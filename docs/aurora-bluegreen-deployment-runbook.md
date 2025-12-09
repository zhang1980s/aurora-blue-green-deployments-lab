# Aurora Blue-Green Deployment Runbook

## Document Information

**Document Version**: 1.0
**Last Updated**: 2025-12-09
**Author**: Aurora Blue-Green Deployment Lab Project
**Based On**: Lab-verified testing with Aurora MySQL 3.04.4 → 3.10.2 across 2 clusters

---

## Table of Contents

1. [Overview](#overview)
2. [Pre-Deployment Checklist](#pre-deployment-checklist)
3. [Deployment Preparation](#deployment-preparation)
4. [Blue-Green Deployment Creation](#blue-green-deployment-creation)
5. [Switchover Execution](#switchover-execution)
6. [Post-Switchover Validation](#post-switchover-validation)
7. [Rollback Procedures](#rollback-procedures)
8. [Troubleshooting](#troubleshooting)
9. [Success Criteria](#success-criteria)

---

## Overview

### Purpose
This runbook provides step-by-step instructions for executing Aurora MySQL Blue-Green deployments with minimal downtime using the AWS Advanced JDBC Wrapper.

### Expected Outcomes
- **Downtime**: 27-29ms (lab-verified)
- **Success Rate**: 100% with automatic retry
- **Failed Transactions**: 0 permanent failures
- **Worker Impact**: 55% experience transient errors (auto-recoverable)

### Known Limitations
- **Read Latency**: Increases 57-133% post-switchover (temporary, 10-15 min recovery)
- **Cluster Performance**: Varies between physical clusters
- **Not Supported**: Aurora Global Database, RDS Multi-AZ clusters

---

## Pre-Deployment Checklist

### Prerequisites Verification

#### ☐ 0. AWS Advanced JDBC Wrapper Version

**Requirement**: Application must use AWS Advanced JDBC Wrapper **version 2.6.8 or later**.

The Blue-Green plugin was introduced in version 2.6.0, with stability improvements in 2.6.8+.

**Verify Version:**
```bash
# Check Maven dependency in pom.xml
grep -A 2 "aws-advanced-jdbc-wrapper" pom.xml

# Expected output (minimum):
# <artifactId>aws-advanced-jdbc-wrapper</artifactId>
# <version>2.6.8</version>
```

**Update if needed:**
```xml
<!-- pom.xml -->
<dependency>
  <groupId>software.amazon.jdbc</groupId>
  <artifactId>aws-advanced-jdbc-wrapper</artifactId>
  <version>2.6.8</version>
</dependency>
```

**Lab-Tested Version**: 2.6.8

#### ☐ 1. Database User Permissions

**Verify permissions on source cluster:**
```sql
-- Test access to topology table
SELECT * FROM mysql.rds_topology LIMIT 1;
```

**Expected Result**: Query returns successfully (even if empty)

**If Access Denied - Grant Permissions:**
```sql
-- Option A: Direct grant
GRANT SELECT ON mysql.rds_topology TO 'your_app_user'@'%';
FLUSH PRIVILEGES;

-- Option B: Using roles (MySQL 8.0+, recommended)
CREATE ROLE IF NOT EXISTS 'bluegreen_reader';
GRANT SELECT ON mysql.rds_topology TO 'bluegreen_reader';
GRANT 'bluegreen_reader' TO 'your_app_user'@'%';
SET DEFAULT ROLE 'bluegreen_reader' TO 'your_app_user'@'%';
FLUSH PRIVILEGES;
```

#### ☐ 2. Binary Logging Configuration

**Check binary logging status:**
```sql
SHOW VARIABLES LIKE 'binlog_format';
```

**Expected Result**: `ROW`, `MIXED`, or `STATEMENT` (ROW recommended)

**If Not Enabled:**
1. Create/modify DB cluster parameter group
2. Set `binlog_format = ROW`
3. Associate parameter group with cluster
4. **Reboot cluster** (required for binlog changes)

**Verify cluster parameter group:**
```bash
aws rds describe-db-clusters \
  --db-cluster-identifier <cluster-name> \
  --query 'DBClusters[0].DBClusterParameterGroup'
```

#### ☐ 3. Multithreaded Replication (Recommended)

**Check current setting:**
```sql
SHOW VARIABLES LIKE 'replica_parallel_workers';
```

**Recommended Values:**
- Instances < 2xlarge: `0` (disabled)
- Instances 2xlarge - 8xlarge: `4`
- Instances > 8xlarge: `8-16`

**Update if needed (no reboot required):**
```bash
# Update DB cluster parameter group
aws rds modify-db-cluster-parameter-group \
  --db-cluster-parameter-group-name <param-group-name> \
  --parameters "ParameterName=replica_parallel_workers,ParameterValue=4,ApplyMethod=immediate"
```

#### ☐ 4. Application Configuration

**Verify JDBC URL includes Blue-Green plugin:**
```
jdbc:aws-wrapper:mysql://<cluster-endpoint>:3306/<database>?wrapperPlugins=initialConnection,auroraConnectionTracker,bg,failover2,efm2&bgdId=1&connectTimeout=30000&socketTimeout=30000&failoverTimeoutMs=60000&failoverClusterTopologyRefreshRateMs=2000&bgConnectTimeoutMs=30000&bgSwitchoverTimeoutMs=180000
```

**Key Parameters Checklist:**
- ☐ `wrapperPlugins` contains `bg`
- ☐ `bgdId=1` (or auto-detect)
- ☐ `bgConnectTimeoutMs=30000`
- ☐ `bgSwitchoverTimeoutMs=180000`

#### ☐ 5. Logging Configuration (Testing/Staging)

**Enable debug logging for Blue-Green plugin:**
```java
// Java application - add to startup code
java.util.logging.Logger.getLogger("software.amazon.jdbc.plugin.bluegreen").setLevel(Level.FINE);
```

**Log4j2 Configuration:**
```xml
<Logger name="software.amazon.jdbc.plugin.bluegreen" level="FINE" additivity="false">
  <AppenderRef ref="Console"/>
</Logger>
```

#### ☐ 6. Monitoring Setup

**CloudWatch Metrics to Monitor:**
- `DatabaseConnections` (current connections)
- `CPUUtilization` (cluster load)
- `AuroraBinlogReplicaLag` (replication lag during PREPARATION)
- `ReadLatency` and `WriteLatency` (performance baseline)

**Application Metrics to Track:**
- Connection pool usage
- Transaction success/failure rate
- P50, P95, P99 latency

---

## Deployment Preparation

### Step 1: Deploy Application with Blue-Green Plugin

**Timing**: 1-2 hours before switchover

#### Action 1.1: Update Application Configuration
```bash
# Update JDBC URL to include 'bg' plugin
# Deploy updated application via rolling restart
```

#### Action 1.2: Verify Plugin Activation
**Check application logs for:**
```
[bgdId: '1'] BG status: NOT_CREATED
```

**Wait 5-10 minutes** for plugin to initialize and establish baseline monitoring.

### Step 2: Establish Performance Baseline

**Timing**: 10 minutes before creating Blue-Green deployment

#### Action 2.1: Collect Current Metrics
```bash
# Query current Aurora version
mysql -h <cluster-endpoint> -u <user> -p<password> -e "SELECT @@aurora_version;"

# Collect baseline metrics
aws cloudwatch get-metric-statistics \
  --namespace AWS/RDS \
  --metric-name ReadLatency \
  --dimensions Name=DBClusterIdentifier,Value=<cluster-id> \
  --start-time $(date -u -d '10 minutes ago' +%Y-%m-%dT%H:%M:%S) \
  --end-time $(date -u +%Y-%m-%dT%H:%M:%S) \
  --period 60 \
  --statistics Average
```

**Document baseline values:**
- Current Aurora version: _____________
- Average read latency: _____________ms
- Average write latency: _____________ms
- Connection count: _____________
- CPU utilization: _____________%

---

## Blue-Green Deployment Creation

### Step 3: Create Blue-Green Deployment

**Timing**: 15-60 minutes for Green cluster readiness

#### Action 3.1: Initiate Deployment
```bash
aws rds create-blue-green-deployment \
  --blue-green-deployment-name <deployment-name> \
  --source-arn arn:aws:rds:<region>:<account-id>:cluster:<source-cluster-id> \
  --target-engine-version 8.0.mysql_aurora.3.10.0 \
  --tags Key=Environment,Value=production Key=Deployment,Value=blue-green-upgrade
```

**Expected Output:**
```json
{
  "BlueGreenDeployment": {
    "BlueGreenDeploymentIdentifier": "bgd-xxxxxxxxxxxxx",
    "Status": "PROVISIONING"
  }
}
```

**Record Deployment ID:** `bgd-_________________`

#### Action 3.2: Monitor Deployment Progress
```bash
# Check deployment status (repeat every 5 minutes)
aws rds describe-blue-green-deployments \
  --blue-green-deployment-identifier <deployment-id> \
  --query 'BlueGreenDeployments[0].Status'
```

**Expected Status Progression:**
1. `PROVISIONING` (5-15 minutes) - Creating Green cluster
2. `AVAILABLE` (15-45 minutes) - Replicating data
3. **Ready for switchover** when status = `AVAILABLE` and replication lag < 1 second

#### Action 3.3: Verify Green Cluster Readiness
```bash
# Check Green cluster details
aws rds describe-blue-green-deployments \
  --blue-green-deployment-identifier <deployment-id> \
  --query 'BlueGreenDeployments[0].Target'

# Monitor replication lag (should be < 1 second)
aws cloudwatch get-metric-statistics \
  --namespace AWS/RDS \
  --metric-name AuroraBinlogReplicaLag \
  --dimensions Name=DBClusterIdentifier,Value=<green-cluster-id> \
  --start-time $(date -u -d '5 minutes ago' +%Y-%m-%dT%H:%M:%S) \
  --end-time $(date -u +%Y-%m-%dT%H:%M:%S) \
  --period 60 \
  --statistics Average
```

**Green Cluster Ready When:**
- ☐ Status = `AVAILABLE`
- ☐ Replication lag < 1 second
- ☐ Green cluster endpoint accessible
- ☐ Application logs show: `[bgdId: '1'] BG status: CREATED` or `PREPARATION`

---

## Switchover Execution

### Step 4: Pre-Switchover Validation

**Timing**: Immediately before triggering switchover

#### Action 4.1: Validate Application Health
```bash
# Check application connection pool
# Verify no stuck connections or high error rate
# Confirm workload is running normally
```

**Health Checks:**
- ☐ Application logs show no connection errors
- ☐ Connection pool utilization < 80%
- ☐ Transaction success rate > 99.9%
- ☐ No manual transactions in progress

#### Action 4.2: Verify Deployment Status
```bash
aws rds describe-blue-green-deployments \
  --blue-green-deployment-identifier <deployment-id> \
  --query 'BlueGreenDeployments[0].Status'
```

**Expected:** `AVAILABLE`

#### Action 4.3: Notify Stakeholders
**Send notification:**
- Switchover starting at: `<timestamp>`
- Expected downtime: 27-29ms
- Expected impact: 55% workers experience transient errors (auto-recoverable)
- Monitoring dashboard: `<link>`

### Step 5: Execute Switchover

**Timing**: 2-7 seconds (critical window)

#### Action 5.1: Trigger Switchover
```bash
# Execute switchover command
aws rds switchover-blue-green-deployment \
  --blue-green-deployment-identifier <deployment-id> \
  --switchover-timeout 300
```

**Expected Output:**
```json
{
  "BlueGreenDeployment": {
    "Status": "SWITCHOVER_IN_PROGRESS"
  }
}
```

#### Action 5.2: Monitor Switchover Progress

**Watch application logs in real-time:**
```bash
# Look for these key log patterns:
# [bgdId: '1'] BG status: PREPARATION → IN_PROGRESS
# [bgdId: '1'] BG status: IN_PROGRESS → POST
# Switched to new host: ip-10-x-x-x
```

**Expected Timeline:**
```
T+0s:  Switchover triggered
T+3-4s: PREPARATION phase (no application impact)
T+5-6s: IN_PROGRESS phase (27-29ms downtime)
        - 55% workers experience transient errors
        - Automatic retry with 500ms backoff
        - 100% recovery expected
T+7s:   POST phase (stabilization)
        - All workers operational on Green cluster
        - Read latency may be elevated (+57-133%)
T+55s:  COMPLETED phase
```

#### Action 5.3: Observe Worker Behavior

**Monitor for expected patterns:**
- ☐ ~55% workers log connection errors (transient)
- ☐ Errors contain: "The active SQL connection has changed"
- ☐ Workers automatically retry within 500ms
- ☐ All workers successfully reconnect to new host
- ☐ First operation after switchover: ~2-2.1 second latency (normal)

---

## Post-Switchover Validation

### Step 6: Immediate Validation (T+0 to T+5 minutes)

#### Action 6.1: Verify Cluster Version
```sql
-- Connect to cluster endpoint
mysql -h <cluster-endpoint> -u <user> -p<password>

-- Check Aurora version
SELECT @@aurora_version;
```

**Expected:** `3.10.2` (or target version)

#### Action 6.2: Check Application Health
**Verify in application logs:**
- ☐ All workers operational
- ☐ No ongoing connection errors
- ☐ Transaction success rate = 100%
- ☐ New host confirmed: `ip-10-x-x-x` (different from old host)

#### Action 6.3: Validate Deployment Status
```bash
aws rds describe-blue-green-deployments \
  --blue-green-deployment-identifier <deployment-id> \
  --query 'BlueGreenDeployments[0].Status'
```

**Expected:** `SWITCHOVER_COMPLETED`

### Step 7: Performance Validation (T+5 to T+15 minutes)

#### Action 7.1: Monitor Read Latency (CRITICAL)

**Collect post-switchover read latency:**
```bash
aws cloudwatch get-metric-statistics \
  --namespace AWS/RDS \
  --metric-name ReadLatency \
  --dimensions Name=DBClusterIdentifier,Value=<cluster-id> \
  --start-time $(date -u -d '5 minutes ago' +%Y-%m-%dT%H:%M:%S) \
  --end-time $(date -u +%Y-%m-%dT%H:%M:%S) \
  --period 60 \
  --statistics Average
```

**Expected Behavior:**
- Read latency elevated by **57-133%** from baseline
- Example: 0.3ms baseline → 0.7ms post-switchover (+133%)
- Example: 0.7ms baseline → 1.1-1.4ms post-switchover (+57-100%)

**Action Required:**
- ☐ Monitor for 10-15 minutes to observe stabilization
- ☐ Alert if latency exceeds baseline by >150%
- ☐ Consider scaling read replicas if read-heavy workload

#### Action 7.2: Validate Write Performance
**Check write latency (should be unchanged):**
```bash
aws cloudwatch get-metric-statistics \
  --namespace AWS/RDS \
  --metric-name WriteLatency \
  --dimensions Name=DBClusterIdentifier,Value=<cluster-id> \
  --start-time $(date -u -d '5 minutes ago' +%Y-%m-%dT%H:%M:%S) \
  --end-time $(date -u +%Y-%m-%dT%H:%M:%S) \
  --period 60 \
  --statistics Average
```

**Expected:** Write latency unchanged (2-4ms typical)

#### Action 7.3: Database Connection Validation
```sql
-- Verify cluster topology
SELECT * FROM mysql.ro_replica_status WHERE deployment_type = 'BLUE_GREEN';

-- Check active connections
SHOW PROCESSLIST;

-- Verify replication status (if applicable)
SHOW REPLICA STATUS\G
```

### Step 8: Extended Monitoring (T+15 to T+60 minutes)

#### Action 8.1: Monitor Read Latency Stabilization
**Check if read latency returns to baseline:**
- At T+15 min: Read latency = _______ms
- At T+30 min: Read latency = _______ms
- At T+45 min: Read latency = _______ms
- At T+60 min: Read latency = _______ms

**Evaluation:**
- ☐ Latency returning to baseline → **Temporary warm-up effect** (expected)
- ☐ Latency stabilized at elevated level → **Aurora 3.10.2 characteristic or cluster-specific**

#### Action 8.2: Application Performance Review
**Collect final metrics:**
```bash
# Total operations during switchover window
# Transaction failure count (should be 0 permanent)
# Worker affectation rate (expect ~55%)
# Recovery time (expect < 1 second)
```

---

## Rollback Procedures

### Scenario 1: Rollback Before Switchover

**Use Case:** Green cluster not ready, or issues detected during PREPARATION

#### Action: Delete Blue-Green Deployment
```bash
aws rds delete-blue-green-deployment \
  --blue-green-deployment-identifier <deployment-id> \
  --delete-target true
```

**Result:**
- Green cluster deleted
- Blue cluster remains primary (no impact)
- Application continues normal operations

### Scenario 2: Rollback After Switchover

**Use Case:** Critical issues discovered post-switchover (within 1-2 hours)

#### Step 1: Assess Situation
**Critical Issues Requiring Rollback:**
- Application errors > 5% sustained for > 5 minutes
- Read latency > 200% of baseline after 30 minutes
- Data inconsistencies detected
- Critical application functionality broken

#### Step 2: Create Reverse Blue-Green Deployment
```bash
# Create new deployment with Green (current) as source
aws rds create-blue-green-deployment \
  --blue-green-deployment-name rollback-deployment \
  --source-arn arn:aws:rds:<region>:<account-id>:cluster:<green-cluster-id> \
  --target-engine-version 8.0.mysql_aurora.3.04.4

# Wait for deployment ready (15-60 minutes)

# Execute switchover back to original version
aws rds switchover-blue-green-deployment \
  --blue-green-deployment-identifier <new-deployment-id>
```

**Note:** Rollback follows same switchover process (27-29ms downtime expected)

### Scenario 3: Emergency Rollback (Manual Failover)

**Use Case:** Blue-Green deployment stuck or unresponsive

#### Action: Manual Failover to Old Blue Cluster
```bash
# 1. Update application JDBC URL to point to old Blue cluster endpoint
# 2. Restart application with updated configuration
# 3. Delete stuck Blue-Green deployment
aws rds delete-blue-green-deployment \
  --blue-green-deployment-identifier <deployment-id> \
  --delete-target false
```

**Warning:** This method results in **11-20 seconds** downtime (no Blue-Green plugin coordination)

---

## Troubleshooting

### Issue 1: Deployment Creation Fails

**Symptom:**
```
Error: Binary logging is not enabled on the source cluster
```

**Resolution:**
1. Enable binary logging (see Pre-Deployment Checklist #2)
2. Reboot cluster
3. Retry deployment creation

---

### Issue 2: High Replication Lag During PREPARATION

**Symptom:**
```
AuroraBinlogReplicaLag > 10 seconds after 30 minutes
```

**Resolution:**
1. Check `replica_parallel_workers` setting (should be 4-16)
2. Reduce write workload temporarily
3. Wait for replication to catch up (do NOT trigger switchover)

**Verify:**
```sql
SHOW REPLICA STATUS\G
-- Look for: Seconds_Behind_Master < 1
```

---

### Issue 3: Application Logs Show Persistent Connection Errors

**Symptom:**
```
[ERROR] Worker-X | connection_error | Retry 5/5 failed
```

**Resolution:**
1. Verify Blue-Green plugin is active:
```bash
# Check JDBC URL contains: wrapperPlugins=...bg...
```

2. Check deployment status:
```bash
aws rds describe-blue-green-deployments \
  --blue-green-deployment-identifier <deployment-id>
```

3. If IN_PROGRESS for > 10 seconds → Potential issue, monitor closely
4. If errors persist > 30 seconds → Contact AWS Support

---

### Issue 4: Read Latency Remains Elevated (> 60 minutes)

**Symptom:**
```
Read latency > 150% baseline after 60 minutes
```

**Investigation:**
```sql
-- Check buffer pool usage
SHOW STATUS LIKE 'Innodb_buffer_pool%';

-- Check query performance
SHOW FULL PROCESSLIST;

-- Check for long-running queries
SELECT * FROM information_schema.processlist WHERE time > 60;
```

**Resolution Options:**
1. **Wait longer** - Buffer pool warm-up may take 2-3 hours for large datasets
2. **Scale read replicas** - Offload read traffic temporarily
3. **Optimize queries** - Review slow query log
4. **Consider rollback** - If business-critical performance SLA breached

---

### Issue 5: Switchover Timeout

**Symptom:**
```
Error: Switchover operation timed out after 300 seconds
```

**Resolution:**
1. Check deployment status:
```bash
aws rds describe-blue-green-deployments \
  --blue-green-deployment-identifier <deployment-id>
```

2. If status = `SWITCHOVER_FAILED`:
   - Green cluster remains standby
   - Blue cluster still primary
   - Application unaffected
   - Retry switchover or delete deployment

3. If status = `SWITCHOVER_IN_PROGRESS`:
   - Wait additional 5 minutes
   - Monitor application logs
   - Do NOT retry or delete deployment

---

## Success Criteria

### Deployment Success Checklist

#### Technical Success Criteria
- ☐ Pure downtime: < 100ms (target: 27-29ms)
- ☐ Permanent failed transactions: 0
- ☐ Transaction success rate: 100% (after retry)
- ☐ Aurora version upgraded: ✅ (verify with `SELECT @@aurora_version;`)
- ☐ Write latency unchanged: ✅ (2-4ms baseline maintained)
- ☐ Read latency elevated but stabilizing: ⚠️ (57-133% increase acceptable)
- ☐ Worker affectation: ~55% (11 of 20 workers affected, expected)
- ☐ All workers recovered: ✅ (within 1 second)
- ☐ Blue-Green lifecycle completed: ✅ (NOT_CREATED → COMPLETED)

#### Operational Success Criteria
- ☐ Zero manual intervention required
- ☐ No application code changes needed
- ☐ No customer-visible errors
- ☐ No data loss or corruption
- ☐ Monitoring dashboards show healthy state
- ☐ Old Blue cluster ready for decommission

#### Business Success Criteria
- ☐ SLA maintained (if < 100ms downtime)
- ☐ No customer complaints
- ☐ No revenue impact
- ☐ Upgrade completed within maintenance window

---

## Post-Deployment Cleanup

### Step 9: Decommission Old Blue Cluster (Optional)

**Timing:** After 24-72 hours of stable operation

#### Action 9.1: Final Validation
**Verify Green cluster stability:**
- ☐ 24+ hours of stable operation
- ☐ No unexpected errors or degradation
- ☐ Read latency returned to acceptable levels
- ☐ All monitoring metrics healthy

#### Action 9.2: Delete Blue-Green Deployment
```bash
# Option 1: Delete deployment and old Blue cluster
aws rds delete-blue-green-deployment \
  --blue-green-deployment-identifier <deployment-id> \
  --delete-target true

# Option 2: Keep old Blue cluster for extended rollback window
aws rds delete-blue-green-deployment \
  --blue-green-deployment-identifier <deployment-id> \
  --delete-target false

# Manually delete old cluster later
aws rds delete-db-cluster \
  --db-cluster-identifier <old-blue-cluster-id> \
  --skip-final-snapshot
```

#### Action 9.3: Update Documentation
**Document deployment results:**
- Actual downtime: _______ms
- Worker affectation rate: _______%
- Read latency impact: +______%
- Time to stabilization: _______minutes
- Lessons learned: _______________________

---

## Appendix A: Quick Reference Commands

### Check Aurora Version
```sql
SELECT @@aurora_version;
```

### Monitor Blue-Green Status
```bash
aws rds describe-blue-green-deployments \
  --blue-green-deployment-identifier <deployment-id>
```

### Check Replication Lag
```bash
aws cloudwatch get-metric-statistics \
  --namespace AWS/RDS \
  --metric-name AuroraBinlogReplicaLag \
  --dimensions Name=DBClusterIdentifier,Value=<cluster-id> \
  --start-time $(date -u -d '5 minutes ago' +%Y-%m-%dT%H:%M:%S) \
  --end-time $(date -u +%Y-%m-%dT%H:%M:%S) \
  --period 60 \
  --statistics Average
```

### Application Health Check
```bash
# Verify Blue-Green plugin active
grep "BG status:" /path/to/application.log | tail -5

# Count connection errors during switchover
grep "connection_error" /path/to/application.log | grep "$(date +%Y-%m-%d)" | wc -l
```

---

## Appendix B: Lab-Verified Results

### Test 055308 (database-268-a)
- **Downtime**: 29ms
- **Baseline Read Latency**: 0.7-0.9ms → 1.1-1.4ms post-switchover (+57-100%)
- **Workers Affected**: 11 of 20 (7 read + 4 write)
- **Total Operations**: 117,231 (100% success)

### Test 070455 (database-268-b)
- **Downtime**: 27ms (fastest measured)
- **Baseline Read Latency**: 0.3ms → 0.7ms post-switchover (+133%)
- **Workers Affected**: 11 of 20 (6 read + 5 write)
- **Total Operations**: 117,081+ (100% success)

### Key Findings
- **Fastest Downtime**: 27ms (65-68% faster than standard failover)
- **Consistent Worker Affectation**: 55% across both clusters
- **Read Latency Elevation**: Varies by cluster baseline (57-133%)
- **Cross-Cluster Validation**: Same plugin behavior on different physical infrastructure

---

## Appendix C: Blue-Green Phase Log Keywords

Use these keywords to identify Blue-Green switchover phases in application logs and trace the switchover timeline.

### Phase Transition Keywords

**Blue-Green Status Changes:**
```
[bgdId: '1'] BG status: NOT_CREATED
[bgdId: '1'] BG status: CREATED
[bgdId: '1'] BG status: PREPARATION
[bgdId: '1'] BG status: IN_PROGRESS     ← Critical switchover window begins
[bgdId: '1'] BG status: POST            ← Switchover completed
[bgdId: '1'] BG status: COMPLETED
```

**Timeline Markers:**
- `NOT_CREATED → CREATED` - Blue-Green deployment detected
- `CREATED → PREPARATION` - Green cluster being prepared (no application impact)
- `PREPARATION → IN_PROGRESS` - Active switchover initiated (downtime window)
- `IN_PROGRESS → POST` - Switchover completed, stabilization begins
- `POST → COMPLETED` - Deployment finished, old environment can be decommissioned

### Connection Event Keywords

**Host Switching:**
```
Switched to new host: ip-10-x-x-x        ← Worker successfully connected to Green cluster
Routing connections to GREEN_WRITER
Suspending new connections               ← IN_PROGRESS phase detected
```

**Failover Events:**
```
Failover                                 ← Connection failover triggered
The active SQL connection has changed    ← Typical error during switchover
connection_error                         ← Connection failure logged
Retry 1/5                               ← Automatic retry with backoff
```

### Performance Marker Keywords

**Operation Results:**
```
SUCCESS: Worker-X | Host: ip-10-x-x-x   ← Successful operation
ERROR: Worker-X | connection_lost       ← Transient error during switchover
Latency: 2,062ms                        ← First operation after switchover (elevated)
Latency: 2-4ms                          ← Normal operation latency
```

**Statistics:**
```
STATS: Total: X | Success: Y | Failed: Z | Success Rate: 100.00%
```

### Search Commands

**Find Blue-Green phase transitions:**
```bash
# Search for all phase transitions
grep "BG status:" /path/to/application.log

# Find switchover window
grep -A 5 "IN_PROGRESS" /path/to/application.log

# Identify connection errors during switchover
grep "connection_error\|active SQL connection" /path/to/application.log | grep "$(date +%Y-%m-%d)"

# Count affected workers
grep "Retry 1/5" /path/to/application.log | wc -l

# Find first new connection after switchover
grep "Switched to new host" /path/to/application.log | head -1
```

**Timeline Reconstruction:**
```bash
# Extract full switchover timeline
grep -E "BG status:|Switched to new host|connection_error|SUCCESS.*Latency: [0-9]{4}" /path/to/application.log | \
  grep "$(date +%Y-%m-%d)" | \
  awk '{print $1, $2, $0}'
```

### Expected Log Pattern During Switchover

**Typical Sequence (27-29ms downtime):**
```
05:54:07.199  [bgdId: '1'] BG status: PREPARATION
05:54:11.576  [bgdId: '1'] BG status: IN_PROGRESS
05:54:13.689  Switched to new host: ip-10-5-2-57 (first new connection)
05:54:13.709  ERROR: Worker-5 | connection_error | The active SQL connection has changed
05:54:13.718  SUCCESS: Worker-10 | Host: ip-10-5-2-22 (last old connection)
05:54:14.210  SUCCESS: Worker-5 | Host: ip-10-5-2-57 | Retry 1/5 | Latency: 2-4ms
05:54:13.652  [bgdId: '1'] BG status: POST
05:55:09.280  [bgdId: '1'] BG status: COMPLETED
```

**Key Metrics from Logs:**
- **Pure Downtime**: Time between first `Switched to new host` and last operation on old host (27-29ms expected)
- **Workers Affected**: Count of `connection_error` logs (expect ~55% of workers)
- **Recovery Time**: Time from first error to successful retry (500-600ms expected)
- **First Operation Latency**: Latency of first operation after switchover (2,000-2,100ms expected)

**Practical Usage During Switchover:**

1. **Before Switchover** - Verify plugin is active:
   ```bash
   grep "BG status:" /path/to/application.log | tail -1
   # Expected: [bgdId: '1'] BG status: CREATED or PREPARATION
   ```

2. **During Switchover** - Monitor real-time (open in separate terminal):
   ```bash
   tail -f /path/to/application.log | grep --line-buffered -E "BG status:|Switched|connection_error|ERROR"
   ```

3. **Post-Switchover** - Validate success:
   ```bash
   # Check final status
   grep "BG status: COMPLETED" /path/to/application.log

   # Count failures
   grep "connection_error" /path/to/application.log | grep "$(date +%Y-%m-%d)" | wc -l

   # Verify all workers recovered
   grep "Retry 1/5" /path/to/application.log | wc -l
   ```

---

## Document Revision History

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2025-12-09 | Aurora Blue-Green Lab | Initial runbook creation based on lab testing |

---

**For questions or issues, refer to:**
- [AWS Advanced JDBC Wrapper Documentation](https://github.com/aws/aws-advanced-jdbc-wrapper)
- [Aurora Blue-Green Deployments Guide](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/blue-green-deployments.html)
- [Architecture Deep Dive](./aws-jdbc-wrapper-bluegreen-architecture.md)

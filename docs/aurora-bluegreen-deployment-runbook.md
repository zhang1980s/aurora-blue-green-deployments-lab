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
- **Downtime**: few seconds, depends on the real workload
- **Success Rate**: 100% with automatic retry
- **Failed Transactions**: 0 permanent failures
- **Worker Impact**: Some workers experience transient errors during switchover, automatically recovered by JDBC wrapper retry mechanism (5 attempts, 500ms backoff). Applications with try-catch blocks will observe SQLException but should log and continue, not fail. See [Appendix D](#appendix-d-application-error-handling-with-try-catch-blocks) for detailed error handling guidance.

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

**Verify permissions on both Blue and Green clusters:**
```sql
-- Test access to topology table
SELECT * FROM mysql.rds_topology LIMIT 1;
```

**Expected Result**: Query returns successfully (even if empty)

**Important**: These permissions must be granted on both Blue and Green clusters and should NOT be revoked after switchover.

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
jdbc:aws-wrapper:mysql://<cluster-endpoint>:3306/<database>?wrapperPlugins=initialConnection,auroraConnectionTracker,bg,failover2,efm2&connectTimeout=30000&socketTimeout=30000&failoverTimeoutMs=60000&failoverClusterTopologyRefreshRateMs=2000
```

**Key Parameters Checklist:**
- ☐ `wrapperPlugins=initialConnection,auroraConnectionTracker,bg,failover2,efm2`
  - `initialConnection`: Establishes initial connection properties and validation
  - `auroraConnectionTracker`: Tracks Aurora cluster topology and connection state
  - `bg` (Blue-Green): Monitors Blue-Green deployment status for coordinated switchover
  - `failover2`: Handles cluster-level failover scenarios (version 2)
  - `efm2` (Enhanced Failure Monitoring v2): Proactive connection health monitoring
- ☐ `bgdId=1` (or auto-detect)

#### ☐ 5. Logging Configuration (Testing/Staging)

**Enable debug logging for Blue-Green plugin:**

**Option 1: Configure in JDBC URL**
```
jdbc:aws-wrapper:mysql://<cluster-endpoint>:3306/<database>?wrapperPlugins=initialConnection,auroraConnectionTracker,bg,failover2,efm2&wrapperLoggerLevel=FINE
```

**Option 2: Java application code**
```java
// Java application - add to startup code
java.util.logging.Logger.getLogger("software.amazon.jdbc.plugin.bluegreen").setLevel(Level.FINE);
```

**Option 3: Log4j2 Configuration**
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
- ☐ Application logs show: `[bgdId: '1'] BG status: CREATED`

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
- Expected downtime: few seconds
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
T+5-6s: IN_PROGRESS phase (few seconds downtime)
        - Automatic retry with 500ms backoff
        - 100% recovery expected
T+7s:   POST phase (stabilization)
        - All workers operational on Green cluster
T+55s:  COMPLETED phase
```

#### Action 5.3: Observe Worker Behavior

**Monitor for expected patterns:**
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

#### Action 7.1: Monitor Read Latency

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

**Expected:** Read latency unchanged

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

**Expected:** Write latency unchanged

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

#### Action 8.1: Application Performance Review
**Collect final metrics:**
```bash
# Total operations during switchover window
# Transaction failure count (should be 0 permanent)
# Worker affectation rate
# Recovery time (expect few seconds)
```

---

## Success Criteria

### Deployment Success Checklist

#### Technical Success Criteria
- ☐ Pure downtime: few seconds
- ☐ Permanent failed transactions: 0
- ☐ Transaction success rate: 100% (after retry)
- ☐ Aurora version upgraded: ✅ (verify with `SELECT @@aurora_version;`)
- ☐ Write latency unchanged: ✅ (2-4ms baseline maintained)
- ☐ All workers recovered: ✅
- ☐ Blue-Green lifecycle completed: ✅ (NOT_CREATED → COMPLETED)

#### Operational Success Criteria
- ☐ Zero manual intervention required
- ☐ No application code changes needed
- ☐ No customer-visible errors
- ☐ No data loss or corruption
- ☐ Monitoring dashboards show healthy state
- ☐ Old Blue cluster ready for decommission

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
- **Workers Affected**: 11 of 20 (7 read + 4 write)
- **Total Operations**: 117,231 (100% success)

### Test 070455 (database-268-b)
- **Downtime**: 27ms (fastest measured)
- **Workers Affected**: 11 of 20 (6 read + 5 write)
- **Total Operations**: 117,081+ (100% success)

### Key Findings
- **Fastest Downtime**: 27ms
- **Consistent Worker Affectation**: 55% across both clusters
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

**Typical Sequence (few seconds downtime):**
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
- **Pure Downtime**: Time between first `Switched to new host` and last operation on old host (few seconds expected)
- **Workers Affected**: Count of `connection_error` logs
- **Recovery Time**: Time from first error to successful retry
- **First Operation Latency**: Latency of first operation after switchover

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

## Appendix D: Application Error Handling with Try-Catch Blocks

### Overview

When applications have try-catch blocks around database operations, they **WILL observe** transient SQLException errors during Blue-Green switchover. However, the JDBC wrapper's automatic retry mechanism still works transparently, ensuring 100% success rate.

### What Happens During Switchover

```java
try {
    statement.execute(sql);
    // Success path
} catch (SQLException e) {
    // Application WILL catch this error during switchover
    logger.error("Database operation failed", e);
    // Error message: "The active SQL connection has changed"
}
```

**Expected Behavior:**
- **55% of workers** will catch SQLException (11 out of 20 workers in lab tests)
- **45% of workers** will not experience errors (immediate success)
- **100% success rate** after automatic retry (no permanent failures)

### Timeline of Error and Recovery

```
T+0ms:   Switchover begins (IN_PROGRESS phase)
T+27ms:  Worker hits connection error
         → SQLException thrown to application
         → Application catch block executes
         → Application logs the error
T+527ms: JDBC wrapper automatically retries (500ms backoff)
         → Retry succeeds on Green cluster
         → Next operation returns to normal latency
```

### Recommended Application Code Patterns

#### Pattern 1: Log-and-Continue (✅ Recommended)

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatabaseService {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseService.class);

    public void executeOperation(Statement statement, String sql) {
        try {
            statement.execute(sql);
            logger.debug("Operation succeeded");

        } catch (SQLException e) {
            // Log for monitoring but DON'T fail the operation
            logger.warn("Database operation encountered transient error: {}",
                e.getMessage());

            // Increment metrics counter
            metrics.incrementCounter("database.transient_errors");

            // JDBC wrapper will retry automatically (5 attempts, 500ms backoff)
            // No action needed - the retry is transparent

            // Optional: Small delay before next operation
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
```

**Why this works:**
- Application observes the error (for alerting/monitoring)
- But doesn't fail the transaction
- JDBC wrapper retry handles recovery automatically
- Next operation succeeds after wrapper reconnects

#### Pattern 2: Application-Level Retry (⚠️ Not Recommended)

```java
public void executeWithRetry(Statement statement, String sql) {
    int maxRetries = 3;
    for (int i = 0; i < maxRetries; i++) {
        try {
            statement.execute(sql);
            return; // Success
        } catch (SQLException e) {
            if (i == maxRetries - 1) {
                throw new DatabaseException("Failed after retries", e);
            }
            logger.warn("Retry {}/{}: {}", i+1, maxRetries, e.getMessage());
            Thread.sleep(500);
        }
    }
}
```

**Why this is unnecessary:**
- JDBC wrapper already retries (5 attempts, 500ms backoff)
- Adds duplicate retry logic
- Can cause longer delays than necessary
- Increases code complexity

#### Pattern 3: Fail-Fast (❌ Not Recommended for Blue-Green)

```java
public void executeWithFailFast(Statement statement, String sql) {
    try {
        statement.execute(sql);
    } catch (SQLException e) {
        logger.error("Database operation failed", e);
        throw new DatabaseException("Operation failed", e);
    }
}
```

**Why this fails:**
- Application terminates the operation permanently
- JDBC wrapper retry still happens, but application already failed
- Results in unnecessary failures during switchover
- Violates Blue-Green zero-downtime objective

### Required Configuration

Based on WorkloadSimulator.java lab testing:

```java
// HikariCP Connection Pool Configuration
HikariConfig hikariConfig = new HikariConfig();
hikariConfig.setConnectionTimeout(30000);      // 30 seconds
hikariConfig.setIdleTimeout(600000);           // 10 minutes
hikariConfig.setMaxLifetime(1800000);          // 30 minutes
hikariConfig.setMaximumPoolSize(100);          // 10 connections per worker
hikariConfig.setMinimumIdle(10);

// JDBC URL with Blue-Green Plugin
String jdbcUrl = "jdbc:aws-wrapper:mysql://<cluster-endpoint>:3306/lab_db?" +
    "wrapperPlugins=initialConnection,auroraConnectionTracker,bg,failover2,efm2&" +
    "connectTimeout=30000&" +          // TCP connection timeout
    "socketTimeout=30000&" +           // Socket read timeout
    "failoverTimeoutMs=60000&" +       // Failover completion timeout
    "bgConnectTimeoutMs=30000&" +      // BG switchover connection timeout
    "bgSwitchoverTimeoutMs=180000";    // BG switchover process timeout (3 min)

hikariConfig.setJdbcUrl(jdbcUrl);
```

### Expected Metrics During Switchover

From lab testing (Test 055308 and 070455):

| Metric | Value | Notes |
|--------|-------|-------|
| **Pure Downtime** | 27-29ms | Connection unavailability window |
| **Workers Catching Errors** | 55% (11 of 20) | Will execute catch block |
| **Workers Unaffected** | 45% (9 of 20) | No SQLException observed |
| **Retry Success Rate** | 100% | All retries succeed on Green cluster |
| **First Operation Latency** | 2,000-2,100ms | First operation after switchover |
| **Normal Latency** | 2-4ms (writes)<br>0.3-0.7ms (reads) | Returns to baseline after first operation |
| **JDBC Wrapper Retries** | 5 attempts | 500ms backoff between attempts |

### Production Deployment Checklist

**✅ DO:**
- Log SQLException for monitoring (warn/info level)
- Track transient error count in metrics
- Trust JDBC wrapper's automatic retry mechanism
- Configure connection pool timeouts ≥ 30 seconds
- Monitor latency spikes (expect 2+ seconds for first operation)
- Set up alerts for sustained errors (> 1 minute)

**❌ DON'T:**
- Throw exceptions that terminate the application
- Implement redundant application-level retry logic
- Use short timeout values (< 15 seconds)
- Fail operations on first SQLException
- Disable JDBC wrapper retry mechanism

### Monitoring and Alerting

**Metrics to Track:**
```java
// Count transient errors
metrics.incrementCounter("database.transient_errors",
    Tags.of("error_type", "connection_changed"));

// Track latency spikes
metrics.timer("database.operation_latency").record(duration);

// Alert thresholds
if (transientErrorRate > 100 errors/min for > 2 minutes) {
    alert("Sustained database connectivity issues");
}
```

**Expected During Switchover:**
- **11-20 transient errors** for 20-worker configuration
- **2+ second latency** for first operations
- **Recovery within 500ms** for all workers
- **Return to baseline** within 10-15 minutes (reads may take longer)

### Example Production Code

Complete example with all best practices:

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class ProductionDatabaseService {
    private static final Logger logger = LoggerFactory.getLogger(ProductionDatabaseService.class);
    private final MeterRegistry metrics;

    public ProductionDatabaseService(MeterRegistry metrics) {
        this.metrics = metrics;
    }

    public void executeOperation(Connection connection, String sql) {
        long startTime = System.nanoTime();

        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);

            // Track success
            long duration = System.nanoTime() - startTime;
            metrics.timer("database.operation.success")
                .record(duration, TimeUnit.NANOSECONDS);

            logger.debug("Operation succeeded in {}ms", duration / 1_000_000);

        } catch (SQLException e) {
            // Log transient error for monitoring
            logger.warn("Database operation encountered transient error (JDBC wrapper will retry): {} - {}",
                e.getSQLState(), e.getMessage());

            // Track transient error for alerting
            metrics.counter("database.transient_errors",
                "error_code", e.getSQLState(),
                "error_type", "connection_changed").increment();

            // DO NOT throw - let JDBC wrapper retry mechanism handle it
            // The wrapper will retry up to 5 times with 500ms backoff

            // Optional: Brief delay before next operation
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
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

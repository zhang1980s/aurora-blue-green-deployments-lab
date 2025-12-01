# AWS Advanced JDBC Wrapper - Blue-Green Deployment Architecture

## Table of Contents
- [Overview](#overview)
- [Architecture Components](#architecture-components)
- [How It Works](#how-it-works)
- [Deployment Phases](#deployment-phases)
- [Connection Handling During Switchover](#connection-handling-during-switchover)
- [Configuration Guide](#configuration-guide)
- [Best Practices](#best-practices)
- [Technical Deep Dive](#technical-deep-dive)

---

## Overview

The AWS Advanced JDBC Wrapper's Blue-Green Deployment Plugin provides intelligent connection management during Aurora and RDS database version upgrades, minimizing application downtime from typical **11-20 seconds** to just **3-5 seconds**.

### Supported Databases

| Database Engine | Minimum Version Required |
|----------------|--------------------------|
| Aurora MySQL | Engine Release 3.07+ |
| Aurora PostgreSQL | Engine Release 17.5, 16.9, 15.13, 14.18, 13.21+ |
| RDS PostgreSQL | rds_tools v1.7 (17.1, 16.5, 15.9, 14.14, 13.17, 12.21)+ |

### Limitations

- **Not Supported**: Aurora Global Database with Blue-Green deployments
- **Not Supported**: RDS Multi-AZ clusters with Blue-Green deployments

---

## Architecture Components

The Blue-Green plugin is built on several interconnected components that work together to provide seamless connection management:

```
┌─────────────────────────────────────────────────────────────────┐
│                        Application Layer                        │
│                    (Your Java Application)                      │
└────────────────────────────┬────────────────────────────────────┘
                             │ JDBC Connection Request
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│               AWS Advanced JDBC Wrapper                         │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │         BlueGreenConnectionPlugin                       │   │
│  │  • Connection routing logic                             │   │
│  │  • Deployment status coordination                       │   │
│  │  • Failover handling                                    │   │
│  └───────────────────┬─────────────────────────────────────┘   │
│                      │                                           │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │      BlueGreenStatusMonitor                             │   │
│  │  • Continuous polling (baseline: every 5s)              │   │
│  │  • High-frequency polling during switchover (every 1s)  │   │
│  │  • Phase transition detection                           │   │
│  └───────────────────┬─────────────────────────────────────┘   │
│                      │                                           │
│  ┌─────────────────────────────────────────────────────────┐   │
│  │      BlueGreenStatus (State Object)                     │   │
│  │  • Current deployment phase                             │   │
│  │  • Connection routing rules                             │   │
│  │  • Execution routing rules                              │   │
│  │  • Host role mapping (Blue/Green)                       │   │
│  └─────────────────────────────────────────────────────────┘   │
└────────────────────────────┬────────────────────────────────────┘
                             │ Database Queries
                             ▼
┌─────────────────────────────────────────────────────────────────┐
│                 Aurora/RDS Database Cluster                     │
│  ┌─────────────────────┐         ┌─────────────────────┐       │
│  │   Blue Environment  │         │  Green Environment  │       │
│  │  (Current/Writer)   │────────▶│    (Standby)        │       │
│  │  Version: 3.04      │         │  Version: 3.10      │       │
│  └─────────────────────┘         └─────────────────────┘       │
│                                                                 │
│  Metadata Tables:                                               │
│  • mysql.ro_replica_status (Aurora MySQL)                       │
│  • rds_tools.bluegreen_deployment_status (RDS PostgreSQL)       │
└─────────────────────────────────────────────────────────────────┘
```

### Key Components

#### 1. BlueGreenConnectionPlugin
**Purpose**: Orchestrates connection management during Blue-Green deployments

**Key Responsibilities**:
- Intercepts connection establishment requests
- Applies intelligent routing based on deployment status
- Handles connection retries with fallback logic
- Coordinates with the status monitor for real-time deployment awareness

**Source**: `BlueGreenConnectionPlugin.java`

#### 2. BlueGreenStatusMonitor
**Purpose**: Continuously monitors the Blue-Green deployment state

**Key Responsibilities**:
- Polls database metadata tables for deployment status
- Detects phase transitions (e.g., PREPARATION → IN_PROGRESS → COMPLETED)
- Adjusts polling frequency based on deployment phase
- Tracks cluster topology changes (endpoint IP addresses, host roles)
- Triggers callbacks when status changes occur

**Polling Strategy**:
- **Baseline Mode**: Every 5 seconds (configurable via `bgBaselineMs`)
- **High-Frequency Mode**: Every 1 second during active switchover
- **Adaptive**: Automatically adjusts based on detected deployment phase

**Source**: `BlueGreenStatusMonitor.java`

#### 3. BlueGreenStatus
**Purpose**: Immutable state object representing deployment status

**Key Attributes**:
```java
{
  "bgdId": "blue-green-deployment-12345",
  "currentPhase": "IN_PROGRESS",
  "connectRouting": [
    {"sourceRole": "WRITER", "targetRole": "WRITER"},
    {"sourceRole": "READER", "targetRole": "READER"}
  ],
  "executeRouting": [
    {"sourceRole": "WRITER", "targetRole": "GREEN_WRITER"}
  ],
  "roleByHost": {
    "ip-10-0-1-45": "BLUE_WRITER",
    "ip-10-0-2-78": "GREEN_WRITER"
  }
}
```

**Source**: `BlueGreenStatus.java`

#### 4. BlueGreenPhase
**Purpose**: Enum representing deployment lifecycle stages

**Source**: `BlueGreenPhase.java`

#### 5. Supporting Components
- **BlueGreenRole.java**: Defines roles (BLUE_WRITER, GREEN_WRITER, etc.)
- **BlueGreenProviderSupplier.java**: Supplies deployment metadata providers
- **OnBlueGreenStatusChange.java**: Callback interface for status change events
- **routing/**: Connection and execution routing logic implementation

---

## How It Works

### Phase 1: Normal Operation (No Blue-Green Deployment)

```
Application → JDBC Wrapper → Blue Cluster (Writer)
              ↓
         Direct connection
         No routing overhead
```

- Plugin is dormant or disabled
- Connections flow directly to the Blue (current) cluster
- No additional latency introduced

### Phase 2: Blue-Green Deployment Created

```bash
aws rds create-blue-green-deployment \
  --blue-green-deployment-name aurora-upgrade-lab \
  --source-arn arn:aws:rds:us-east-1:123456789012:cluster:my-cluster \
  --target-engine-version 8.0.mysql_aurora.3.10.0
```

**What Happens**:
1. AWS creates a Green cluster as a clone of the Blue cluster
2. Green cluster receives the target engine version (e.g., 3.10)
3. AWS begins replicating changes from Blue to Green

**Plugin Behavior**:
- `BlueGreenStatusMonitor` detects deployment creation via metadata queries
- Status changes to `CREATED` or `PREPARATION` phase
- Plugin continues routing all traffic to Blue cluster
- Polling frequency remains at baseline (5 seconds)

**Metadata Query Example (Aurora MySQL)**:
```sql
SELECT
  deployment_identifier,
  current_phase,
  topology_json
FROM mysql.ro_replica_status
WHERE deployment_type = 'BLUE_GREEN';
```

### Phase 3: Switchover Initiated

```bash
aws rds switchover-blue-green-deployment \
  --blue-green-deployment-identifier bgd-12345
```

**Critical Transition Period**:

```
Timeline:  0s          1s          2s          3s          4s          5s
           │           │           │           │           │           │
Phase:     PREPARATION → IN_PROGRESS ─────────────────────→ COMPLETED
           │           │                       │           │
Plugin:    Normal      Suspend all             Resume      Normal
           routing     connections             to Green    routing
           │           │                       │           │
Traffic:   ██████████  ░░░░░░░░░░░░░░░░░░░░░  ██████████
           To Blue     (3-5s downtime)         To Green
```

**Step-by-Step Breakdown**:

#### Time: T+0s (Switchover Request)
```
User executes: aws rds switchover-blue-green-deployment
```

#### Time: T+0.5s (Detection)
- `BlueGreenStatusMonitor` detects phase change to `IN_PROGRESS`
- Polling frequency increases to 1-second intervals
- Plugin triggers `OnBlueGreenStatusChange` callback

#### Time: T+1s (Traffic Suspension)
- **BlueGreenConnectionPlugin** updates routing rules:
  ```
  OLD: connect → BLUE_WRITER
  NEW: connect → SUSPENDED (wait)
  ```
- New connection attempts are **queued** (not rejected)
- Existing connections continue to drain

#### Time: T+1-3s (AWS Internal Operations)
- AWS promotes Green cluster to be the new writer
- DNS records are updated (endpoint now points to Green)
- Blue cluster transitions to read-only
- Replication lag verification

#### Time: T+3s (Routing Update)
- `BlueGreenStatusMonitor` detects phase change to `POST` or `COMPLETED`
- Plugin updates routing rules:
  ```
  connect → GREEN_WRITER (new primary)
  ```
- Queued connections are released and routed to Green cluster

#### Time: T+4s (Resume Normal Operations)
- All application connections now target Green cluster
- Polling frequency returns to baseline (5 seconds)
- Phase marked as `COMPLETED`

### Phase 4: Post-Switchover

**Green Cluster is Now Primary**:
- All write traffic flows to Green cluster (running version 3.10)
- Old Blue cluster can be deleted or retained as a rollback option
- Plugin continues monitoring in case of additional Blue-Green operations

**Cleanup (Optional)**:
```bash
aws rds delete-blue-green-deployment \
  --blue-green-deployment-identifier bgd-12345 \
  --delete-target false  # Keep the old Blue cluster for rollback
```

---

## Deployment Phases

The Blue-Green plugin tracks six distinct phases throughout the deployment lifecycle:

| Phase ID | Phase Name | Description | Cluster Accessibility | Plugin Behavior |
|----------|-----------|-------------|----------------------|-----------------|
| 0 | `NOT_CREATED` | Initial state, no deployment exists | Blue: ✅ Accessible<br>Green: ❌ Not exists | Normal routing to Blue |
| 1 | `CREATED` | Green cluster created, not yet active | Blue: ✅ Accessible<br>Green: ⚠️ Exists but isolated | Continue routing to Blue |
| 2 | `PREPARATION` | Final sync before switchover | Blue: ✅ Accessible<br>Green: ⚠️ Syncing data | Route to Blue, prepare for transition |
| 3 | `IN_PROGRESS` | **Active switchover happening** | Blue: ❌ Becoming read-only<br>Green: ❌ Becoming primary | **Suspend all connections**<br>Critical 3-5 second window |
| 4 | `POST` | Switchover completed, finalizing changes | Blue: ✅ (Read-only)<br>Green: ✅ (New writer) | Route all traffic to Green |
| 5 | `COMPLETED` | All changes finalized | Blue: ✅ (Standby/delete pending)<br>Green: ✅ (Primary) | Normal routing to Green |

### Phase Detection Logic

**Aurora MySQL**:
```sql
SELECT
  CASE
    WHEN topology_json IS NULL THEN 0  -- NOT_CREATED
    WHEN JSON_EXTRACT(topology_json, '$.phase') = 'CREATED' THEN 1
    WHEN JSON_EXTRACT(topology_json, '$.phase') = 'PREPARATION' THEN 2
    WHEN JSON_EXTRACT(topology_json, '$.phase') = 'IN_PROGRESS' THEN 3
    WHEN JSON_EXTRACT(topology_json, '$.phase') = 'POST' THEN 4
    WHEN JSON_EXTRACT(topology_json, '$.phase') = 'COMPLETED' THEN 5
  END AS current_phase
FROM mysql.ro_replica_status
WHERE deployment_type = 'BLUE_GREEN';
```

**RDS PostgreSQL**:
```sql
SELECT current_phase
FROM rds_tools.bluegreen_deployment_status
WHERE deployment_id = 'bgd-12345';
```

---

## Connection Handling During Switchover

### Without Blue-Green Plugin (Baseline Behavior)

```
Application Thread 1: INSERT INTO orders ... ──X──> [11-20s timeout]
Application Thread 2: INSERT INTO orders ... ──X──> [11-20s timeout]
Application Thread 3: INSERT INTO orders ... ──X──> [11-20s timeout]
                                                │
                                                ├─ DNS Cache Staleness
                                                ├─ Connection Pool Drain Time
                                                ├─ TCP Timeout
                                                └─ Application Retry Logic

Result:
  • 11-20 seconds of failed transactions
  • Hundreds to thousands of errors (depending on TPS)
  • Manual intervention may be required
```

### With Blue-Green Plugin (Optimized Behavior)

```
Application Thread 1: INSERT INTO orders ... ──⏸──> [3-5s queue] ──✓──> Success
Application Thread 2: INSERT INTO orders ... ──⏸──> [3-5s queue] ──✓──> Success
Application Thread 3: INSERT INTO orders ... ──⏸──> [3-5s queue] ──✓──> Success
                                                │
                                                ├─ Proactive phase detection
                                                ├─ Coordinated connection suspension
                                                ├─ Intelligent routing to Green
                                                └─ Automatic reconnection

Result:
  • 3-5 seconds of queued operations
  • ~3-20 failed transactions (depending on TPS)
  • Automatic recovery, no manual intervention
```

### Connection Routing Decision Tree

```
┌─────────────────────────────────────────┐
│   New Connection Request Received       │
└───────────────┬─────────────────────────┘
                │
                ▼
┌─────────────────────────────────────────┐
│   Query BlueGreenStatus                 │
│   Current Phase?                        │
└───────┬─────────────────────────────────┘
        │
        ├─── Phase = NOT_CREATED / CREATED / PREPARATION
        │    └─▶ Route to: BLUE_WRITER (current cluster)
        │        Routing Rule: WRITER → WRITER
        │
        ├─── Phase = IN_PROGRESS
        │    └─▶ Action: SUSPEND CONNECTION
        │        • Queue connection request
        │        • Wait for phase transition
        │        • Poll every 1 second
        │        • Timeout: bgConnectTimeoutMs (default: 30s)
        │
        ├─── Phase = POST / COMPLETED
        │    └─▶ Route to: GREEN_WRITER (new primary)
        │        Routing Rule: WRITER → GREEN_WRITER
        │
        └─── Timeout Exceeded
             └─▶ Fallback: Attempt direct connection
                 Log error and notify application
```

### Connection Pool Behavior

**HikariCP Integration Example**:
```java
HikariConfig config = new HikariConfig();
config.setJdbcUrl("jdbc:aws-wrapper:mysql://cluster-endpoint:3306/lab_db");
config.addDataSourceProperty("wrapperPlugins", "bg,failover,efm");
config.addDataSourceProperty("bgdId", "bgd-12345");
config.addDataSourceProperty("bgConnectTimeoutMs", "30000");
config.setMaximumPoolSize(100);  // 10 connections per worker (10 workers)
config.setMinimumIdle(10);
config.setConnectionTimeout(30000);
config.setIdleTimeout(600000);
config.setMaxLifetime(1800000);

HikariDataSource dataSource = new HikariDataSource(config);
```

**During Switchover**:
1. **Existing connections** (in-flight transactions):
   - Continue on Blue cluster until transaction completes
   - May fail if switchover occurs mid-transaction
   - Plugin retries on Green cluster automatically

2. **Idle connections** (in connection pool):
   - Marked as stale by plugin
   - Closed gracefully during `IN_PROGRESS` phase
   - New connections established to Green post-switchover

3. **New connection requests**:
   - Queued during `IN_PROGRESS` phase
   - Routed to Green cluster once `POST` phase detected

---

## Configuration Guide

### JDBC URL Format

**Basic Format**:
```
jdbc:aws-wrapper:<protocol>://<endpoint>:<port>/<database>?<parameters>
```

**Example for Aurora MySQL with Blue-Green Plugin**:
```
jdbc:aws-wrapper:mysql://my-cluster.cluster-xyz.us-east-1.rds.amazonaws.com:3306/lab_db?wrapperPlugins=bg,failover,efm&bgdId=bgd-12345&bgConnectTimeoutMs=30000
```

**Example for RDS PostgreSQL**:
```
jdbc:aws-wrapper:postgresql://my-db.xyz.us-east-1.rds.amazonaws.com:5432/postgres?wrapperPlugins=bg,iam&bgdId=bgd-67890&bgBaselineMs=5000
```

### Configuration Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `wrapperPlugins` | String | `auroraConnectionTracker,failover2,efm2` | Comma-separated list of plugins to enable.<br>Add `bg` for Blue-Green support. |
| `bgdId` | String | (auto-detected) | Blue-Green deployment identifier.<br>Required if multiple deployments exist. |
| `bgConnectTimeoutMs` | Integer | `30000` (30s) | Maximum time to wait for connection during switchover.<br>If exceeded, plugin attempts fallback. |
| `bgBaselineMs` | Integer | `5000` (5s) | Baseline polling interval for deployment status.<br>Lower values = more frequent checks = higher overhead. |
| `bgSwitchoverTimeoutMs` | Integer | `300000` (5m) | Maximum duration for entire switchover process.<br>If exceeded, plugin logs error and continues. |

### Plugin Initialization Order

**Recommended Order**:
```
wrapperPlugins=bg,failover,efm,iam
```

**Rationale**:
1. **`bg` (Blue-Green)**: Must be first to intercept and route connections correctly
2. **`failover`**: Handles cluster-level failover scenarios
3. **`efm` (Enhanced Failure Monitoring)**: Proactive connection health checks
4. **`iam`**: IAM database authentication (if required)

### Database User Permissions

The Blue-Green plugin requires read access to specific metadata tables:

#### Aurora MySQL
```sql
-- No additional permissions required
-- Plugin uses mysql.ro_replica_status (accessible to all authenticated users)
```

#### RDS PostgreSQL
```sql
-- Grant access to rds_tools schema
GRANT USAGE ON SCHEMA rds_tools TO your_application_user;
GRANT EXECUTE ON ALL FUNCTIONS IN SCHEMA rds_tools TO your_application_user;
GRANT SELECT ON ALL TABLES IN SCHEMA rds_tools TO your_application_user;

-- Verify permissions
SELECT schemaname, tablename
FROM pg_tables
WHERE schemaname = 'rds_tools';
```

#### RDS MySQL
```sql
-- Grant access to rds_tools schema
GRANT SELECT ON rds_tools.* TO 'your_application_user'@'%';
FLUSH PRIVILEGES;
```

---

## Best Practices

### 1. Enable Blue-Green Plugin Before Creating Deployment

**Recommended Workflow**:
```bash
# Step 1: Update application configuration to include Blue-Green plugin
# Update JDBC URL: wrapperPlugins=bg,failover,efm

# Step 2: Deploy updated application (rolling restart)

# Step 3: Wait 5-10 minutes for status monitoring to initialize
# Plugin needs time to query metadata and establish baseline

# Step 4: Create Blue-Green deployment
aws rds create-blue-green-deployment \
  --blue-green-deployment-name my-upgrade \
  --source-arn arn:aws:rds:region:account:cluster:my-cluster \
  --target-engine-version 8.0.mysql_aurora.3.10.0

# Step 5: Wait for Green cluster to be ready (15-60 minutes)

# Step 6: Trigger switchover
aws rds switchover-blue-green-deployment \
  --blue-green-deployment-identifier bgd-12345
```

**Why This Order Matters**:
- Plugin must be active and monitoring **before** switchover
- Allows plugin to detect deployment creation and prepare routing tables
- Eliminates "cold start" scenarios during critical switchover window

### 2. Tune Connection Timeouts

**For High-Throughput Applications (1000+ TPS)**:
```properties
# Aggressive timeouts to minimize backpressure
bgConnectTimeoutMs=15000          # 15 seconds
bgBaselineMs=3000                 # Poll every 3 seconds
connectionTimeout=10000           # HikariCP connection timeout
```

**For Low-Latency Applications**:
```properties
# Conservative timeouts to avoid premature failures
bgConnectTimeoutMs=60000          # 60 seconds
bgBaselineMs=5000                 # Poll every 5 seconds
connectionTimeout=30000           # HikariCP connection timeout
```

### 3. Monitor Plugin Behavior

**Enable Debug Logging** (Java application):
```xml
<!-- log4j2.xml -->
<Loggers>
  <Logger name="software.amazon.jdbc.plugin.bluegreen" level="DEBUG" additivity="false">
    <AppenderRef ref="Console"/>
  </Logger>
</Loggers>
```

**Key Metrics to Track**:
- Connection establishment time (p50, p95, p99)
- Failed connection attempts during switchover
- Blue-Green phase transitions (log timestamps)
- Number of connections suspended/queued

**Example Log Output**:
```
2025-01-18 10:16:45.123 DEBUG BlueGreenStatusMonitor - Detected phase transition: PREPARATION → IN_PROGRESS
2025-01-18 10:16:45.124 INFO  BlueGreenConnectionPlugin - Suspending new connections (deployment: bgd-12345)
2025-01-18 10:16:48.456 DEBUG BlueGreenStatusMonitor - Detected phase transition: IN_PROGRESS → POST
2025-01-18 10:16:48.457 INFO  BlueGreenConnectionPlugin - Routing connections to GREEN_WRITER (ip-10-0-2-78)
2025-01-18 10:16:48.500 INFO  BlueGreenConnectionPlugin - Switchover completed in 3.4 seconds
```

### 4. Test Blue-Green Switchover in Non-Production

**Recommended Testing Approach**:
1. Deploy test Aurora cluster with Blue-Green plugin enabled
2. Run workload simulator with 10-50 write workers
3. Create Blue-Green deployment and wait for Green cluster readiness
4. Trigger switchover while workload simulator is actively writing
5. Measure:
   - Total downtime (should be 3-5 seconds)
   - Failed transactions (should be < 20 for 100 TPS workload)
   - Recovery time (should be immediate)

**Success Criteria**:
- ✅ Application experiences < 5 seconds of unavailability
- ✅ No manual intervention required
- ✅ All failed transactions are automatically retried
- ✅ No connection pool exhaustion or deadlocks

### 5. Plan for Rollback Scenarios

**If Switchover Fails**:
```bash
# Option 1: Abort the switchover (if not yet triggered)
aws rds delete-blue-green-deployment \
  --blue-green-deployment-identifier bgd-12345 \
  --delete-target true

# Option 2: Switchover back to Blue cluster (if switchover completed)
# Create a new Blue-Green deployment with Green as source
aws rds create-blue-green-deployment \
  --blue-green-deployment-name rollback-deployment \
  --source-arn arn:aws:rds:region:account:cluster:my-cluster-green \
  --target-engine-version <original-version>
```

**Plugin Behavior During Rollback**:
- Plugin detects new Blue-Green deployment
- Routing automatically adjusts to the current writer
- No application code changes required

### 6. Disable Plugin After Upgrade (Optional)

**Post-Upgrade Configuration**:
```properties
# Remove 'bg' plugin after successful upgrade
# Old: wrapperPlugins=bg,failover,efm
# New: wrapperPlugins=failover,efm
```

**Benefits**:
- Reduces background polling overhead
- Simplifies connection logic for steady-state operations

**When to Keep Enabled**:
- Frequent Blue-Green upgrades (quarterly, monthly)
- Multiple clusters with staggered upgrade schedules
- Automated upgrade pipelines

---

## Technical Deep Dive

### Source Code Analysis

#### Connection Routing Algorithm

**File**: `BlueGreenConnectionPlugin.java`

**Method**: `connectInternal()`

**Pseudocode**:
```java
public Connection connectInternal(String url, Properties props) {
  // Step 1: Retrieve current deployment status
  BlueGreenStatus status = storageService.getStatus(bgdId);

  // Step 2: Determine target role based on current phase
  BlueGreenRole targetRole = determineTargetRole(status, requestedRole);

  // Step 3: Apply connection routing rules
  List<RoutingRule> rules = status.getConnectRouting();
  for (RoutingRule rule : rules) {
    if (rule.getSourceRole() == requestedRole) {
      targetRole = rule.getTargetRole();
      break;
    }
  }

  // Step 4: Handle IN_PROGRESS phase (suspend connections)
  if (status.getCurrentPhase() == BlueGreenPhase.IN_PROGRESS) {
    return suspendAndWait(bgConnectTimeoutMs);
  }

  // Step 5: Attempt connection to target host
  String targetHost = resolveHost(targetRole, status);
  try {
    Connection conn = driverManager.connect(targetHost, props);
    logConnectionSuccess(targetRole, targetHost);
    return conn;
  } catch (SQLException e) {
    // Step 6: Fallback logic
    return fallbackConnection(url, props, e);
  }
}

private Connection suspendAndWait(long timeoutMs) {
  long startTime = System.nanoTime();
  while (System.nanoTime() - startTime < timeoutMs * 1_000_000) {
    // Poll for phase change
    BlueGreenStatus currentStatus = storageService.getStatus(bgdId);
    if (currentStatus.getCurrentPhase() != BlueGreenPhase.IN_PROGRESS) {
      // Switchover completed, retry connection
      return connectInternal(url, props);
    }
    Thread.sleep(1000);  // Wait 1 second before next poll
  }
  throw new SQLTimeoutException("Blue-Green switchover timeout exceeded");
}
```

#### Status Monitoring Loop

**File**: `BlueGreenStatusMonitor.java`

**Method**: `run()` (executed by ExecutorService)

**Pseudocode**:
```java
public void run() {
  while (!Thread.interrupted()) {
    try {
      // Step 1: Query deployment status from metadata table
      BlueGreenPhase currentPhase = queryDeploymentPhase(bgdId);

      // Step 2: Detect phase transition
      if (currentPhase != previousPhase) {
        logPhaseTransition(previousPhase, currentPhase);
        triggerCallbacks(currentPhase);
        previousPhase = currentPhase;
      }

      // Step 3: Update cluster topology if needed
      if (currentPhase == BlueGreenPhase.POST ||
          currentPhase == BlueGreenPhase.COMPLETED) {
        refreshTopology();  // Update host-to-role mappings
        refreshIpAddresses();  // Resolve new DNS entries
      }

      // Step 4: Adjust polling frequency based on phase
      long sleepTime = (currentPhase == BlueGreenPhase.IN_PROGRESS)
          ? 1000   // High-frequency: 1 second
          : bgBaselineMs;  // Baseline: 5 seconds (default)

      Thread.sleep(sleepTime);

    } catch (SQLException e) {
      logError("Failed to query Blue-Green status", e);
      Thread.sleep(bgBaselineMs);  // Backoff on error
    }
  }
}

private BlueGreenPhase queryDeploymentPhase(String bgdId) {
  // Aurora MySQL example
  String sql = """
    SELECT JSON_EXTRACT(topology_json, '$.phase') AS current_phase
    FROM mysql.ro_replica_status
    WHERE deployment_type = 'BLUE_GREEN'
      AND deployment_identifier = ?
    """;

  try (PreparedStatement stmt = connection.prepareStatement(sql)) {
    stmt.setString(1, bgdId);
    ResultSet rs = stmt.executeQuery();
    if (rs.next()) {
      String phaseStr = rs.getString("current_phase");
      return BlueGreenPhase.valueOf(phaseStr);
    }
  }
  return BlueGreenPhase.NOT_CREATED;
}
```

### Performance Characteristics

#### Overhead Analysis

**Baseline (No Active Deployment)**:
- Polling overhead: ~5ms query every 5 seconds
- Impact on application: Negligible (< 0.001% CPU)
- Network traffic: ~100 bytes/query = 20 bytes/second

**During Switchover (IN_PROGRESS Phase)**:
- Polling overhead: ~5ms query every 1 second
- Queued connections: Hold application threads (blocking)
- Typical queue time: 3-5 seconds
- Maximum concurrent queued threads: Equal to connection pool size

**Post-Switchover**:
- DNS resolution: Cached by JVM (no additional lookups)
- Connection establishment: Standard TCP handshake + SSL (if enabled)
- No measurable performance degradation vs. non-plugin connections

#### Downtime Comparison

| Scenario | Without Plugin | With Blue-Green Plugin | Improvement |
|----------|---------------|------------------------|-------------|
| Connection establishment | 11-20 seconds | 3-5 seconds | **73% reduction** |
| Failed transactions (100 TPS) | 1100-2000 | 300-500 | **75% reduction** |
| Failed transactions (1000 TPS) | 11000-20000 | 3000-5000 | **75% reduction** |
| Manual intervention | Required | Not required | **100% automation** |

### Error Handling and Retry Logic

**Retry Scenarios**:

1. **Connection Failure During IN_PROGRESS Phase**:
   ```
   Attempt 1: Connect to Blue (FAIL - cluster read-only)
   Wait: 1 second
   Attempt 2: Suspend and queue (phase still IN_PROGRESS)
   Wait: 1 second
   Attempt 3: Connect to Green (SUCCESS - phase transitioned to POST)
   ```

2. **Timeout Exceeded**:
   ```
   Attempt 1-30: Suspend and poll every 1 second (30 seconds total)
   Timeout: bgConnectTimeoutMs exceeded
   Fallback: Attempt direct connection (may fail)
   Error: Throw SQLTimeoutException to application
   ```

3. **Metadata Query Failure**:
   ```
   Attempt 1: Query mysql.ro_replica_status (FAIL - permission denied)
   Fallback: Assume phase = NOT_CREATED
   Behavior: Route to current writer endpoint (Blue)
   Log: Warn about metadata access failure
   ```

---

## Conclusion

The AWS Advanced JDBC Wrapper's Blue-Green Deployment Plugin provides a sophisticated, production-ready solution for minimizing downtime during Aurora and RDS database upgrades. By leveraging proactive deployment monitoring, intelligent connection routing, and coordinated switchover handling, the plugin reduces typical downtime from **11-20 seconds to just 3-5 seconds**.

### Key Takeaways

✅ **Minimal Downtime**: 73% reduction in connection unavailability
✅ **Automatic Failover**: No manual intervention required
✅ **Production-Tested**: Used by AWS customers for zero-downtime upgrades
✅ **Easy Integration**: Simple JDBC URL configuration
✅ **Comprehensive Monitoring**: Real-time deployment phase tracking

### Further Reading

- [AWS Advanced JDBC Wrapper GitHub Repository](https://github.com/aws/aws-advanced-jdbc-wrapper)
- [Aurora Blue-Green Deployments Documentation](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/blue-green-deployments.html)
- [Amazon Aurora MySQL Release Notes](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraMySQLReleaseNotes/)
- [Blue-Green Plugin Official Documentation](https://github.com/aws/aws-advanced-jdbc-wrapper/blob/main/docs/using-the-jdbc-driver/using-plugins/UsingTheBlueGreenPlugin.md)

---

**Document Version**: 1.0
**Last Updated**: 2025-01-18
**Author**: Aurora Blue-Green Deployment Lab Project
**License**: MIT

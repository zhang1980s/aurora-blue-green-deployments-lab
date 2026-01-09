# Aurora Blue-Green Workload Simulator

A Java-based workload simulator designed to test Aurora Blue-Green deployment scenarios with realistic write and read workloads.

## Features

- **Mixed Workload Support**: Simulates both write and read operations against Aurora MySQL writer instance
- **AWS JDBC Wrapper Integration**: Uses AWS Advanced JDBC Wrapper with Blue-Green plugin support
- **Automatic Failover Handling**: Built-in connection retry logic with exponential backoff
- **Real-Time Monitoring**: Console output with success/failure indicators and detailed statistics
- **Host Distribution Tracking**: Monitors which Aurora nodes handle read queries during Blue-Green switchover
- **Prometheus Metrics**: Optional metrics export for advanced monitoring (EKS deployments)
- **Flexible Deployment**: Can run on EC2 or Kubernetes (EKS)

## Prerequisites

- Java 17 (Amazon Corretto recommended)
- Maven 3.9+
- MySQL client CLI tool
- Access to Aurora MySQL cluster
- Database credentials with write permissions

## Database Schema Initialization

Before running the workload simulator, you need to initialize the database with 12,000 test tables. This simulates production-scale metadata overhead.

### Using init-schema.sh

The `init-schema.sh` script automates the creation of 12,000 tables with parallel execution for optimal performance.

**Basic Usage:**
```bash
./init-schema.sh \
  --endpoint my-cluster.cluster-xxxxx.us-east-1.rds.amazonaws.com \
  --password MySecretPassword
```

**High-Performance Initialization (8 parallel workers):**
```bash
./init-schema.sh \
  --endpoint my-cluster.cluster-xxxxx.us-east-1.rds.amazonaws.com \
  --password MySecretPassword \
  --parallel 8
```

**Custom Table Count:**
```bash
./init-schema.sh \
  --endpoint my-cluster.cluster-xxxxx.us-east-1.rds.amazonaws.com \
  --password MySecretPassword \
  --tables 5000
```

**Using Environment Variable for Password:**
```bash
export DB_PASSWORD="MySecretPassword"
./init-schema.sh \
  --endpoint my-cluster.cluster-xxxxx.us-east-1.rds.amazonaws.com
```

### Script Parameters

| Parameter | Description | Default |
|-----------|-------------|---------|
| `--endpoint` | Aurora cluster writer endpoint | (required) |
| `--password` | Database password | (required or use DB_PASSWORD env var) |
| `--database` | Database name | `lab_db` |
| `--username` | Database username | `admin` |
| `--tables` | Number of tables to create | `12000` |
| `--parallel` | Number of parallel workers | `4` |
| `--log-file` | Log file path | `schema-init.log` |
| `--help` | Show help message | - |

### Expected Duration

- **12,000 tables with 4 workers**: 30-60 minutes
- **12,000 tables with 8 workers**: 15-30 minutes
- **5,000 tables with 4 workers**: 10-20 minutes

### Table Structure

Each table is created with the following structure:

```sql
CREATE TABLE test_XXXX (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    col1 VARCHAR(255),
    col2 INT,
    col3 VARCHAR(255),
    col4 BIGINT,
    col5 TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_col2 (col2),
    INDEX idx_col4 (col4)
) ENGINE=InnoDB;
```

Each table is initialized with 1 row of baseline data.

### Verification

After completion, verify the table count:

```bash
mysql -h <endpoint> -u admin -p<password> -e \
  "SELECT COUNT(*) FROM information_schema.tables
   WHERE table_schema='lab_db' AND table_name LIKE 'test_%'"
```

Expected result: 12000 (or your specified --tables count)

## Building the Application

### Using build.sh (Recommended)

The `build.sh` script provides convenient commands for building and managing the Maven project:

```bash
# Build the project (clean + package)
./build.sh build

# Clean build artifacts
./build.sh clean

# Quick compile without tests
./build.sh compile

# Verify project structure and dependencies
./build.sh verify

# View dependency tree
./build.sh dependency

# Show all available commands
./build.sh help
```

**Available Commands:**
- `build` - Clean and build the project with all dependencies (default)
- `clean` - Remove all build artifacts and target directory
- `package` - Build JAR with all dependencies (skip tests)
- `compile` - Compile source code only
- `test` - Run unit tests
- `verify` - Verify project structure and dependencies
- `install` - Install to local Maven repository
- `dependency` - Display dependency tree

### Manual Maven Build

```bash
cd workload-simulator
mvn clean package
```

This creates `target/workload-simulator.jar` with all dependencies included (~8.5MB).

### Docker Build

```bash
docker build -t workload-simulator:latest .
```

## Running the Simulator

### Option 1: Direct Execution (EC2/Local)

**Basic Configuration:**
```bash
java -jar target/workload-simulator.jar \
  --aurora-endpoint my-cluster.cluster-xxxxx.us-east-1.rds.amazonaws.com \
  --password MySecretPassword \
  --write-workers 10 \
  --write-rate 100
```

**High-Load Configuration:**
```bash
java -jar target/workload-simulator.jar \
  --aurora-endpoint my-cluster.cluster-xxxxx.us-east-1.rds.amazonaws.com \
  --password MySecretPassword \
  --write-workers 50 \
  --write-rate 200 \
  --connection-pool-size 500
```

**Mixed Workload (Write + Read):**
```bash
java -jar target/workload-simulator.jar \
  --aurora-endpoint my-cluster.cluster-xxxxx.us-east-1.rds.amazonaws.com \
  --password MySecretPassword \
  --write-workers 10 \
  --write-rate 50 \
  --read-workers 10 \
  --read-rate 50 \
  --connection-pool-size 200
```

**Read-Only Workload:**
```bash
java -jar target/workload-simulator.jar \
  --aurora-endpoint my-cluster.cluster-xxxxx.us-east-1.rds.amazonaws.com \
  --password MySecretPassword \
  --write-workers 0 \
  --read-workers 20 \
  --read-rate 100 \
  --connection-pool-size 200
```

**Using Environment Variable for Password:**
```bash
export DB_PASSWORD="MySecretPassword"
java -jar target/workload-simulator.jar \
  --aurora-endpoint my-cluster.cluster-xxxxx.us-east-1.rds.amazonaws.com
```

### Option 2: Kubernetes Deployment (EKS)

**Step 1: Update Secret Configuration**
```bash
# Edit kubernetes/secret.yaml and replace placeholders
vim kubernetes/secret.yaml
```

**Step 2: Deploy to Kubernetes**
```bash
# Apply configurations
kubectl apply -f kubernetes/secret.yaml
kubectl apply -f kubernetes/configmap.yaml
kubectl apply -f kubernetes/deployment.yaml

# Verify deployment
kubectl get pods -l app=workload-simulator

# View logs
kubectl logs -f -l app=workload-simulator
```

**Step 3: Scale the Deployment (Optional)**
```bash
# Scale to 5 replicas
kubectl scale deployment workload-simulator --replicas=5

# Or modify HPA settings in deployment.yaml for auto-scaling
```

## Configuration Parameters

| Parameter | Description | Default | Required |
|-----------|-------------|---------|----------|
| `--aurora-endpoint` | Aurora cluster writer endpoint | - | Yes |
| `--database-name` | Database name | `lab_db` | No |
| `--username` | Database username | `admin` | No |
| `--password` | Database password | `DB_PASSWORD` env var | Yes |
| `--write-workers` | Number of concurrent write workers | `10` | No |
| `--write-rate` | Writes per second per worker | `100` | No |
| `--read-workers` | Number of concurrent read workers | `0` | No |
| `--read-rate` | Reads per second per worker | `100` | No |
| `--connection-pool-size` | HikariCP connection pool size | `100` | No |
| `--log-interval` | Statistics log interval (seconds) | `10` | No |
| `--blue-green-deployment-id` | Blue-Green deployment ID | auto-detect | No |
| `--enable-prometheus` | Enable Prometheus metrics | `false` | No |

### Read Workload Details

The read workload feature allows you to simulate read operations alongside write operations to better understand how Aurora Blue-Green deployments affect different types of database traffic.

**Key Characteristics:**
- Read operations execute `SELECT @@hostname, @@server_id, @@aurora_version, @@read_only` to query system variables
- All read operations target the **writer instance** (same endpoint as write operations)
- Tracks which Aurora node handles each read query, including server ID, Aurora version, and read-only status
- Monitors host distribution before, during, and after Blue-Green switchover
- Useful for observing connection behavior, DNS propagation, and version changes during switchover

**Use Cases:**
1. **Mixed Workload Testing**: Simulate realistic production scenarios with both reads and writes
2. **Host Distribution Analysis**: Track which database node handles queries during switchover
3. **Connection Behavior**: Observe how read connections behave differently than write connections during failover
4. **DNS Propagation**: Monitor endpoint resolution changes during Blue-Green switchover

**Important Notes:**
- Set `--write-workers 0` to run read-only workload
- Read workers use the same connection pool as write workers
- Host distribution statistics show which Aurora node processed each query
- Latency metrics help identify performance impact during switchover

## Understanding the Output

### Console Log Format - Write Operations

```
[timestamp] INFO: Workload Simulator Started (Write workers: 10, Read workers: 0)
[timestamp] INFO: Aurora Endpoint: my-cluster.cluster-xxxxx.us-east-1.rds.amazonaws.com
[timestamp] SUCCESS: Worker-1 | Host: ip-10-0-1-45 (writer) | Table: test_0001 | INSERT completed | Latency: 12ms
[timestamp] SUCCESS: Worker-2 | Host: ip-10-0-1-45 (writer) | Table: test_0042 | INSERT completed | Latency: 15ms
```

### Console Log Format - Read Operations

```
[timestamp] SUCCESS: Reader-1 | Result: ip-10-0-1-45.ap-southeast-1.compute.internal (server_id=123, version=3.04.1, read_only=0) | Latency: 8ms
[timestamp] SUCCESS: Reader-2 | Result: ip-10-0-1-45.ap-southeast-1.compute.internal (server_id=123, version=3.04.1, read_only=0) | Latency: 7ms
```

**What the output shows:**
- **hostname**: The Aurora instance hostname (e.g., `ip-10-0-1-45.ap-southeast-1.compute.internal`)
- **server_id**: MySQL server ID (unique per instance)
- **version**: Aurora MySQL version (e.g., `3.04.1` for blue, `3.10.2` for green)
- **read_only**: 0=writer instance, 1=reader instance (should always be 0 for writer endpoint)
- **Latency**: Query execution time in milliseconds

### Statistics Output (Mixed Workload)

```
========================================
WRITE STATS: Total: 5000 | Success: 5000 | Failed: 0 | Success Rate: 100.00%
READ STATS: Total: 5000 | Success: 4998 | Failed: 2 | Success Rate: 99.96% | Avg Latency: 8.3ms
READ HOST DISTRIBUTION:
  ip-10-0-1-45.ap-southeast-1.compute.internal : 4998 queries (99.96%)
========================================
```

### During Blue-Green Switchover

**Write Worker:**
```
[timestamp] ERROR: Worker-5 | Table: test_0123 | connection_lost | Retry 1/5 in 500ms | Error: Communications link failure
[timestamp] INFO: Worker-5 | Switched to new host: ip-10-0-2-78 (writer) (from: ip-10-0-1-45 (writer))
[timestamp] SUCCESS: Worker-5 | Host: ip-10-0-2-78 (writer) | Table: test_0123 | INSERT completed | Latency: 234ms (retry 1)
```

**Read Worker:**
```
[timestamp] WARN: Reader-3 | Query: system_vars | connection_error | Retry 1/5 in 500ms | Error: Communications link failure
[timestamp] INFO: Reader-3 | Switched to new host: ip-10-0-2-78.ap-southeast-1.compute.internal (server_id=456, version=3.10.2, read_only=0) (from: ip-10-0-1-45.ap-southeast-1.compute.internal)
[timestamp] SUCCESS: Reader-3 | Result: ip-10-0-2-78.ap-southeast-1.compute.internal (server_id=456, version=3.10.2, read_only=0) | Latency: 187ms
```

**Key observations during switchover:**
- Version changes from `3.04.1` (blue) to `3.10.2` (green)
- Server ID changes indicating connection to a different Aurora instance
- Hostname changes from old instance to new instance
- `read_only=0` remains constant (both are writer instances)

**Host Distribution After Switchover:**
```
========================================
READ HOST DISTRIBUTION:
  ip-10-0-1-45.ap-southeast-1.compute.internal : 2500 queries (50.0%)
  ip-10-0-2-78.ap-southeast-1.compute.internal : 2500 queries (50.0%)
========================================
```

## JDBC Connection URL Format

The simulator uses the AWS Advanced JDBC Wrapper with the following plugins enabled:

```
jdbc:aws-wrapper:mysql://endpoint:3306/database?wrapperPlugins=initialConnection,bg,failover,efm&bgdId=1&bgConnectTimeoutMs=30000&bgSwitchoverTimeoutMs=180000
```

**Plugins (in order):**
- `initialConnection`: Ensures proper connection initialization and driver setup
- `bg`: Blue-Green deployment plugin for proactive switchover monitoring (3-5 second downtime)
- `failover`: Automatic failover detection and handling
- `efm`: Enhanced Failure Monitoring for connection health

**Note:** Plugin order matters. The `initialConnection` plugin should be first to ensure proper connection setup.

**Blue-Green Plugin Parameters:**
- `bgdId`: Blue-Green deployment ID (default: "1", required when multiple BGDs exist)
- `bgConnectTimeoutMs`: Connection timeout during switchover (default: 30000ms)
- `bgSwitchoverTimeoutMs`: Maximum switchover duration (default: 180000ms)

## Logging Implementation

### Architecture Overview

The workload simulator uses a **simplified URL-only** approach for JDBC wrapper log level control:

```
┌──────────────────────────────────────────────────────────────────────────────┐
│  CLI: --jdbc-log-level FINE                                                  │
│              ↓                                                               │
│  JDBC URL: wrapperLoggerLevel=FINE  ← SINGLE CONTROL POINT                   │
└──────────────────────────────┬───────────────────────────────────────────────┘
                               ↓
┌──────────────────────────────────────────────────────────────────────────────┐
│                         AWS JDBC Wrapper (JUL)                               │
│  [wrapperLoggerLevel=FINE] ← ONLY FILTER (controls what gets emitted)        │
└──────────────────────────────┬───────────────────────────────────────────────┘
                               ↓
┌──────────────────────────────────────────────────────────────────────────────┐
│                        SLF4JBridgeHandler                                    │
│  Forwards ALL logs from JUL to SLF4J (no filtering)                          │
└──────────────────────────────┬───────────────────────────────────────────────┘
                               ↓
┌──────────────────────────────────────────────────────────────────────────────┐
│                        Log4j2 (level="all")                                  │
│  PASS THROUGH - no filtering, only routing to appenders                      │
└──────────────────────────────┬───────────────────────────────────────────────┘
                               ↓
              ┌────────────────┴────────────────┐
              ▼                                 ▼
┌───────────────────────────────┐  ┌───────────────────────────────────────────┐
│        RollingFile            │  │              Console                      │
│  logs/workload-simulator-     │  │  SYSTEM_OUT with colored output           │
│    ${timestamp}.log           │  │                                           │
│  - 100MB per file max         │  │  Example:                                 │
│  - Keeps 10 files             │  │  2025-01-05 10:15:23.456 FINE [main]      │
│  - Gzip compressed            │  │  BlueGreenPlugin - Topology changed       │
└───────────────────────────────┘  └───────────────────────────────────────────┘
```

**Key Point:** Log level is controlled ONLY by `wrapperLoggerLevel` in the JDBC URL. Log4j2 passes through everything.

### JUL to SLF4J Bridge

The AWS JDBC Wrapper uses **Java Util Logging (JUL)** internally, but the workload simulator uses **SLF4J/Log4j2**. The bridge connects them:

```java
// In WorkloadSimulator.main()
LogManager.getLogManager().reset();
SLF4JBridgeHandler.removeHandlersForRootLogger();
SLF4JBridgeHandler.install();
```

This redirects all JUL logs from `software.amazon.jdbc.*` to SLF4J, which then routes them to Log4j2.

### Log4j2 Configuration

The `log4j2.xml` configuration provides:

**1. Console Appender** - Real-time colored output to stdout:
```xml
<Console name="Console" target="SYSTEM_OUT">
    <PatternLayout pattern="%d{yyyy-MM-dd HH:mm:ss.SSS} %highlight{%-5level} [%t] %c{1} - %msg%n">
        <DisableAnsi>false</DisableAnsi>
    </PatternLayout>
</Console>
```

**2. RollingFile Appender** - Persistent logs with rotation:
```xml
<RollingFile name="RollingFile"
             fileName="logs/workload-simulator-${log.timestamp}.log"
             filePattern="logs/workload-simulator-${log.timestamp}-%d{yyyy-MM-dd}-%i.log.gz">
    <Policies>
        <TimeBasedTriggeringPolicy interval="1" modulate="true"/>
        <SizeBasedTriggeringPolicy size="100 MB"/>
    </Policies>
    <DefaultRolloverStrategy max="10"/>
</RollingFile>
```

### JDBC Wrapper Log Level Control

The JDBC wrapper log level is controlled via the `--jdbc-log-level` CLI argument, which sets the `wrapperLoggerLevel` parameter in the JDBC URL.

**Usage:**
```bash
java -jar workload-simulator.jar \
  --aurora-endpoint my-cluster.xxxxx.us-east-1.rds.amazonaws.com \
  --jdbc-log-level FINE \
  --password MySecret
```

### JUL Log Level Reference

The `--jdbc-log-level` argument accepts standard Java Util Logging (JUL) levels:

| Level | Verbosity | When to Use |
|-------|-----------|-------------|
| `SEVERE` | Lowest | Only errors and critical failures |
| `WARNING` | Low | Warnings and errors |
| `INFO` | Normal | Normal operational messages (default) |
| `CONFIG` | Medium | Configuration information |
| `FINE` | High | Debug-level diagnostic information |
| `FINER` | Higher | More detailed tracing |
| `FINEST` | Highest | Maximum verbosity - all internal operations |
| `OFF` | None | Disable all JDBC wrapper logging |
| `ALL` | Everything | Enable all log levels |

**Examples:**
```bash
# Default (INFO level)
java -jar workload-simulator.jar --aurora-endpoint ... --password ...

# Debug Blue-Green switchover issues
java -jar workload-simulator.jar --aurora-endpoint ... --jdbc-log-level FINE --password ...

# Maximum verbosity for troubleshooting
java -jar workload-simulator.jar --aurora-endpoint ... --jdbc-log-level FINEST --password ...

# Quiet mode (errors only)
java -jar workload-simulator.jar --aurora-endpoint ... --jdbc-log-level SEVERE --password ...
```

### Sample Log Output

**Normal Operation (INFO level):**
```
2025-01-05 10:15:23.456 INFO  [main] WorkloadSimulator - Connection pool initialized with 100 max connections
2025-01-05 10:15:24.123 INFO  [Worker-1] WorkloadSimulator - SUCCESS: Worker-1 | Host: ip-10-0-1-45 | Table: test_0001 | INSERT completed | Latency: 12ms
```

**Blue-Green Switchover (FINE level):**
```
2025-01-05 10:15:23.456 FINE  [HikariPool-1] BlueGreenPlugin - Detected topology change
2025-01-05 10:15:23.457 FINE  [HikariPool-1] BlueGreenPlugin - New writer endpoint: ip-10-0-2-78
2025-01-05 10:15:23.458 INFO  [Worker-5] WorkloadSimulator - Switched to new host: ip-10-0-2-78 (from: ip-10-0-1-45)
```

**Troubleshooting with FINEST level:**
```bash
# Maximum verbosity for debugging Blue-Green issues
java -jar workload-simulator.jar \
     --aurora-endpoint my-cluster.xxxxx.us-east-1.rds.amazonaws.com \
     --jdbc-log-level FINEST \
     --console-format verbose \
     --password MySecret
```

### Log File Location

Logs are written to the `logs/` directory relative to where the JAR is executed:

```
logs/
├── workload-simulator-2025-01-05-101523.log      # Current log file
├── workload-simulator-2025-01-05-101523-2025-01-05-1.log.gz  # Rolled (compressed)
└── workload-simulator-2025-01-05-101523-2025-01-05-2.log.gz  # Rolled (compressed)
```

### How It Works

The workload simulator uses the AWS JDBC Wrapper's native `wrapperLoggerLevel` URL parameter for log level control:

```
CLI: --jdbc-log-level FINE
        ↓
JDBC URL: jdbc:aws-wrapper:mysql://...?wrapperLoggerLevel=FINE&...
        ↓
AWS JDBC Wrapper filters logs at source (JUL)
        ↓
SLF4JBridgeHandler forwards to SLF4J (no filtering)
        ↓
Log4j2 passes through to Console + RollingFile (no filtering)
```

**Why URL-based control?**

- **Single control point** - No confusion about which filter is active
- **Simple** - One CLI argument controls everything
- **Predictable** - Log level set at JDBC wrapper source
- **Standard** - Uses AWS JDBC Wrapper's native parameter

## Performance Tuning

### Connection Pool Sizing

**Recommended Formula:**
```
Connection Pool Size = (Write Workers + Read Workers) × 10
```

**Examples:**
- 10 write workers → 100 connections
- 50 write workers → 500 connections
- 10 write + 10 read workers → 200 connections
- 20 write + 20 read workers → 400 connections

### JVM Options (Containerized Deployments)

```bash
JAVA_OPTS="-XX:+UseContainerSupport \
           -XX:MaxRAMPercentage=75.0 \
           -XX:+UseG1GC \
           -XX:+ExitOnOutOfMemoryError"
```

### Resource Allocation (Kubernetes)

**Per Pod:**
- **10 workers**: 2 vCPU, 4GB RAM
- **50 workers**: 4 vCPU, 8GB RAM
- **100 workers**: 8 vCPU, 16GB RAM

## Testing Blue-Green Deployments

### Step-by-Step Workflow

1. **Start the workload simulator** with desired configuration
2. **Verify workload is running** - Check console output for successful writes
3. **Create Blue-Green deployment** via AWS CLI or Console
4. **Keep simulator running** - DO NOT stop during the upgrade
5. **Observe console output** during switchover
6. **Trigger switchover** when ready
7. **Monitor logs** for connection errors and recovery time
8. **Validate results** - Check success rate and total failures

### Expected Behavior

**With Blue-Green Plugin:**
- Downtime: 3-5 seconds
- Failed transactions: ~3-20 writes (depending on TPS)
- Automatic reconnection: Yes
- Data loss: None

**Without Blue-Green Plugin:**
- Downtime: 11-20 seconds
- Failed transactions: Higher (depending on TPS)
- Manual reconnection required
- Data loss: None (Aurora guarantees durability)

## Troubleshooting

### Connection Failures

**Symptom:** Unable to establish initial connection

**Solution:**
1. Verify Aurora endpoint is correct
2. Check security group allows inbound from your IP/subnet
3. Verify credentials are correct
4. Ensure database exists

### High Failure Rate

**Symptom:** Many failed write operations

**Solution:**
1. Reduce write rate: `--write-rate 50`
2. Reduce workers: `--write-workers 5`
3. Increase connection pool: `--connection-pool-size 200`
4. Check Aurora cluster performance metrics

### Out of Memory Errors

**Symptom:** JVM crashes with OOM error

**Solution:**
1. Reduce workers or connection pool size
2. Increase heap size: `JAVA_OPTS="-Xmx4g"`
3. Use G1GC: `JAVA_OPTS="-XX:+UseG1GC"`

## Advanced Monitoring (Kubernetes Only)

### Prometheus Metrics

When `--enable-prometheus` is set, the following metrics are exported on port 9090:

- `workload_writes_success_total`: Total successful write operations
- `workload_writes_failure_total`: Total failed write operations
- `workload_writes_latency_seconds`: Write operation latency histogram

### Accessing Metrics

```bash
# Port-forward to local machine
kubectl port-forward svc/workload-simulator 9090:9090

# Query metrics
curl http://localhost:9090/metrics
```

### Grafana Dashboard

A pre-configured Grafana dashboard is available in `../monitoring/dashboards/` for visualizing workload metrics during Blue-Green deployments.

## Project Structure

```
workload-simulator/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/aws/aurora/
│   │   │       └── WorkloadSimulator.java    # Main application (700+ lines)
│   │   └── resources/
│   │       └── log4j2.xml                    # Logging configuration (FINE level)
├── kubernetes/
│   ├── deployment.yaml                        # K8s deployment with HPA
│   ├── configmap.yaml                         # Configuration parameters
│   └── secret.yaml                            # Database credentials (template)
├── build.sh                                   # Build and cleanup script
├── init-schema.sh                             # Database schema initialization script
├── Dockerfile                                 # Multi-stage container build
├── pom.xml                                    # Maven dependencies
├── .gitignore                                 # Git ignore rules
└── README.md                                  # This file (comprehensive documentation)
```

## Dependencies

| Dependency | Version | Purpose |
|------------|---------|---------|
| AWS Advanced JDBC Wrapper | 2.6.8 | Blue-Green plugin support |
| MySQL Connector/J | 8.0.33 | MySQL JDBC driver |
| HikariCP | 5.0.1 | Connection pooling |
| SLF4J | 2.0.9 | Logging API |
| Log4j2 | 2.20.0 | Logging implementation |
| Micrometer | 1.11.5 | Metrics (Prometheus) |

## JDBC Configuration Reference

### Plugin Names (IMPORTANT)

**Correct Plugin Names (in order):**
- `initialConnection` - Connection initialization plugin (should be first) ✓
- `bg` - Blue-Green deployment plugin ✓
- `failover` - Failover plugin ✓
- `efm` - Enhanced Failure Monitoring plugin ✓

**❌ INCORRECT (Do Not Use):**
- ~~`blueGreenDeployment`~~ - This is NOT the correct plugin name

**Plugin Order:** The order of plugins matters. `initialConnection` should always be first in the list.

### Complete JDBC URL Examples

**Production Configuration (Recommended):**
```
jdbc:aws-wrapper:mysql://endpoint:3306/db?wrapperPlugins=initialConnection,bg,failover,efm&bgdId=1&bgConnectTimeoutMs=30000&bgSwitchoverTimeoutMs=180000&failoverTimeoutMs=60000&failoverClusterTopologyRefreshRateMs=2000
```

**Minimal Configuration:**
```
jdbc:aws-wrapper:mysql://endpoint:3306/db?wrapperPlugins=initialConnection,bg,failover,efm
```

### Blue-Green Plugin Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `bgdId` | String | "1" | Blue-Green deployment ID. Required when multiple BGDs exist. |
| `bgConnectTimeoutMs` | Integer | 30000 | Maximum waiting time (ms) for establishing new connections during switchover. |
| `bgBaselineMs` | Integer | 60000 | Baseline interval (ms) for checking BGD status. Keep below 900000ms (15 min). |
| `bgIncreasedMs` | Integer | 1000 | Interval (ms) for checking BGD status. Recommended: 500-2000ms. |
| `bgHighMs` | Integer | 100 | High-frequency interval (ms) for checking BGD status. Recommended: 50-500ms. |
| `bgSwitchoverTimeoutMs` | Integer | 180000 | Maximum duration (ms) allowed for switchover completion. |

### Failover Plugin Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `failoverTimeoutMs` | Integer | 60000 | Maximum time (ms) to attempt failover. |
| `failoverClusterTopologyRefreshRateMs` | Integer | 2000 | Cluster topology refresh interval (ms). |

### Configuration in Code

The WorkloadSimulator uses the following JDBC URL construction:

```java
StringBuilder jdbcUrl = new StringBuilder("jdbc:aws-wrapper:mysql://");
jdbcUrl.append(auroraEndpoint).append(":3306/").append(databaseName);
jdbcUrl.append("?wrapperPlugins=initialConnection,bg,failover,efm");

if (blueGreenDeploymentId != null && !blueGreenDeploymentId.isEmpty()) {
    jdbcUrl.append("&bgdId=").append(blueGreenDeploymentId);
}

jdbcUrl.append("&connectTimeout=30000");
jdbcUrl.append("&socketTimeout=30000");
jdbcUrl.append("&failoverTimeoutMs=60000");
jdbcUrl.append("&failoverClusterTopologyRefreshRateMs=2000");
jdbcUrl.append("&bgConnectTimeoutMs=30000");
jdbcUrl.append("&bgSwitchoverTimeoutMs=180000");
```

### Expected Performance

**With Blue-Green Plugin (`bg`):**
- Downtime during switchover: 3-5 seconds
- Failed transactions: ~3-20 writes (depending on TPS)
- Automatic reconnection: Yes
- Connection tracking: Monitors BGD status proactively

**Without Blue-Green Plugin:**
- Downtime during switchover: 11-20 seconds
- Failed transactions: Higher (depending on TPS)
- Reconnection: Relies on failover plugin only

## License

See [LICENSE](../LICENSE) file for details.

## References

- [AWS Advanced JDBC Wrapper - Blue-Green Plugin Documentation](https://github.com/aws/aws-advanced-jdbc-wrapper/blob/main/docs/using-the-jdbc-driver/using-plugins/UsingTheBlueGreenPlugin.md)
- [AWS Advanced JDBC Wrapper - Failover Plugin Documentation](https://github.com/aws/aws-advanced-jdbc-wrapper/blob/main/docs/using-the-jdbc-driver/using-plugins/UsingTheFailoverPlugin.md)
- [Aurora Blue-Green Deployments User Guide](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/blue-green-deployments.html)

## Support

For issues and questions:
1. Check the main project README
2. Review Aurora Blue-Green documentation
3. Check AWS JDBC Wrapper documentation

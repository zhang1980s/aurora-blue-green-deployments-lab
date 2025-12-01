# Aurora Blue-Green Workload Simulator

A Java-based workload simulator designed to test Aurora Blue-Green deployment scenarios with realistic write workloads.

## Features

- **Write-Heavy Workload**: Simulates production-like write operations against Aurora MySQL
- **AWS JDBC Wrapper Integration**: Uses AWS Advanced JDBC Wrapper with Blue-Green plugin support
- **Automatic Failover Handling**: Built-in connection retry logic with exponential backoff
- **Real-Time Monitoring**: Console output with success/failure indicators and statistics
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
| `--connection-pool-size` | HikariCP connection pool size | `100` | No |
| `--log-interval` | Statistics log interval (seconds) | `10` | No |
| `--blue-green-deployment-id` | Blue-Green deployment ID | auto-detect | No |
| `--enable-prometheus` | Enable Prometheus metrics | `false` | No |

## Understanding the Output

### Console Log Format

```
[timestamp] INFO: Workload Simulator Started
[timestamp] INFO: Aurora Endpoint: my-cluster.cluster-xxxxx.us-east-1.rds.amazonaws.com
[timestamp] INFO: Workers: 10, Rate: 100 writes/sec/worker
[timestamp] SUCCESS: Worker-1 | Host: ip-10-0-1-45 (writer) | Table: test_0001 | INSERT completed | Latency: 12ms
[timestamp] SUCCESS: Worker-2 | Host: ip-10-0-1-45 (writer) | Table: test_0042 | INSERT completed | Latency: 15ms
[timestamp] STATS: Total: 1000 | Success: 1000 | Failed: 0 | Success Rate: 100.00%
```

### During Blue-Green Switchover

```
[timestamp] ERROR: Worker-5 | Table: test_0123 | connection_lost | Retry 1/5 in 500ms | Error: Communications link failure
[timestamp] INFO: Worker-5 | Switched to new host: ip-10-0-2-78 (writer) (from: ip-10-0-1-45 (writer))
[timestamp] SUCCESS: Worker-5 | Host: ip-10-0-2-78 (writer) | Table: test_0123 | INSERT completed | Latency: 234ms (retry 1)
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

## Performance Tuning

### Connection Pool Sizing

**Recommended Formula:**
```
Connection Pool Size = Write Workers × 10
```

**Examples:**
- 10 workers → 100 connections
- 50 workers → 500 connections
- 100 workers → 1000 connections

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
| AWS Advanced JDBC Wrapper | 2.6.7 | Blue-Green plugin support |
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

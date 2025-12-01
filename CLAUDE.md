# Aurora Blue-Green Deployment Lab

## Project Overview

This project demonstrates the Blue-Green deployment feature of Amazon Aurora. It provides a complete lab environment to practice the Blue-Green upgrade process with real-world workload simulation and monitoring capabilities.

## Purpose

The lab environment enables hands-on practice with Aurora Blue-Green deployments, allowing users to:
- Experience zero-downtime upgrades in a controlled environment
- Monitor the impact of Blue-Green switchovers on application workloads
- Understand the behavior of write operations during the upgrade process

---

## For Claude Code Agents

### Important Context for AI-Assisted Development

When working on this project, Claude Code should understand the following:

#### Key Project Constraints
- **Do NOT stop the workload simulator** during Blue-Green switchover testing - it must remain running to observe connection behavior
- **12,000 tables are intentional** - This simulates production-scale metadata overhead for testing purposes
- **Schema initialization takes 30-60 minutes** - This is expected, not a bug
- **Blue-Green plugin requires specific JDBC parameters** - The deployment ID and timeout settings are critical

#### Common Commands Reference

```bash
# Build workload simulator
cd workload-simulator && mvn clean package

# Deploy infrastructure (automated)
cd infrastructure && ./deploy.sh

# Deploy infrastructure (manual - step by step)
cd infrastructure/vpc && pulumi up
cd ../aurora && pulumi up
cd ../ec2 && pulumi up

# Initialize database schema
cd scripts && ./init-schema.sh \
  --endpoint <cluster-endpoint> \
  --password <password> \
  --tables 12000

# Run workload simulator
java -jar workload-simulator.jar \
  --aurora-endpoint <endpoint> \
  --write-workers 10 \
  --write-rate 100

# Test Blue-Green deployment
aws rds create-blue-green-deployment \
  --blue-green-deployment-name aurora-upgrade-test \
  --source-arn <cluster-arn> \
  --target-engine-version 8.0.mysql_aurora.3.10.0

# Perform switchover
aws rds switchover-blue-green-deployment \
  --blue-green-deployment-identifier <deployment-id>

# Cleanup
cd infrastructure && ./destroy.sh
```

#### File Structure & Key Files

```
infrastructure/
├── vpc/main.go              # VPC, subnets, security groups, routing
├── aurora/main.go           # Aurora cluster, parameter groups, instances
├── ec2/main.go              # EC2 instance with user data for workload simulator
├── deploy.sh                # Automated deployment orchestration
└── destroy.sh               # Cleanup script

scripts/
└── init-schema.sh           # Creates 12,000 test tables with parallel execution

workload-simulator/
├── src/main/java/com/aws/aurora/WorkloadSimulator.java  # Main application
├── pom.xml                  # Maven dependencies and build config
├── Dockerfile               # Multi-stage container build
└── kubernetes/              # K8s manifests for EKS deployment
    ├── deployment.yaml      # Workload simulator deployment
    ├── secret.yaml          # Database credentials (template)
    └── configmap.yaml       # Application configuration
```

#### Code Style Guidelines

**Pulumi/Go Code:**
- Use descriptive resource names with project name prefix: `fmt.Sprintf("%s-vpc", projectName)`
- Always check errors immediately after operations: `if err != nil { return err }`
- Tag all resources with: `"Name"` and `"Project"` tags
- Export all important resource IDs and endpoints using `ctx.Export()`
- Use Pulumi stack references for cross-stack dependencies
- Store secrets using `cfg.RequireSecret()` instead of plain strings
- Provide sensible defaults with fallbacks: `if x == "" { x = "default-value" }`

**Java Code:**
- Use SLF4J for logging, not `System.out.println()`
- Configure connection pool size as 10× the number of workers
- Always use AWS Advanced JDBC Wrapper URL format: `jdbc:aws-wrapper:mysql://...`
- Handle connection failures with exponential backoff retry logic
- Track and log both success and failure metrics

**Shell Scripts:**
- Use `set -e` for fail-fast behavior (except where background jobs are used)
- Provide colored output for clarity: `echo -e "${GREEN}Success${NC}"`
- Validate all required parameters before execution
- Include `--help` option with usage examples
- Log all major operations with timestamps

#### Testing Strategy

Before committing changes, verify:

```bash
# Validate infrastructure changes (no actual deployment)
cd infrastructure/vpc && pulumi preview
cd ../aurora && pulumi preview
cd ../ec2 && pulumi preview

# Test Java compilation and packaging
cd workload-simulator && mvn clean verify

# Validate shell script syntax
shellcheck scripts/*.sh infrastructure/*.sh

# Check for accidentally committed secrets
git secrets --scan || git diff --cached
```

#### Repository Etiquette

- **Never commit**: `*.log`, `Pulumi.*.yaml` (except `*.example.yaml`), `.env` files
- **Always include**: Tags on all infrastructure resources
- **Commit message format**: Use Conventional Commits (e.g., `feat:`, `fix:`, `docs:`)
- **Pull request requirements**: Updated documentation, passing builds, no secrets

#### Known Issues & Workarounds

1. **Schema initialization timeout on slow networks**
   - Increase `--parallel` parameter to 8 (from default 4)
   - Or run in batches: `--tables 5000` multiple times

2. **Pulumi stack reference format**
   - Must be full path: `organization/project-name/stack`
   - Example: `myorg/aurora-bluegreen-vpc/dev`
   - Verify with: `pulumi stack ls`

3. **Aurora security group connectivity**
   - Must allow inbound from both EC2 (10.0.10.0/24) and EKS (10.0.20.0/24, 10.0.21.0/24)
   - Verify with: `aws ec2 describe-security-groups --group-ids <sg-id>`

4. **Blue-Green plugin JDBC parameters**
   - Requires deployment ID parameter: `blueGreenDeploymentId=1`
   - Requires connection timeout: `connectTimeout=30000`
   - Example: `jdbc:aws-wrapper:mysql://endpoint:3306/db?blueGreenDeploymentId=1&connectTimeout=30000`

5. **Maven build in containers**
   - Use `mvn dependency:go-offline` in separate layer for Docker caching
   - Multi-stage builds reduce final image size by ~400MB

#### Testing Blue-Green Deployment Workflow

**Critical sequence (do not deviate):**

1. **Deploy infrastructure** → Wait for Aurora cluster ready (~15 min)
2. **Initialize schema** → Wait for 12,000 tables created (~45 min)
3. **Start workload simulator** → Verify writes are succeeding
4. **Create Blue-Green deployment** → Wait for Green cluster ready (~15-60 min)
5. **Keep simulator running** → Do NOT stop during switchover
6. **Trigger switchover** → Observe connection errors and recovery
7. **Validate results** → Count failures, measure recovery time

**Expected behavior:**
- Downtime: 3-5 seconds with Blue-Green plugin, 11-20 seconds without
- Failed transactions: ~3-20 writes (depending on TPS and downtime)
- Automatic reconnection: Yes, via JDBC wrapper
- Data loss: None (Aurora guarantees durability)

---

## Lab Components

### 2.1 VPC and Network Infrastructure
- **Infrastructure as Code**: Pulumi (Golang)
- **VPC Configuration**:
  - 1 VPC with 2 Availability Zones
  - CIDR Block: 10.0.0.0/16 (configurable)
- **Subnet Architecture**:
  - **Aurora Database Subnets** (Private):
    - AZ1: 10.0.1.0/24 - Private subnet for Aurora writer
    - AZ2: 10.0.2.0/24 - Private subnet for Aurora reader
    - No NAT Gateway, No Internet Gateway
    - Isolated for maximum security
  - **EC2 Workload Simulator Subnet** (Public):
    - AZ1: 10.0.10.0/24 - Public subnet with Internet Gateway
    - Hosts EC2 instance running workload simulator
    - Internet access for software updates and management
  - **EKS Cluster Subnet** (Private - Optional):
    - AZ1: 10.0.20.0/24 - Private subnet for EKS nodes
    - AZ2: 10.0.21.0/24 - Private subnet for EKS nodes (optional)
    - No direct internet access
- **Security Groups**:
  - Aurora SG: Allows inbound MySQL (3306) from EC2 and EKS subnets only
  - EC2 SG: Allows outbound to Aurora, inbound SSH (22) from specified IP ranges
  - EKS SG: Allows outbound to Aurora, inter-node communication
- **Network Design Benefits**:
  - Aurora isolated in private subnets with no internet exposure
  - EC2 in public subnet for easy access and management
  - EKS in separate private subnet for advanced testing scenarios
  - Multi-AZ deployment for Aurora high availability
- **Network Architecture Diagram**:
  ```
  ┌─────────────────────────────────────────────────────────────────┐
  │                    VPC (10.0.0.0/16)                            │
  │                                                                 │
  │  ┌─────────────────────────┐  ┌─────────────────────────┐    │
  │  │   Availability Zone 1   │  │   Availability Zone 2   │    │
  │  │                         │  │                         │    │
  │  │ ┌─────────────────────┐ │  │ ┌─────────────────────┐ │    │
  │  │ │ Aurora Private      │ │  │ │ Aurora Private      │ │    │
  │  │ │ 10.0.1.0/24         │ │  │ │ 10.0.2.0/24         │ │    │
  │  │ │ (Writer)            │ │  │ │ (Reader)            │ │    │
  │  │ └─────────────────────┘ │  │ └─────────────────────┘ │    │
  │  │                         │  │                         │    │
  │  │ ┌─────────────────────┐ │  │                         │    │
  │  │ │ EC2 Public          │ │  │                         │    │
  │  │ │ 10.0.10.0/24        │←┼──┼─→ Internet Gateway     │    │
  │  │ │ (Workload Sim)      │ │  │                         │    │
  │  │ └─────────────────────┘ │  │                         │    │
  │  │                         │  │                         │    │
  │  │ ┌─────────────────────┐ │  │ ┌─────────────────────┐ │    │
  │  │ │ EKS Private         │ │  │ │ EKS Private         │ │    │
  │  │ │ 10.0.20.0/24        │ │  │ │ 10.0.21.0/24        │ │    │
  │  │ │ (Optional)          │ │  │ │ (Optional)          │ │    │
  │  │ └─────────────────────┘ │  │ └─────────────────────┘ │    │
  │  └─────────────────────────┘  └─────────────────────────┘    │
  └─────────────────────────────────────────────────────────────────┘
  ```

### 2.2 Aurora Database Infrastructure
- **Infrastructure as Code**: Pulumi (Golang)
- **Database**: Amazon Aurora MySQL-Compatible
  - Initial Version: 3.04
  - Target Version: 3.10
  - Database Schema: 12,000 tables to simulate production-scale metadata overhead and test Blue-Green deployment performance with large schemas
- **Cluster Configuration**:
  - 1 Writer instance (r6g.xlarge) - Primary endpoint for write operations
  - 1 Reader instance (r6g.xlarge) - Standby for high availability
  - Deployed across 2 private subnets in different AZs (10.0.1.0/24, 10.0.2.0/24)
  - Multi-AZ deployment for production-like reliability testing
  - No direct internet access - secured in isolated private subnets

### 2.3 Workload Simulator
- **Language**: Java
- **JDK Version**: Amazon Corretto 17
- **JDBC Driver**: AWS Advanced JDBC Wrapper 2.6.6
- **Workload Design**: Write-only workload targeting the writer endpoint to simulate production write operations during Blue-Green switchover
- **Deployment Options**:
  - **Option 1 (Recommended for Beginners)**: EC2 instance with manual execution
    - Deployed in public subnet (10.0.10.0/24) with Internet Gateway for management access
    - Instance type: t3.xlarge with Amazon Linux 2023
    - Simple command-line execution with direct console output
    - Real-time log output showing success/failed connections
    - Easy to start, stop, and observe
    - Ideal for learning and understanding the Blue-Green switchover behavior
    - Security Group allows SSH access and outbound connections to Aurora private subnets
  - **Option 2 (Advanced)**: Kubernetes (EKS) deployment
    - EKS nodes deployed in private subnets (10.0.20.0/24, 10.0.21.0/24)
    - Containerized deployment for scaled testing
    - Supports multiple pod instances for high-concurrency scenarios
    - Integrated with Prometheus/Grafana for advanced monitoring
    - Suitable for stress testing and production-like simulations
- **Worker Configuration**:
  - Minimum 10 write workers (configurable via parameters)
  - Workers can be scaled dynamically based on testing requirements
- **Functionality**:
  - Simulates realistic write workloads against the Aurora cluster
  - Uses AWS Advanced JDBC Wrapper for enhanced Aurora connectivity features:
    - **Blue-Green Plugin**: Proactive monitoring of Blue-Green deployment status for minimal downtime (3-5 seconds typical)
    - **Failover Plugin**: Automatic failover detection and handling during cluster failures
    - **Enhanced Failure Monitoring (EFM)**: Proactive connection health monitoring
    - Connection state tracking to detect interrupted transactions
    - Coordinated switchover across all database connections
  - Configurable workload parameters:
    - Number of write workers
    - Write request rate per worker
    - Connection pool settings
- **Output & Monitoring**:
  - Real-time console log output with timestamps
  - Success/failure indicators for each database operation
  - Connection error details during switchover events
  - Summary statistics (total requests, success rate, error count)
  - Optional metrics export to Prometheus (EKS deployment only)

### 2.4 Kubernetes Infrastructure (Optional - Advanced Testing)
- **Infrastructure as Code**: Pulumi (Golang)
- **Container Orchestration**: Amazon EKS (Elastic Kubernetes Service)
- **Use Case**: Advanced testing scenarios requiring high concurrency and distributed workloads
- **Deployment Architecture**:
  - Designed to support multiple workload simulator instances
  - Resource allocation per pod: 2 vCPU, 4GB RAM (supports 10-50 write workers)
  - Horizontal Pod Autoscaling (HPA) support for dynamic scaling based on CPU/memory utilization
  - Node group sizing optimized for high-concurrency workload scenarios (50+ total workers across pods)
- **Purpose**:
  - Hosts and manages containerized workload simulators for scaled testing
  - Enables easy horizontal scaling of workload generators
  - Provides infrastructure for the monitoring stack (Prometheus/Grafana integration)
- **Note**: EC2-based execution is recommended for initial learning; EKS deployment is optional for advanced use cases

### 2.5 Real-time Monitoring
- **Monitoring Stack**:
  - Amazon Managed Service for Prometheus (AMP) for metrics collection and storage
  - Amazon Managed Grafana (AMG) for real-time dashboard visualization
  - AWS Distro for OpenTelemetry (ADOT) Collector for metrics ingestion
  - Custom application metrics exported via Prometheus client library
- **Architecture Benefits**:
  - Fully managed, highly available monitoring infrastructure
  - Automatic scaling and no operational overhead
  - Native AWS IAM integration for secure access
  - Cross-AZ redundancy for reliability
- **Metrics Tracked**:
  - Total write requests sent
  - Successful write operations
  - Failed write operations
  - Response time percentiles (p50, p95, p99)
  - Connection status and errors
  - JDBC wrapper failover events
- **Monitoring Capabilities**:
  - Real-time dashboard visualization with 1-second refresh rate
  - Tracks workload performance during Blue-Green switchover
  - Identifies any service disruptions or degradation
  - Long-term historical data retention for post-upgrade analysis

## Technology Stack

| Component | Technology |
|-----------|-----------|
| Infrastructure as Code | Pulumi (Golang) |
| Networking | Amazon VPC with Multi-AZ deployment<br>Private subnets (Aurora, EKS)<br>Public subnet (EC2)<br>Internet Gateway, Security Groups |
| Database | Amazon Aurora MySQL 3.04 → 3.10 |
| Application Language | Java (Amazon Corretto 17) |
| JDBC Driver | AWS Advanced JDBC Wrapper 2.6.7 |

| Logging Framework | SLF4J API with Log4j2 implementation |
| Workload Simulator Host | EC2 (Amazon Linux 2023) - Primary<br>Amazon EKS - Optional for advanced testing |
| Monitoring | Amazon Managed Service for Prometheus (AMP) + Amazon Managed Grafana (AMG) |
| Metrics Collection | AWS Distro for OpenTelemetry (ADOT) |

## Getting Started

### Prerequisites
- AWS Account with appropriate permissions
- Pulumi CLI installed
- Go 1.21+ installed
- Docker installed
- kubectl installed
- AWS CLI configured

### Deployment Steps

#### Option 1: EC2-Based Deployment (Recommended for Beginners)

1. **Deploy VPC and Network Infrastructure**
   ```bash
   cd infrastructure/vpc
   pulumi up
   ```

   This will provision:
   - VPC with CIDR 10.0.0.0/16
   - 2 Availability Zones
   - Private subnets for Aurora (10.0.1.0/24, 10.0.2.0/24)
   - Public subnet for EC2 (10.0.10.0/24)
   - Private subnets for EKS (10.0.20.0/24, 10.0.21.0/24) - optional
   - Internet Gateway for public subnet
   - Security Groups for Aurora, EC2, and EKS
   - Route tables and subnet associations

2. **Deploy Aurora Cluster**
   ```bash
   cd infrastructure/aurora
   pulumi up
   ```

   This will deploy Aurora cluster into the private subnets created in step 1.

3. **Initialize Database Schema**
   ```bash
   # Run the schema initialization script to create 12,000 tables
   cd scripts
   ./init-schema.sh

   # This process may take 30-60 minutes to complete
   # Progress will be logged to schema-init.log
   ```

   The schema initialization creates:
   - 12,000 tables with identical structure
   - Each table has a primary key and 5 data columns
   - Minimal initial data (1 row per table) to establish baseline

4. **Deploy EC2 Instance for Workload Simulator**
   ```bash
   cd infrastructure/ec2
   pulumi up
   ```

   This will provision:
   - EC2 instance (t3.xlarge) with Amazon Linux 2023 in public subnet (10.0.10.0/24)
   - Amazon Corretto 17 pre-installed
   - Security group allowing SSH access and outbound access to Aurora private subnets
   - Workload simulator JAR pre-deployed to /opt/workload-simulator

5. **Run Workload Simulator**
   ```bash
   # SSH into the EC2 instance
   ssh -i your-key.pem ec2-user@<ec2-public-ip>

   # Navigate to workload simulator directory
   cd /opt/workload-simulator

   # Start the workload simulator (manual execution)
   java -jar workload-simulator.jar \
     --aurora-endpoint <your-aurora-cluster-endpoint> \
     --write-workers 10 \
     --write-rate 100 \
     --connection-pool-size 100

   # Output will show real-time success/failure logs
   # Press Ctrl+C to stop the simulator
   ```

#### Option 2: EKS-Based Deployment (Advanced - For Scaled Testing)

> **Prerequisites**: Complete steps 1-3 from Option 1 (VPC, Aurora, and Schema initialization)

4. **Deploy EKS Cluster (Optional)**
   ```bash
   cd infrastructure/eks
   pulumi up
   ```

   This will provision:
   - EKS cluster with nodes in private subnets (10.0.20.0/24, 10.0.21.0/24)
   - Security groups allowing communication with Aurora
   - IAM roles for EKS nodes and service accounts

5. **Deploy Monitoring Stack (Optional)**
   ```bash
   # Create Amazon Managed Service for Prometheus workspace
   cd infrastructure/monitoring
   pulumi up

   # Deploy ADOT Collector to EKS for metrics collection
   kubectl apply -f adot-collector-config.yaml
   ```

   This will provision:
   - Amazon Managed Service for Prometheus (AMP) workspace
   - Amazon Managed Grafana (AMG) workspace with IAM authentication
   - ADOT Collector deployment in EKS cluster
   - Pre-configured Grafana dashboard for Blue-Green monitoring

6. **Build and Deploy Workload Simulator to EKS (Optional)**
   ```bash
   cd workload-simulator
   docker build -t workload-simulator:latest .
   kubectl apply -f kubernetes/
   ```

   Workload simulator pods will be deployed in EKS private subnets with connectivity to Aurora.

7. **Access Monitoring Dashboard (Optional)**
   ```bash
   # Get the Amazon Managed Grafana workspace URL
   pulumi stack output grafana-workspace-url

   # Access via web browser (SSO or IAM authentication)
   # Dashboard: "Aurora Blue-Green Deployment Monitor"
   ```

   > **Note**: Amazon Managed Grafana uses AWS SSO or IAM Identity Center for authentication. Ensure your AWS account has the necessary permissions configured.

## Blue-Green Upgrade Process

### EC2-Based Testing Flow (Recommended)

1. **Start the workload simulator** on EC2 instance with desired parameters (see step 4 above)
2. **Verify workload is running** - Watch the console output showing successful write operations
3. **Initiate Blue-Green deployment** via AWS Console or CLI:
   ```bash
   aws rds create-blue-green-deployment \
     --blue-green-deployment-name aurora-upgrade-test \
     --source-arn <source-cluster-arn> \
     --target-engine-version 8.0.mysql_aurora.3.10.0
   ```
4. **Keep the workload simulator running** - Do NOT stop the simulator during the upgrade
5. **Observe the console output** during the Blue-Green switchover:
   - Look for connection errors or transaction failures
   - Note the timestamp when errors occur (if any)
   - Observe the JDBC wrapper failover behavior
6. **Complete the switchover** when ready:
   ```bash
   aws rds switchover-blue-green-deployment \
     --blue-green-deployment-identifier <deployment-id>
   ```
7. **Monitor the logs** during switchover - This is the critical moment to observe:
   - Any connection interruptions
   - Automatic reconnection attempts
   - Time to recovery
8. **Validate post-upgrade**:
   - Verify workload continues successfully
   - Check Aurora cluster version: `SELECT @@aurora_version;`
   - Review the log output for total failures vs. successes

### EKS-Based Testing Flow (Optional - Advanced)

1. Deploy workload simulator pods to EKS
2. Monitor metrics in Amazon Managed Grafana
3. Follow steps 3-8 above
4. Use Grafana dashboards to visualize switchover impact

## Workload Simulator Configuration

### Command-Line Parameters

- `--aurora-endpoint`: Aurora cluster writer endpoint (required)
- `--database-name`: Database name (default: lab_db)
- `--write-workers`: Number of concurrent write workers (minimum: 1, default: 10)
- `--write-rate`: Writes per second per worker (default: 100)
- `--connection-pool-size`: Database connection pool size (default: 100)
- `--log-interval`: Statistics log interval in seconds (default: 10)

### EC2 Execution Examples

**Basic Configuration (Recommended for Testing):**
```bash
java -jar workload-simulator.jar \
  --aurora-endpoint my-cluster.cluster-xxxxx.us-east-1.rds.amazonaws.com \
  --database-name lab_db \
  --write-workers 10 \
  --write-rate 100 \
  --connection-pool-size 100
```

**High Load Configuration:**
```bash
java -jar workload-simulator.jar \
  --aurora-endpoint my-cluster.cluster-xxxxx.us-east-1.rds.amazonaws.com \
  --database-name lab_db \
  --write-workers 50 \
  --write-rate 200 \
  --connection-pool-size 500
```

> **Note**: Connection pool sizing recommendation: 10 connections per worker for optimal throughput. Minimum pool size should be at least equal to the number of workers.

### Sample Console Output

```
[2025-01-18 10:15:23.456] INFO: Workload Simulator Started
[2025-01-18 10:15:23.457] INFO: Aurora Endpoint: my-cluster.cluster-xxxxx.us-east-1.rds.amazonaws.com
[2025-01-18 10:15:23.458] INFO: Workers: 10, Rate: 100 writes/sec/worker
[2025-01-18 10:15:24.123] SUCCESS: Worker-1 | Host: ip-10-0-1-45 (writer) | Table: test_0001 | INSERT completed | Latency: 12ms
[2025-01-18 10:15:24.234] SUCCESS: Worker-2 | Host: ip-10-0-1-45 (writer) | Table: test_0042 | INSERT completed | Latency: 15ms
[2025-01-18 10:15:24.345] SUCCESS: Worker-3 | Host: ip-10-0-1-45 (writer) | Table: test_0123 | INSERT completed | Latency: 11ms
...
[2025-01-18 10:15:34.123] STATS: Total: 1000 | Success: 1000 | Failed: 0 | Success Rate: 100.00%
[2025-01-18 10:16:45.678] ERROR: Worker-5 | Table: test_0123 | connection_lost | Retry 1/5 in 500ms | Error: Communications link failure
[2025-01-18 10:16:46.012] INFO: Worker-5 | Switched to new host: ip-10-0-2-78 (writer) (from: ip-10-0-1-45 (writer))
[2025-01-18 10:16:46.123] SUCCESS: Worker-5 | Host: ip-10-0-2-78 (writer) | Table: test_0123 | INSERT completed | Latency: 234ms (retry 1)
```

### EKS Deployment (Optional - Advanced)

**Kubernetes Deployment Scaling:**
```bash
# Scale the number of workload simulator pods
kubectl scale deployment workload-simulator --replicas=5

# Each pod runs with configurable workers
# Total capacity: 5 pods × 10 write workers = 50 write workers

# View logs from all pods
kubectl logs -l app=workload-simulator -f
```

## Project Structure

```
aurora-blue-green-deployment-lab/
├── infrastructure/
│   ├── vpc/             # Pulumi code for VPC and network infrastructure
│   ├── aurora/          # Pulumi code for Aurora cluster
│   ├── ec2/             # Pulumi code for EC2 workload simulator host
│   ├── eks/             # (Optional) Pulumi code for EKS cluster
│   └── monitoring/      # (Optional) Pulumi code for AMP and AMG setup
├── scripts/
│   └── init-schema.sh   # Database schema initialization script
├── workload-simulator/  # Java workload simulator application
│   ├── src/             # Java source code
│   ├── pom.xml          # Maven configuration
│   ├── Dockerfile       # (Optional) Container image for EKS deployment
│   └── kubernetes/      # (Optional) K8s manifests for EKS deployment
├── monitoring/          # (Optional) Advanced monitoring for EKS
│   ├── adot-collector-config.yaml  # ADOT Collector configuration
│   └── dashboards/      # Pre-configured Grafana dashboard JSON
└── docs/                # Additional documentation
```

## Learning Objectives

By completing this lab, you will:
- Understand Aurora Blue-Green deployment architecture
- Learn how to monitor database upgrades in real-time
- Experience zero-downtime upgrade procedures
- Gain insights into workload behavior during infrastructure changes
- Practice scaling workloads to test different scenarios

## Resources

- [Aurora Blue-Green Deployments Documentation](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/blue-green-deployments.html)
- [Amazon Aurora MySQL Version 3.10 Release Notes](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraMySQLReleaseNotes/AuroraMySQL.Updates.3010.html)
- [Pulumi AWS Provider Documentation](https://www.pulumi.com/registry/packages/aws/)

## License

See [LICENSE](LICENSE) file for details.

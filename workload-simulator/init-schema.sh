#!/bin/bash
set -e

#####################################################################
# Aurora Blue-Green Deployment Lab - Database Schema Initialization
#
# This script creates 12,000 test tables in the Aurora MySQL database
# to simulate production-scale metadata overhead for testing Blue-Green
# deployment performance with large schemas.
#
# Usage:
#   ./init-schema.sh --endpoint <endpoint> --password <password> [options]
#
# The 12,000 tables are intentional and necessary for:
# - Testing Blue-Green deployment with production-scale metadata
# - Simulating real-world schema complexity
# - Validating switchover performance under heavy metadata load
#####################################################################

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Default values
DATABASE_NAME="lab_db"
USERNAME="admin"
PASSWORD=""
AURORA_ENDPOINT=""
TOTAL_TABLES=12000
PARALLEL_WORKERS=4
LOG_FILE="schema-init.log"

# Function to print colored output
print_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

print_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

print_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

print_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Function to print usage
print_usage() {
    cat << EOF
Aurora Blue-Green Deployment Lab - Database Schema Initialization

Usage:
  $0 --endpoint <endpoint> --password <password> [options]

Required options:
  --endpoint <endpoint>       Aurora cluster writer endpoint
  --password <password>       Database password (or use DB_PASSWORD env var)

Optional options:
  --database <name>           Database name (default: lab_db)
  --username <username>       Database username (default: admin)
  --tables <count>            Number of tables to create (default: 12000)
  --parallel <workers>        Number of parallel workers (default: 4)
  --log-file <path>           Log file path (default: schema-init.log)
  --help                      Show this help message

Environment variables:
  DB_PASSWORD                 Database password (alternative to --password)

Examples:
  # Basic usage
  $0 --endpoint my-cluster.cluster-xxxxx.us-east-1.rds.amazonaws.com \\
     --password MySecretPassword

  # High-performance initialization with 8 parallel workers
  $0 --endpoint my-cluster.cluster-xxxxx.us-east-1.rds.amazonaws.com \\
     --password MySecretPassword \\
     --parallel 8

  # Custom table count
  $0 --endpoint my-cluster.cluster-xxxxx.us-east-1.rds.amazonaws.com \\
     --password MySecretPassword \\
     --tables 5000

Notes:
  - Schema initialization typically takes 30-60 minutes for 12,000 tables
  - Increasing --parallel can speed up the process but may impact Aurora performance
  - Progress is logged to both console and log file
  - The script creates tables with minimal initial data (1 row per table)
EOF
}

# Parse command-line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        --endpoint)
            AURORA_ENDPOINT="$2"
            shift 2
            ;;
        --database)
            DATABASE_NAME="$2"
            shift 2
            ;;
        --username)
            USERNAME="$2"
            shift 2
            ;;
        --password)
            PASSWORD="$2"
            shift 2
            ;;
        --tables)
            TOTAL_TABLES="$2"
            shift 2
            ;;
        --parallel)
            PARALLEL_WORKERS="$2"
            shift 2
            ;;
        --log-file)
            LOG_FILE="$2"
            shift 2
            ;;
        --help)
            print_usage
            exit 0
            ;;
        *)
            print_error "Unknown argument: $1"
            print_usage
            exit 1
            ;;
    esac
done

# Get password from environment if not provided
if [[ -z "$PASSWORD" ]]; then
    PASSWORD="${DB_PASSWORD}"
fi

# Validate required parameters
if [[ -z "$AURORA_ENDPOINT" ]]; then
    print_error "Aurora endpoint is required"
    print_usage
    exit 1
fi

if [[ -z "$PASSWORD" ]]; then
    print_error "Password is required (use --password or DB_PASSWORD environment variable)"
    print_usage
    exit 1
fi

# Validate numeric parameters
if ! [[ "$TOTAL_TABLES" =~ ^[0-9]+$ ]] || [[ "$TOTAL_TABLES" -lt 1 ]]; then
    print_error "Invalid table count: $TOTAL_TABLES (must be a positive integer)"
    exit 1
fi

if ! [[ "$PARALLEL_WORKERS" =~ ^[0-9]+$ ]] || [[ "$PARALLEL_WORKERS" -lt 1 ]]; then
    print_error "Invalid parallel workers: $PARALLEL_WORKERS (must be a positive integer)"
    exit 1
fi

# Initialize log file
echo "========================================" > "$LOG_FILE"
echo "Aurora Schema Initialization Log" >> "$LOG_FILE"
echo "Started at: $(date)" >> "$LOG_FILE"
echo "========================================" >> "$LOG_FILE"

print_info "========================================="
print_info "Aurora Schema Initialization"
print_info "========================================="
print_info "Aurora Endpoint: $AURORA_ENDPOINT"
print_info "Database: $DATABASE_NAME"
print_info "Username: $USERNAME"
print_info "Total Tables: $TOTAL_TABLES"
print_info "Parallel Workers: $PARALLEL_WORKERS"
print_info "Log File: $LOG_FILE"
print_info "========================================="

# Test database connection
print_info "Testing database connection..."
if ! mysql -h "$AURORA_ENDPOINT" -u "$USERNAME" -p"$PASSWORD" -e "SELECT 1" &> /dev/null; then
    print_error "Failed to connect to Aurora database"
    print_error "Please verify endpoint, username, and password"
    exit 1
fi
print_success "Database connection successful"

# Create database if not exists
print_info "Creating database '$DATABASE_NAME' if not exists..."
mysql -h "$AURORA_ENDPOINT" -u "$USERNAME" -p"$PASSWORD" -e "CREATE DATABASE IF NOT EXISTS $DATABASE_NAME" 2>> "$LOG_FILE"
print_success "Database '$DATABASE_NAME' is ready"

# Function to create a batch of tables
create_table_batch() {
    local start_id=$1
    local end_id=$2
    local worker_id=$3

    local mysql_cmd="mysql -h $AURORA_ENDPOINT -u $USERNAME -p$PASSWORD $DATABASE_NAME"

    for ((table_id=start_id; table_id<=end_id; table_id++)); do
        table_name=$(printf "test_%04d" $table_id)

        # Create table SQL
        create_sql="CREATE TABLE IF NOT EXISTS $table_name (
            id BIGINT AUTO_INCREMENT PRIMARY KEY,
            col1 VARCHAR(255),
            col2 INT,
            col3 VARCHAR(255),
            col4 BIGINT,
            col5 TEXT,
            created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
            INDEX idx_col2 (col2),
            INDEX idx_col4 (col4)
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;"

        # Insert initial row
        insert_sql="INSERT INTO $table_name (col1, col2, col3, col4, col5)
                    VALUES ('init-data', 0, 'baseline', 0, 'Initial row for testing');"

        # Execute SQL
        if echo "$create_sql $insert_sql" | $mysql_cmd 2>> "$LOG_FILE"; then
            echo "[Worker-$worker_id] Created table $table_name ($table_id/$TOTAL_TABLES)" >> "$LOG_FILE"
        else
            echo "[Worker-$worker_id] ERROR: Failed to create table $table_name" >> "$LOG_FILE"
            return 1
        fi
    done

    return 0
}

# Calculate tables per worker
tables_per_worker=$((TOTAL_TABLES / PARALLEL_WORKERS))
remainder=$((TOTAL_TABLES % PARALLEL_WORKERS))

print_info "Starting schema creation with $PARALLEL_WORKERS parallel workers..."
print_info "This may take 30-60 minutes for $TOTAL_TABLES tables..."

# Start time
start_time=$(date +%s)

# Launch parallel workers
pids=()
for ((worker=0; worker<PARALLEL_WORKERS; worker++)); do
    start_id=$((worker * tables_per_worker + 1))
    end_id=$(((worker + 1) * tables_per_worker))

    # Add remainder to last worker
    if [[ $worker -eq $((PARALLEL_WORKERS - 1)) ]]; then
        end_id=$((end_id + remainder))
    fi

    print_info "Worker-$((worker + 1)): Creating tables $start_id to $end_id"

    # Launch worker in background
    create_table_batch $start_id $end_id $((worker + 1)) &
    pids+=($!)
done

# Wait for all workers to complete
print_info "Waiting for all workers to complete..."

failed=0
for ((i=0; i<${#pids[@]}; i++)); do
    if wait ${pids[$i]}; then
        print_success "Worker-$((i + 1)) completed successfully"
    else
        print_error "Worker-$((i + 1)) failed"
        failed=1
    fi
done

# End time
end_time=$(date +%s)
duration=$((end_time - start_time))
minutes=$((duration / 60))
seconds=$((duration % 60))

if [[ $failed -eq 0 ]]; then
    print_success "========================================="
    print_success "Schema initialization completed successfully!"
    print_success "Total tables created: $TOTAL_TABLES"
    print_success "Time taken: ${minutes}m ${seconds}s"
    print_success "========================================="

    # Verify table count
    print_info "Verifying table count..."
    actual_count=$(mysql -h "$AURORA_ENDPOINT" -u "$USERNAME" -p"$PASSWORD" -N -e \
        "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='$DATABASE_NAME' AND table_name LIKE 'test_%'" 2>> "$LOG_FILE")

    if [[ "$actual_count" -eq "$TOTAL_TABLES" ]]; then
        print_success "Verified: $actual_count tables found in database"
    else
        print_warning "Table count mismatch: Expected $TOTAL_TABLES, found $actual_count"
        print_warning "Check $LOG_FILE for details"
    fi

    # Display sample data
    print_info "Sample tables created:"
    mysql -h "$AURORA_ENDPOINT" -u "$USERNAME" -p"$PASSWORD" -e \
        "SHOW TABLES FROM $DATABASE_NAME LIKE 'test_%' LIMIT 5" 2>> "$LOG_FILE" | tail -n +2

    echo "" >> "$LOG_FILE"
    echo "========================================" >> "$LOG_FILE"
    echo "Completed at: $(date)" >> "$LOG_FILE"
    echo "Duration: ${minutes}m ${seconds}s" >> "$LOG_FILE"
    echo "========================================" >> "$LOG_FILE"

    print_info "Full logs available in: $LOG_FILE"
    exit 0
else
    print_error "========================================="
    print_error "Schema initialization failed!"
    print_error "Duration: ${minutes}m ${seconds}s"
    print_error "Check $LOG_FILE for error details"
    print_error "========================================="
    exit 1
fi

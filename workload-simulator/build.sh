#!/bin/bash
set -e

#####################################################################
# Aurora Blue-Green Workload Simulator - Build Script
#
# This script provides convenient commands for building, cleaning,
# and managing the Maven-based workload simulator project.
#
# Usage:
#   ./build.sh [command]
#
# Commands:
#   build         - Clean and build the project (default)
#   clean         - Clean build artifacts
#   package       - Build JAR with all dependencies
#   compile       - Compile source code only
#   test          - Run tests
#   verify        - Verify syntax without building
#   install       - Install to local Maven repository
#   help          - Show this help message
#####################################################################

# Color codes for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

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
Aurora Blue-Green Workload Simulator - Build Script

Usage:
  $0 [command]

Commands:
  build         Clean and build the project with all dependencies (default)
  clean         Remove all build artifacts and target directory
  package       Build JAR with all dependencies (skip tests)
  compile       Compile source code only (no packaging)
  test          Run unit tests
  verify        Verify project structure and dependencies
  install       Install to local Maven repository
  dependency    Display dependency tree
  help          Show this help message

Examples:
  # Build the project (clean + package)
  $0 build

  # Clean all build artifacts
  $0 clean

  # Quick compile without tests
  $0 compile

  # View dependency tree
  $0 dependency

Environment:
  MAVEN_OPTS    Additional Maven options (e.g., MAVEN_OPTS="-Xmx2g")

Notes:
  - Java 17 (Amazon Corretto) is required
  - Maven 3.9+ is required
  - Built JAR will be in target/workload-simulator.jar
  - Build typically takes 1-2 minutes
EOF
}

# Check prerequisites
check_prerequisites() {
    # Check Java
    if ! command -v java &> /dev/null; then
        print_error "Java is not installed or not in PATH"
        print_info "Please install Amazon Corretto 17 or OpenJDK 17"
        exit 1
    fi

    # Check Java version
    JAVA_VERSION=$(java -version 2>&1 | head -n 1 | cut -d'"' -f2 | cut -d'.' -f1)
    if [[ "$JAVA_VERSION" -lt 17 ]]; then
        print_error "Java 17 or higher is required (found: Java $JAVA_VERSION)"
        exit 1
    fi

    # Check Maven
    if ! command -v mvn &> /dev/null; then
        print_error "Maven is not installed or not in PATH"
        print_info "Please install Maven 3.9 or higher"
        exit 1
    fi

    print_success "Prerequisites check passed (Java $JAVA_VERSION, $(mvn --version | head -n 1))"
}

# Build command - clean and package
cmd_build() {
    print_info "========================================="
    print_info "Building Aurora Workload Simulator"
    print_info "========================================="

    check_prerequisites

    print_info "Cleaning previous build..."
    mvn clean

    print_info "Building JAR with dependencies..."
    mvn package -DskipTests

    if [[ -f target/workload-simulator.jar ]]; then
        JAR_SIZE=$(ls -lh target/workload-simulator.jar | awk '{print $5}')
        print_success "========================================="
        print_success "Build completed successfully!"
        print_success "JAR: target/workload-simulator.jar ($JAR_SIZE)"
        print_success "========================================="
        print_info "Run with: java -jar target/workload-simulator.jar --help"
    else
        print_error "Build failed - JAR not found"
        exit 1
    fi
}

# Clean command
cmd_clean() {
    print_info "Cleaning build artifacts..."

    if [[ -d target ]]; then
        rm -rf target
        print_success "Removed target/ directory"
    fi

    if [[ -d logs ]]; then
        rm -rf logs
        print_success "Removed logs/ directory"
    fi

    if [[ -f dependency-reduced-pom.xml ]]; then
        rm -f dependency-reduced-pom.xml
        print_success "Removed dependency-reduced-pom.xml"
    fi

    # Clean Maven cache for this project (optional)
    print_info "Cleaning Maven cache for this project..."
    mvn clean -q

    print_success "Clean completed"
}

# Package command - build JAR only
cmd_package() {
    print_info "Packaging workload simulator..."

    check_prerequisites

    mvn package -DskipTests

    if [[ -f target/workload-simulator.jar ]]; then
        JAR_SIZE=$(ls -lh target/workload-simulator.jar | awk '{print $5}')
        print_success "Package created: target/workload-simulator.jar ($JAR_SIZE)"
    else
        print_error "Package failed"
        exit 1
    fi
}

# Compile command
cmd_compile() {
    print_info "Compiling source code..."

    check_prerequisites

    mvn compile

    print_success "Compilation completed"
}

# Test command
cmd_test() {
    print_info "Running tests..."

    check_prerequisites

    mvn test

    print_success "Tests completed"
}

# Verify command
cmd_verify() {
    print_info "Verifying project..."

    check_prerequisites

    print_info "Checking POM syntax..."
    mvn validate

    print_info "Checking dependencies..."
    mvn dependency:resolve

    print_info "Verifying Java syntax..."
    mvn compiler:testCompile

    print_success "Verification completed"
}

# Install command
cmd_install() {
    print_info "Installing to local Maven repository..."

    check_prerequisites

    mvn install -DskipTests

    print_success "Installation completed"
    print_info "Installed to: ~/.m2/repository/com/aws/aurora/aurora-workload-simulator/1.0.0/"
}

# Dependency tree command
cmd_dependency() {
    print_info "Displaying dependency tree..."

    check_prerequisites

    mvn dependency:tree

    print_info "To see detailed conflicts, run: mvn dependency:tree -Ddetail=true"
}

# Main script logic
main() {
    # Get command (default to build)
    COMMAND="${1:-build}"

    case "$COMMAND" in
        build)
            cmd_build
            ;;
        clean)
            cmd_clean
            ;;
        package)
            cmd_package
            ;;
        compile)
            cmd_compile
            ;;
        test)
            cmd_test
            ;;
        verify)
            cmd_verify
            ;;
        install)
            cmd_install
            ;;
        dependency|deps)
            cmd_dependency
            ;;
        help|--help|-h)
            print_usage
            ;;
        *)
            print_error "Unknown command: $COMMAND"
            echo ""
            print_usage
            exit 1
            ;;
    esac
}

# Run main function
main "$@"

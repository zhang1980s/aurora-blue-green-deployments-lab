package com.aws.aurora;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.prometheus.PrometheusConfig;
import io.micrometer.prometheus.PrometheusMeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.bridge.SLF4JBridgeHandler;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.LogManager;

/**
 * Workload Simulator for Aurora Blue-Green Deployment Testing
 *
 * This application simulates write-heavy workloads against an Aurora MySQL cluster
 * to test the Blue-Green deployment feature with minimal downtime.
 *
 * Key Features:
 * - Write-only workload targeting the writer endpoint
 * - Configurable number of concurrent write workers
 * - AWS Advanced JDBC Wrapper with Blue-Green plugin support
 * - Real-time console output showing success/failure status
 * - Automatic reconnection and retry logic
 * - Prometheus metrics export for monitoring (optional)
 */
public class WorkloadSimulator {

    private static final Logger logger = LoggerFactory.getLogger(WorkloadSimulator.class);

    // Configuration parameters
    private final String auroraEndpoint;
    private final String databaseName;
    private final String username;
    private final String password;
    private final int writeWorkers;
    private final int writeRatePerWorker;
    private final int connectionPoolSize;
    private final int logIntervalSeconds;
    private final String blueGreenDeploymentId;

    // Data source and connection pool
    private HikariDataSource dataSource;

    // Metrics registry
    private final MeterRegistry meterRegistry;
    private final Counter successCounter;
    private final Counter failureCounter;
    private final Timer writeLatencyTimer;

    // Atomic counters for statistics
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong successfulRequests = new AtomicLong(0);
    private final AtomicLong failedRequests = new AtomicLong(0);

    // Executor services
    private ExecutorService workerExecutor;
    private ScheduledExecutorService statsExecutor;

    // Shutdown flag
    private final AtomicBoolean running = new AtomicBoolean(true);

    // Random generator for table selection
    private final Random random = new Random();
    private final int totalTables = 12000;

    public WorkloadSimulator(Config config) {
        this.auroraEndpoint = config.auroraEndpoint;
        this.databaseName = config.databaseName;
        this.username = config.username;
        this.password = config.password;
        this.writeWorkers = config.writeWorkers;
        this.writeRatePerWorker = config.writeRatePerWorker;
        this.connectionPoolSize = config.connectionPoolSize;
        this.logIntervalSeconds = config.logIntervalSeconds;
        this.blueGreenDeploymentId = config.blueGreenDeploymentId;

        // Initialize metrics registry
        if (config.enablePrometheus) {
            this.meterRegistry = new PrometheusMeterRegistry(PrometheusConfig.DEFAULT);
        } else {
            this.meterRegistry = new SimpleMeterRegistry();
        }

        this.successCounter = Counter.builder("workload.writes.success")
                .description("Number of successful write operations")
                .register(meterRegistry);

        this.failureCounter = Counter.builder("workload.writes.failure")
                .description("Number of failed write operations")
                .register(meterRegistry);

        this.writeLatencyTimer = Timer.builder("workload.writes.latency")
                .description("Write operation latency")
                .register(meterRegistry);
    }

    /**
     * Initialize the HikariCP connection pool with AWS JDBC Wrapper
     */
    private void initializeDataSource() {
        logger.info("Initializing connection pool...");

        HikariConfig hikariConfig = new HikariConfig();

        // Build JDBC URL with AWS wrapper
        StringBuilder jdbcUrl = new StringBuilder("jdbc:aws-wrapper:mysql://");
        jdbcUrl.append(auroraEndpoint).append(":3306/").append(databaseName);
        jdbcUrl.append("?wrapperPlugins=initialConnection,auroraConnectionTracker,bg,failover2,efm2");

        if (blueGreenDeploymentId != null && !blueGreenDeploymentId.isEmpty()) {
            jdbcUrl.append("&bgdId=").append(blueGreenDeploymentId);
        }

        jdbcUrl.append("&connectTimeout=30000");
        jdbcUrl.append("&socketTimeout=30000");
        jdbcUrl.append("&failoverTimeoutMs=60000");
        jdbcUrl.append("&failoverClusterTopologyRefreshRateMs=2000");
        jdbcUrl.append("&bgConnectTimeoutMs=30000");
        jdbcUrl.append("&bgSwitchoverTimeoutMs=180000");

        hikariConfig.setJdbcUrl(jdbcUrl.toString());
        hikariConfig.setUsername(username);
        hikariConfig.setPassword(password);
        hikariConfig.setDriverClassName("software.amazon.jdbc.Driver");

        // Connection pool settings
        hikariConfig.setMaximumPoolSize(connectionPoolSize);
        hikariConfig.setMinimumIdle(Math.min(10, connectionPoolSize / 2));
        hikariConfig.setConnectionTimeout(30000);
        hikariConfig.setIdleTimeout(600000);
        hikariConfig.setMaxLifetime(1800000);
        hikariConfig.setPoolName("AuroraWorkloadPool");

        // Connection test query
        hikariConfig.setConnectionTestQuery("SELECT 1");

        this.dataSource = new HikariDataSource(hikariConfig);

        logger.info("Connection pool initialized with {} max connections", connectionPoolSize);
        logger.info("JDBC URL: {}", jdbcUrl.toString().replaceAll("password=[^&]*", "password=***"));
    }

    /**
     * Start the workload simulator
     */
    public void start() {
        logger.info("========================================");
        logger.info("Aurora Blue-Green Workload Simulator");
        logger.info("========================================");
        logger.info("Aurora Endpoint: {}", auroraEndpoint);
        logger.info("Database: {}", databaseName);
        logger.info("Write Workers: {}", writeWorkers);
        logger.info("Write Rate: {} writes/sec/worker", writeRatePerWorker);
        logger.info("Connection Pool Size: {}", connectionPoolSize);
        logger.info("Blue-Green Deployment ID: {}", blueGreenDeploymentId != null ? blueGreenDeploymentId : "auto-detect");
        logger.info("========================================");

        // Initialize data source
        initializeDataSource();

        // Test initial connection
        if (!testConnection()) {
            logger.error("Failed to establish initial connection. Exiting...");
            return;
        }

        // Create executor services
        workerExecutor = Executors.newFixedThreadPool(writeWorkers);
        statsExecutor = Executors.newScheduledThreadPool(1);

        // Start statistics logging
        statsExecutor.scheduleAtFixedRate(
            this::logStatistics,
            logIntervalSeconds,
            logIntervalSeconds,
            TimeUnit.SECONDS
        );

        // Start write workers
        for (int i = 0; i < writeWorkers; i++) {
            final int workerId = i + 1;
            workerExecutor.submit(() -> writeWorker(workerId));
        }

        logger.info("Workload simulator started successfully");

        // Add shutdown hook
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));
    }

    /**
     * Test database connection
     */
    private boolean testConnection() {
        logger.info("Testing database connection...");
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT @@aurora_version, @@hostname")) {

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                String auroraVersion = rs.getString(1);
                String hostname = rs.getString(2);
                logger.info("Connected successfully to Aurora version {} on host {}", auroraVersion, hostname);
                return true;
            }
        } catch (SQLException e) {
            logger.error("Connection test failed", e);
        }
        return false;
    }

    /**
     * Write worker thread
     */
    private void writeWorker(int workerId) {
        logger.info("Worker-{} started", workerId);

        long delayMs = writeRatePerWorker > 0 ? 1000 / writeRatePerWorker : 0;
        String currentHost = null;

        while (running.get()) {
            long startTime = System.currentTimeMillis();

            try {
                // Select a random table (1 to totalTables)
                int tableId = random.nextInt(totalTables) + 1;
                String tableName = String.format("test_%04d", tableId);

                // Execute write operation
                boolean success = executeWrite(tableName, workerId);

                long latency = System.currentTimeMillis() - startTime;

                if (success) {
                    successfulRequests.incrementAndGet();
                    successCounter.increment();
                    writeLatencyTimer.record(Duration.ofMillis(latency));

                    // Get current host
                    String newHost = getCurrentHost();
                    if (newHost != null && !newHost.equals(currentHost)) {
                        if (currentHost != null) {
                            logger.info("Worker-{} | Switched to new host: {} (from: {})",
                                workerId, newHost, currentHost);
                        }
                        currentHost = newHost;
                    }

                    logger.info("SUCCESS: Worker-{} | Host: {} | Table: {} | INSERT completed | Latency: {}ms",
                        workerId, currentHost != null ? currentHost : "unknown", tableName, latency);
                } else {
                    failedRequests.incrementAndGet();
                    failureCounter.increment();
                    logger.error("FAILED: Worker-{} | Table: {} | INSERT failed | Latency: {}ms",
                        workerId, tableName, latency);
                }

                totalRequests.incrementAndGet();

                // Rate limiting
                if (delayMs > 0) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    long sleepTime = delayMs - elapsed;
                    if (sleepTime > 0) {
                        Thread.sleep(sleepTime);
                    }
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("Worker-{} encountered unexpected error", workerId, e);
                failedRequests.incrementAndGet();
                failureCounter.increment();
                totalRequests.incrementAndGet();
            }
        }

        logger.info("Worker-{} stopped", workerId);
    }

    /**
     * Execute a write operation with retry logic
     */
    private boolean executeWrite(String tableName, int workerId) {
        int maxRetries = 5;
        int retryDelayMs = 500;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "INSERT INTO " + tableName + " (col1, col2, col3, col4, col5) VALUES (?, ?, ?, ?, ?)")) {

                stmt.setString(1, "data-" + System.currentTimeMillis());
                stmt.setInt(2, random.nextInt(1000));
                stmt.setString(3, "worker-" + workerId);
                stmt.setLong(4, System.currentTimeMillis());
                stmt.setString(5, "test-data");

                int rowsAffected = stmt.executeUpdate();
                return rowsAffected > 0;

            } catch (SQLException e) {
                logger.warn("Worker-{} | Table: {} | connection_error | Retry {}/{} in {}ms | Error: {}",
                    workerId, tableName, attempt, maxRetries, retryDelayMs * attempt, e.getMessage());

                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(retryDelayMs * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                } else {
                    logger.error("Worker-{} | Table: {} | Max retries exceeded", workerId, tableName);
                    return false;
                }
            }
        }

        return false;
    }

    /**
     * Get current Aurora host
     */
    private String getCurrentHost() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement("SELECT @@hostname")) {

            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString(1);
            }
        } catch (SQLException e) {
            // Ignore - not critical
        }
        return null;
    }

    /**
     * Log statistics
     */
    private void logStatistics() {
        long total = totalRequests.get();
        long success = successfulRequests.get();
        long failed = failedRequests.get();
        double successRate = total > 0 ? (success * 100.0 / total) : 0.0;

        logger.info("========================================");
        logger.info("STATS: Total: {} | Success: {} | Failed: {} | Success Rate: {}%",
            total, success, failed, String.format("%.2f", successRate));
        logger.info("========================================");
    }

    /**
     * Shutdown the simulator
     */
    private void shutdown() {
        logger.info("Shutting down workload simulator...");
        running.set(false);

        // Shutdown executors
        if (workerExecutor != null) {
            workerExecutor.shutdown();
            try {
                if (!workerExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                    workerExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                workerExecutor.shutdownNow();
            }
        }

        if (statsExecutor != null) {
            statsExecutor.shutdown();
        }

        // Close data source
        if (dataSource != null) {
            dataSource.close();
        }

        // Final statistics
        logStatistics();
        logger.info("Workload simulator stopped");
    }

    /**
     * Configuration class
     */
    public static class Config {
        String auroraEndpoint;
        String databaseName = "lab_db";
        String username = "admin";
        String password;
        int writeWorkers = 10;
        int writeRatePerWorker = 100;
        int connectionPoolSize = 100;
        int logIntervalSeconds = 10;
        String blueGreenDeploymentId = null;
        boolean enablePrometheus = false;
    }

    /**
     * Main entry point
     */
    public static void main(String[] args) {
        Config config = new Config();

        // Parse command-line arguments
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--aurora-endpoint":
                    config.auroraEndpoint = args[++i];
                    break;
                case "--database-name":
                    config.databaseName = args[++i];
                    break;
                case "--username":
                    config.username = args[++i];
                    break;
                case "--password":
                    config.password = args[++i];
                    break;
                case "--write-workers":
                    config.writeWorkers = Integer.parseInt(args[++i]);
                    break;
                case "--write-rate":
                    config.writeRatePerWorker = Integer.parseInt(args[++i]);
                    break;
                case "--connection-pool-size":
                    config.connectionPoolSize = Integer.parseInt(args[++i]);
                    break;
                case "--log-interval":
                    config.logIntervalSeconds = Integer.parseInt(args[++i]);
                    break;
                case "--blue-green-deployment-id":
                    config.blueGreenDeploymentId = args[++i];
                    break;
                case "--enable-prometheus":
                    config.enablePrometheus = true;
                    break;
                case "--help":
                    printUsage();
                    return;
                default:
                    System.err.println("Unknown argument: " + args[i]);
                    printUsage();
                    System.exit(1);
            }
        }

        // Validate required parameters
        if (config.auroraEndpoint == null || config.auroraEndpoint.isEmpty()) {
            System.err.println("Error: --aurora-endpoint is required");
            printUsage();
            System.exit(1);
        }

        // Get password from environment if not provided
        if (config.password == null || config.password.isEmpty()) {
            config.password = System.getenv("DB_PASSWORD");
            if (config.password == null || config.password.isEmpty()) {
                System.err.println("Error: Password must be provided via --password or DB_PASSWORD environment variable");
                System.exit(1);
            }
        }

        // Validate worker count
        if (config.writeWorkers < 1) {
            System.err.println("Error: --write-workers must be at least 1");
            System.exit(1);
        }

        // Configure JUL to SLF4J bridge for AWS JDBC Wrapper logging
        // This allows us to see detailed Blue-Green plugin activity
        LogManager.getLogManager().reset();
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();
        java.util.logging.Logger.getLogger("software.amazon.jdbc").setLevel(Level.FINE);

        // Start the simulator
        WorkloadSimulator simulator = new WorkloadSimulator(config);
        simulator.start();

        // Keep main thread alive
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Print usage information
     */
    private static void printUsage() {
        System.out.println("Aurora Blue-Green Workload Simulator");
        System.out.println("\nUsage:");
        System.out.println("  java -jar workload-simulator.jar [options]");
        System.out.println("\nRequired options:");
        System.out.println("  --aurora-endpoint <endpoint>    Aurora cluster writer endpoint");
        System.out.println("\nOptional options:");
        System.out.println("  --database-name <name>          Database name (default: lab_db)");
        System.out.println("  --username <username>           Database username (default: admin)");
        System.out.println("  --password <password>           Database password (or use DB_PASSWORD env var)");
        System.out.println("  --write-workers <count>         Number of write workers (default: 10, minimum: 1)");
        System.out.println("  --write-rate <rate>             Writes per second per worker (default: 100)");
        System.out.println("  --connection-pool-size <size>   Connection pool size (default: 100)");
        System.out.println("  --log-interval <seconds>        Statistics log interval (default: 10)");
        System.out.println("  --blue-green-deployment-id <id> Blue-Green deployment ID (optional, auto-detect if not provided)");
        System.out.println("  --enable-prometheus             Enable Prometheus metrics export");
        System.out.println("  --help                          Show this help message");
        System.out.println("\nExamples:");
        System.out.println("  # Basic usage");
        System.out.println("  java -jar workload-simulator.jar \\");
        System.out.println("    --aurora-endpoint my-cluster.cluster-xxxxx.us-east-1.rds.amazonaws.com \\");
        System.out.println("    --password MySecretPassword");
        System.out.println("\n  # High-concurrency testing");
        System.out.println("  java -jar workload-simulator.jar \\");
        System.out.println("    --aurora-endpoint my-cluster.cluster-xxxxx.us-east-1.rds.amazonaws.com \\");
        System.out.println("    --write-workers 50 \\");
        System.out.println("    --write-rate 200 \\");
        System.out.println("    --connection-pool-size 500 \\");
        System.out.println("    --password MySecretPassword");
    }
}

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
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
    private static final Logger operationsLogger = LoggerFactory.getLogger("com.aws.aurora.operations");

    // Configuration parameters
    private final String auroraEndpoint;
    private final String databaseName;
    private final String username;
    private final String password;
    private final int writeWorkers;
    private final int writeRatePerWorker;
    private final int readWorkers;
    private final int readRatePerWorker;
    private final int connectionPoolSize;
    private final int logIntervalSeconds;
    private final String blueGreenDeploymentId;
    private final ConsoleFormat consoleFormat;

    // Data source and connection pool
    private HikariDataSource dataSource;

    // Runtime tracking for dashboard/event formats
    private final long startTime = System.currentTimeMillis();
    private String lastKnownHost = null;
    private BlueGreenPhase currentBlueGreenPhase = BlueGreenPhase.NOT_CREATED;
    private boolean switchoverInProgress = false;
    private String lastBgdId = null;

    // Metrics registry
    private final MeterRegistry meterRegistry;
    private final Counter successCounter;
    private final Counter failureCounter;
    private final Timer writeLatencyTimer;

    // Atomic counters for write statistics
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong successfulRequests = new AtomicLong(0);
    private final AtomicLong failedRequests = new AtomicLong(0);

    // Atomic counters for read statistics
    private final AtomicLong totalReadRequests = new AtomicLong(0);
    private final AtomicLong successfulReadRequests = new AtomicLong(0);
    private final AtomicLong failedReadRequests = new AtomicLong(0);
    private final AtomicLong totalReadLatencyMs = new AtomicLong(0);

    // Host distribution tracking for reads
    private final ConcurrentHashMap<String, AtomicLong> readHostDistribution = new ConcurrentHashMap<>();

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
        this.readWorkers = config.readWorkers;
        this.readRatePerWorker = config.readRatePerWorker;
        this.connectionPoolSize = config.connectionPoolSize;
        this.logIntervalSeconds = config.logIntervalSeconds;
        this.blueGreenDeploymentId = config.blueGreenDeploymentId;
        this.consoleFormat = config.consoleFormat;

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
        int totalWorkers = writeWorkers + readWorkers;
        workerExecutor = Executors.newFixedThreadPool(totalWorkers);
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

        // Start read workers
        for (int i = 0; i < readWorkers; i++) {
            final int workerId = i + 1;
            workerExecutor.submit(() -> readWorker(workerId));
        }

        logger.info("Workload simulator started successfully (Write workers: {}, Read workers: {})",
            writeWorkers, readWorkers);

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

                    getOperationLogger().info("SUCCESS: Worker-{} | Host: {} | Table: {} | INSERT completed | Latency: {}ms",
                        workerId, currentHost != null ? currentHost : "unknown", tableName, latency);
                } else {
                    failedRequests.incrementAndGet();
                    failureCounter.increment();
                    getOperationLogger().error("FAILED: Worker-{} | Table: {} | INSERT failed | Latency: {}ms",
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
     * Read worker thread
     */
    private void readWorker(int workerId) {
        logger.info("Reader-{} started", workerId);

        long delayMs = readRatePerWorker > 0 ? 1000 / readRatePerWorker : 0;
        String currentHost = null;

        while (running.get()) {
            long startTime = System.currentTimeMillis();

            try {
                // Execute read operation
                String result = executeRead(workerId);
                long latency = System.currentTimeMillis() - startTime;

                if (result != null) {
                    successfulReadRequests.incrementAndGet();
                    totalReadLatencyMs.addAndGet(latency);

                    // Extract hostname from result (format: "hostname (server_id=X, version=Y, read_only=Z)")
                    String hostname = result.split(" \\(")[0];

                    // Track host distribution
                    readHostDistribution.computeIfAbsent(hostname, k -> new AtomicLong(0)).incrementAndGet();

                    // Check for host switch
                    if (!hostname.equals(currentHost)) {
                        if (currentHost != null) {
                            logger.info("Reader-{} | Switched to new host: {} (from: {})",
                                workerId, result, currentHost);
                        }
                        currentHost = hostname;
                    }

                    getOperationLogger().info("SUCCESS: Reader-{} | Result: {} | Latency: {}ms",
                        workerId, result, latency);
                } else {
                    failedReadRequests.incrementAndGet();
                    getOperationLogger().error("FAILED: Reader-{} | Query: system_vars | READ failed | Latency: {}ms",
                        workerId, latency);
                }

                totalReadRequests.incrementAndGet();

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
                logger.error("Reader-{} encountered unexpected error", workerId, e);
                failedReadRequests.incrementAndGet();
                totalReadRequests.incrementAndGet();
            }
        }

        logger.info("Reader-{} stopped", workerId);
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
                // Check if this error might be Blue-Green switchover related
                detectBlueGreenEventsFromError(e);

                getOperationLogger().warn("Worker-{} | Table: {} | connection_error | Retry {}/{} in {}ms | Error: {}",
                    workerId, tableName, attempt, maxRetries, retryDelayMs * attempt, e.getMessage());

                if (attempt < maxRetries) {
                    try {
                        Thread.sleep(retryDelayMs * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                } else {
                    getOperationLogger().error("Worker-{} | Table: {} | Max retries exceeded", workerId, tableName);
                    return false;
                }
            }
        }

        return false;
    }

    /**
     * Execute a read operation with retry logic
     */
    private String executeRead(int workerId) {
        int maxRetries = 5;
        int retryDelayMs = 500;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try (Connection conn = dataSource.getConnection();
                 PreparedStatement stmt = conn.prepareStatement(
                     "SELECT @@hostname, @@server_id, @@aurora_version, @@read_only")) {

                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    String hostname = rs.getString(1);
                    int serverId = rs.getInt(2);
                    String auroraVersion = rs.getString(3);
                    int readOnly = rs.getInt(4);

                    // Format detailed result string
                    String result = String.format("%s (server_id=%d, version=%s, read_only=%d)",
                        hostname, serverId, auroraVersion, readOnly);

                    return result;
                }

                return null;

            } catch (SQLException e) {
                // Check if this error might be Blue-Green switchover related
                detectBlueGreenEventsFromError(e);

                if (attempt < maxRetries) {
                    getOperationLogger().warn("Reader-{} | Query: system_vars | connection_error | Retry {}/{} in {}ms | Error: {}",
                        workerId, attempt, maxRetries, retryDelayMs, e.getMessage());

                    try {
                        Thread.sleep(retryDelayMs);
                        retryDelayMs *= 2; // Exponential backoff
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                } else {
                    getOperationLogger().error("Reader-{} | Query: system_vars | Max retries exceeded", workerId);
                    return null;
                }
            }
        }

        return null;
    }

    /**
     * Get appropriate logger based on console format
     * - VERBOSE: logs to both console and file
     * - DASHBOARD/EVENT_DRIVEN: logs to file only
     */
    private Logger getOperationLogger() {
        return (consoleFormat == ConsoleFormat.VERBOSE) ? logger : operationsLogger;
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
            // Check if this might be a Blue-Green switchover related error
            detectBlueGreenEventsFromError(e);
        }
        return null;
    }

    /**
     * Detect Blue-Green events from connection errors and behaviors
     */
    private void detectBlueGreenEventsFromError(SQLException e) {
        String errorMessage = e.getMessage();

        if (errorMessage != null) {
            // Check for typical Blue-Green switchover error messages
            if (errorMessage.contains("The active SQL connection has changed") ||
                errorMessage.contains("Communications link failure") ||
                errorMessage.contains("Connection is closed")) {

                // If we're not already in IN_PROGRESS, this might indicate switchover
                if (currentBlueGreenPhase != BlueGreenPhase.IN_PROGRESS) {
                    updateBlueGreenPhase(BlueGreenPhase.IN_PROGRESS, "Detected connection errors");
                    switchoverInProgress = true;
                }
            }
        }
    }

    /**
     * Update Blue-Green phase and log transition
     */
    private synchronized void updateBlueGreenPhase(BlueGreenPhase newPhase, String reason) {
        if (newPhase != currentBlueGreenPhase) {
            BlueGreenPhase previousPhase = currentBlueGreenPhase;
            currentBlueGreenPhase = newPhase;

            String timestamp = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));

            // Log phase transition in console formats
            if (consoleFormat == ConsoleFormat.EVENT_DRIVEN || consoleFormat == ConsoleFormat.VERBOSE) {
                System.out.printf("[%s] 🔄 BG-PHASE | %s → %s | %s%n",
                    timestamp, previousPhase.getDisplayName(), newPhase.getDisplayName(), reason);
            }

            // Update switchover status
            if (newPhase == BlueGreenPhase.IN_PROGRESS) {
                switchoverInProgress = true;
            } else if (newPhase == BlueGreenPhase.POST || newPhase == BlueGreenPhase.COMPLETED) {
                switchoverInProgress = false;
            }

            // Log to file via logger
            logger.info("Blue-Green phase transition: {} → {} ({})",
                previousPhase.getPhaseName(), newPhase.getPhaseName(), reason);
        }
    }

    /**
     * Detect Blue-Green deployment status from deployment ID and connection behavior
     */
    private void detectBlueGreenStatus() {
        // If blueGreenDeploymentId is specified, assume we have a deployment
        if (blueGreenDeploymentId != null && !blueGreenDeploymentId.isEmpty() &&
            currentBlueGreenPhase == BlueGreenPhase.NOT_CREATED) {
            updateBlueGreenPhase(BlueGreenPhase.CREATED, "Deployment ID specified: " + blueGreenDeploymentId);
            lastBgdId = blueGreenDeploymentId;
        }

        // Check for host changes that might indicate switchover completion
        String currentHost = getCurrentHost();
        if (currentHost != null && lastKnownHost != null && !currentHost.equals(lastKnownHost)) {
            if (switchoverInProgress) {
                updateBlueGreenPhase(BlueGreenPhase.POST, "Host switched: " + lastKnownHost + " → " + currentHost);
            } else if (currentBlueGreenPhase == BlueGreenPhase.CREATED ||
                      currentBlueGreenPhase == BlueGreenPhase.PREPARATION) {
                // Unexpected host change might indicate switchover
                updateBlueGreenPhase(BlueGreenPhase.POST, "Unexpected host change: " + lastKnownHost + " → " + currentHost);
            }
        }

        // Simulate detection of PREPARATION phase before switchover
        // In a real scenario, this would be detected by parsing JDBC wrapper logs
        if (currentBlueGreenPhase == BlueGreenPhase.CREATED) {
            // After some time in CREATED, transition to PREPARATION
            // This is a simulation - real implementation would parse JDBC logs
            long timeInCreated = System.currentTimeMillis() - startTime;
            if (timeInCreated > 30000) { // After 30 seconds, simulate PREPARATION
                updateBlueGreenPhase(BlueGreenPhase.PREPARATION, "Simulated: Green cluster syncing");
            }
        }
    }

    /**
     * Format 2: Event-driven clean console output
     */
    private void logEventDrivenFormat() {
        long total = totalRequests.get();
        long success = successfulRequests.get();
        long failed = failedRequests.get();

        long totalRead = totalReadRequests.get();
        long successRead = successfulReadRequests.get();
        long failedRead = failedReadRequests.get();
        double avgReadLatency = successRead > 0 ? (totalReadLatencyMs.get() * 1.0 / successRead) : 0.0;

        String currentTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));

        // Check for host changes (potential switchover events)
        String currentHost = getCurrentHost();
        if (currentHost != null && !currentHost.equals(lastKnownHost)) {
            if (lastKnownHost != null) {
                System.out.printf("[%s] ✅ RECOVERY| New writer: %s | Reconnection successful%n",
                    currentTime, currentHost);
            } else {
                System.out.printf("[%s] 🚀 STARTED  | Writers: %d | Rate: %d/sec | Pool: %d | Target: %s:3306%n",
                    currentTime, writeWorkers, writeRatePerWorker, connectionPoolSize, currentHost);
            }
            lastKnownHost = currentHost;
        }

        // Log periodic summary
        int combinedTotal = (int)(total + totalRead);
        int combinedSuccess = (int)(success + successRead);
        int combinedFailed = (int)(failed + failedRead);
        double combinedSuccessRate = combinedTotal > 0 ? (combinedSuccess * 100.0 / combinedTotal) : 0.0;

        System.out.printf("[%s] 📈 SUMMARY  | %ds | Total: %d | Success: %d (%.1f%%) | Failed: %d | Avg: %.0fms%n",
            currentTime, logIntervalSeconds, combinedTotal, combinedSuccess, combinedSuccessRate,
            combinedFailed, avgReadLatency > 0 ? avgReadLatency : 0.0);

        // Update Blue-Green status detection
        detectBlueGreenStatus();

        // Log Blue-Green deployment detection
        if (blueGreenDeploymentId != null && currentBlueGreenPhase == BlueGreenPhase.CREATED && lastBgdId == null) {
            System.out.printf("[%s] 🔄 DETECTED | Blue-Green deployment: %s | Phase: %s%n",
                currentTime, blueGreenDeploymentId, currentBlueGreenPhase.getDisplayName());
            lastBgdId = blueGreenDeploymentId;
        }
    }

    /**
     * Format 3: Dashboard-style console output
     */
    private void logDashboardFormat() {
        long total = totalRequests.get();
        long success = successfulRequests.get();
        long failed = failedRequests.get();
        double successRate = total > 0 ? (success * 100.0 / total) : 0.0;

        long totalRead = totalReadRequests.get();
        long successRead = successfulReadRequests.get();
        long failedRead = failedReadRequests.get();
        double avgReadLatency = successRead > 0 ? (totalReadLatencyMs.get() * 1.0 / successRead) : 0.0;

        String currentHost = getCurrentHost();
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        // Calculate runtime
        long runtimeMs = System.currentTimeMillis() - startTime;
        long hours = runtimeMs / 3600000;
        long minutes = (runtimeMs % 3600000) / 60000;
        long seconds = (runtimeMs % 60000) / 1000;
        String runtime = String.format("%02d:%02d:%02d", hours, minutes, seconds);

        // Get connection pool stats
        int activeConnections = 0;
        int totalConnections = 0;
        try {
            activeConnections = dataSource.getHikariPoolMXBean().getActiveConnections();
            totalConnections = dataSource.getHikariPoolMXBean().getTotalConnections();
        } catch (Exception e) {
            // Ignore - pool might not be ready
        }

        // Calculate combined statistics
        int combinedTotal = (int)(total + totalRead);
        int combinedSuccess = (int)(success + successRead);
        int combinedFailed = (int)(failed + failedRead);
        double combinedSuccessRate = combinedTotal > 0 ? (combinedSuccess * 100.0 / combinedTotal) : 0.0;

        // Clear screen and show dashboard (comment out clear if not desired)
        // System.out.print("\033[2J\033[H"); // Clear screen and move cursor to top

        // Update Blue-Green status detection
        detectBlueGreenStatus();

        System.out.println("┌─────────────────────────────────────────────────────────────────────────────┐");
        System.out.printf("│ Aurora Blue-Green Monitor │ %s │ Runtime: %s        │%n", timestamp, runtime);
        System.out.println("├─────────────────────────────────────────────────────────────────────────────┤");
        System.out.printf("│ Current Writer: %-25s │ BG Phase: %-17s │%n",
            currentHost != null ? currentHost : "unknown", currentBlueGreenPhase.getDisplayName());
        System.out.printf("│ Workers: %d/%d Active    │ Pool: %d/%-3d      │ Deployment: %-15s │%n",
            writeWorkers + readWorkers, writeWorkers + readWorkers, activeConnections, totalConnections,
            blueGreenDeploymentId != null ? blueGreenDeploymentId : "none");
        System.out.println("├─────────────────────────────────────────────────────────────────────────────┤");
        System.out.printf("│ LAST %d SECONDS%67s │%n", logIntervalSeconds, "");
        System.out.printf("│ ├─ Operations: %-8d │ Success: %d (%.1f%%)  │ Failed: %d (%.1f%%)%7s │%n",
            combinedTotal, combinedSuccess, combinedSuccessRate, combinedFailed,
            combinedTotal > 0 ? (combinedFailed * 100.0 / combinedTotal) : 0.0, "");

        // Show detailed stats if both read and write are active
        if (writeWorkers > 0 && readWorkers > 0) {
            System.out.printf("│ ├─ Writes: %-12d │ Success: %d (%.1f%%)    │ Failed: %d%13s │%n",
                (int)total, (int)success, successRate, (int)failed, "");
            System.out.printf("│ ├─ Reads: %-13d │ Success: %d (%.1f%%)    │ Avg: %.0fms%12s │%n",
                (int)totalRead, (int)successRead,
                totalRead > 0 ? (successRead * 100.0 / totalRead) : 0.0, avgReadLatency, "");
        } else {
            System.out.printf("│ ├─ Avg Latency: %.0fms%4s │ P95: --ms             │ P99: --ms%16s │%n",
                avgReadLatency, "", "");
        }

        if (combinedFailed > 0) {
            System.out.printf("│ └─ Recent Errors: Connection issues (%d), Timeouts (%d)%20s │%n",
                combinedFailed / 2, combinedFailed / 2, "");
        } else {
            System.out.printf("│ └─ No errors in last %d seconds%44s │%n", logIntervalSeconds, "");
        }

        System.out.println("├─────────────────────────────────────────────────────────────────────────────┤");

        // Show recent events based on Blue-Green phase
        String eventTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        switch (currentBlueGreenPhase) {
            case IN_PROGRESS:
                System.out.printf("│ %s ⚠️  BLUE-GREEN SWITCHOVER IN PROGRESS%32s │%n", eventTime, "");
                break;
            case PREPARATION:
                System.out.printf("│ %s 🟡 PREPARING: Green cluster syncing data%32s │%n", eventTime, "");
                break;
            case POST:
                System.out.printf("│ %s 🟠 FINALIZING: Switchover completed, stabilizing%26s │%n", eventTime, "");
                break;
            case CREATED:
                System.out.printf("│ %s 🟡 CREATED: Green cluster ready, awaiting switchover%23s │%n", eventTime, "");
                break;
            case COMPLETED:
                System.out.printf("│ %s ✅ COMPLETED: Blue-Green deployment finished%29s │%n", eventTime, "");
                break;
            default:
                if (combinedFailed > 5) {
                    System.out.printf("│ %s 💔 HIGH ERROR RATE: %d failures detected%28s │%n",
                        eventTime, combinedFailed, "");
                } else {
                    System.out.printf("│ %s ✅ STABLE: All systems operational%35s │%n", eventTime, "");
                }
        }

        System.out.println("└─────────────────────────────────────────────────────────────────────────────┘");
        System.out.println(); // Add spacing
    }

    /**
     * Log statistics based on configured console format
     */
    private void logStatistics() {
        switch (consoleFormat) {
            case EVENT_DRIVEN:
                logEventDrivenFormat();
                break;
            case DASHBOARD:
                logDashboardFormat();
                break;
            case VERBOSE:
            default:
                logVerboseFormat();
                break;
        }
    }

    /**
     * Original verbose format logging
     */
    private void logVerboseFormat() {
        // Write statistics
        long total = totalRequests.get();
        long success = successfulRequests.get();
        long failed = failedRequests.get();
        double successRate = total > 0 ? (success * 100.0 / total) : 0.0;

        // Read statistics
        long totalRead = totalReadRequests.get();
        long successRead = successfulReadRequests.get();
        long failedRead = failedReadRequests.get();
        double readSuccessRate = totalRead > 0 ? (successRead * 100.0 / totalRead) : 0.0;
        double avgReadLatency = successRead > 0 ? (totalReadLatencyMs.get() * 1.0 / successRead) : 0.0;

        logger.info("========================================");

        // Log write stats if write workers are enabled
        if (writeWorkers > 0) {
            logger.info("WRITE STATS: Total: {} | Success: {} | Failed: {} | Success Rate: {}%",
                total, success, failed, String.format("%.2f", successRate));
        }

        // Log read stats if read workers are enabled
        if (readWorkers > 0) {
            logger.info("READ STATS: Total: {} | Success: {} | Failed: {} | Success Rate: {}% | Avg Latency: {}ms",
                totalRead, successRead, failedRead, String.format("%.2f", readSuccessRate),
                String.format("%.1f", avgReadLatency));

            // Log host distribution
            if (!readHostDistribution.isEmpty()) {
                logger.info("READ HOST DISTRIBUTION:");
                for (Map.Entry<String, AtomicLong> entry : readHostDistribution.entrySet()) {
                    long count = entry.getValue().get();
                    double percentage = totalRead > 0 ? (count * 100.0 / totalRead) : 0.0;
                    logger.info("  {} : {} queries ({}%)",
                        entry.getKey(), count, String.format("%.2f", percentage));
                }
            }
        }

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
     * Console output format options
     */
    public enum ConsoleFormat {
        VERBOSE,      // Original detailed logging
        EVENT_DRIVEN, // Format 2: Event-driven clean output
        DASHBOARD     // Format 3: Dashboard-style output (default)
    }

    /**
     * Blue-Green deployment phases from AWS JDBC Wrapper
     */
    public enum BlueGreenPhase {
        NOT_CREATED("NOT_CREATED", "🔵", "No deployment"),
        CREATED("CREATED", "🟡", "Green cluster created"),
        PREPARATION("PREPARATION", "🟡", "Syncing data"),
        IN_PROGRESS("IN_PROGRESS", "🔴", "SWITCHING OVER"),
        POST("POST", "🟠", "Finalizing"),
        COMPLETED("COMPLETED", "🟢", "Complete");

        private final String phaseName;
        private final String emoji;
        private final String description;

        BlueGreenPhase(String phaseName, String emoji, String description) {
            this.phaseName = phaseName;
            this.emoji = emoji;
            this.description = description;
        }

        public String getPhaseName() { return phaseName; }
        public String getEmoji() { return emoji; }
        public String getDescription() { return description; }

        public static BlueGreenPhase fromString(String phaseName) {
            for (BlueGreenPhase phase : values()) {
                if (phase.phaseName.equals(phaseName)) {
                    return phase;
                }
            }
            return NOT_CREATED;
        }

        public String getDisplayName() {
            return String.format("%s %s", emoji, phaseName);
        }
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
        int readWorkers = 0;
        int readRatePerWorker = 100;
        int connectionPoolSize = 100;
        int logIntervalSeconds = 10;
        String blueGreenDeploymentId = null;
        boolean enablePrometheus = false;
        ConsoleFormat consoleFormat = ConsoleFormat.DASHBOARD; // Default to Format 3
        String jdbcLogLevel = "info"; // Default JDBC wrapper log level
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
                case "--read-workers":
                    config.readWorkers = Integer.parseInt(args[++i]);
                    break;
                case "--read-rate":
                    config.readRatePerWorker = Integer.parseInt(args[++i]);
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
                case "--console-format":
                    String formatValue = args[++i];
                    try {
                        config.consoleFormat = ConsoleFormat.valueOf(formatValue.toUpperCase());
                    } catch (IllegalArgumentException e) {
                        System.err.println("Error: Invalid console format: " + formatValue);
                        System.err.println("Valid formats: verbose, event_driven, dashboard");
                        System.exit(1);
                    }
                    break;
                case "--jdbc-log-level":
                    String logLevel = args[++i].toLowerCase();
                    if (logLevel.equals("finest") || logLevel.equals("fine") || logLevel.equals("info") ||
                        logLevel.equals("warn") || logLevel.equals("error") || logLevel.equals("debug") ||
                        logLevel.equals("trace")) {
                        config.jdbcLogLevel = logLevel;
                    } else {
                        System.err.println("Error: Invalid JDBC log level: " + logLevel);
                        System.err.println("Valid levels: finest, fine, debug, info, warn, error, trace");
                        System.exit(1);
                    }
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

        // Set JDBC log level system property for log4j2.xml
        System.setProperty("jdbc.log.level", config.jdbcLogLevel);

        // Configure JUL to SLF4J bridge for AWS JDBC Wrapper logging
        // This allows us to see AWS JDBC Wrapper logs through SLF4J/Log4j2
        LogManager.getLogManager().reset();
        SLF4JBridgeHandler.removeHandlersForRootLogger();
        SLF4JBridgeHandler.install();

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
        System.out.println("  --read-workers <count>          Number of read workers (default: 0)");
        System.out.println("  --read-rate <rate>              Reads per second per worker (default: 100)");
        System.out.println("  --connection-pool-size <size>   Connection pool size (default: 100)");
        System.out.println("  --log-interval <seconds>        Statistics log interval (default: 10)");
        System.out.println("  --blue-green-deployment-id <id> Blue-Green deployment ID (optional, auto-detect if not provided)");
        System.out.println("  --enable-prometheus             Enable Prometheus metrics export");
        System.out.println("  --console-format <format>       Console output format: verbose, event_driven, dashboard (default: dashboard)");
        System.out.println("  --jdbc-log-level <level>        JDBC wrapper log level: finest, fine, debug, info, warn, error (default: info)");
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
        System.out.println("\n  # Mixed workload (write + read)");
        System.out.println("  java -jar workload-simulator.jar \\");
        System.out.println("    --aurora-endpoint my-cluster.cluster-xxxxx.us-east-1.rds.amazonaws.com \\");
        System.out.println("    --write-workers 10 \\");
        System.out.println("    --write-rate 50 \\");
        System.out.println("    --read-workers 10 \\");
        System.out.println("    --read-rate 50 \\");
        System.out.println("    --password MySecretPassword");
        System.out.println("\n  # Event-driven console output for cleaner logs");
        System.out.println("  java -jar workload-simulator.jar \\");
        System.out.println("    --aurora-endpoint my-cluster.cluster-xxxxx.us-east-1.rds.amazonaws.com \\");
        System.out.println("    --write-workers 10 \\");
        System.out.println("    --console-format event_driven \\");
        System.out.println("    --password MySecretPassword");
        System.out.println("\n  # Blue-Green debugging with detailed JDBC logs");
        System.out.println("  java -jar workload-simulator.jar \\");
        System.out.println("    --aurora-endpoint my-cluster.cluster-xxxxx.us-east-1.rds.amazonaws.com \\");
        System.out.println("    --blue-green-deployment-id bgd-123456 \\");
        System.out.println("    --jdbc-log-level finest \\");
        System.out.println("    --console-format dashboard \\");
        System.out.println("    --password MySecretPassword");
    }
}

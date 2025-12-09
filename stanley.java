import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Multi-Thread Blue/Green Switchover Test with HikariCP (20 Threads)
 * 
 * 测试场景：
 * 1. 20个线程，每个线程执行 SELECT SLEEP(3600) 等待 Blue/Green 切换
 * 2. 遇到 failover exception 后立即在同一线程进行持续写入
 * 3. 每个线程写入独立的表
 * 4. 使用 HikariCP 连接池
 * 5. 目标：触发并重现 read-only error
 * 
 * Wrapper Plugins:
 * - initialConnection: 初始化连接
 * - auroraConnectionTracker: 跟踪连接状态
 * - failover2: 故障转移
 * - efm2: 增强故障监控
 * - bg: Blue/Green 切换检测
 */
public class MultiThreadBlueGreenTest {
    
    private static final String CLUSTER_ENDPOINT = System.getenv("CLUSTER_ENDPOINT");
    private static final String DB_USER = System.getenv("DB_USER");
    private static final String DB_PASSWORD = System.getenv("DB_PASSWORD");
    private static final String DB_NAME = System.getenv("DB_NAME");
    
    private static final int NUM_THREADS = 20; // 20个线程
    private static final int WRITE_INTERVAL_MS = 1000; // 每1秒写入一次
    private static final int MAX_WRITES_AFTER_FAILOVER = 300; // 最多写入300次（5分钟）
    
    private static final AtomicInteger totalWrites = new AtomicInteger(0);
    private static final AtomicInteger successfulWrites = new AtomicInteger(0);
    private static final AtomicInteger failedWrites = new AtomicInteger(0);
    private static final AtomicInteger readOnlyErrors = new AtomicInteger(0);
    private static final AtomicLong totalLatency = new AtomicLong(0);
    private static final AtomicInteger failoverCount = new AtomicInteger(0);
    private static final AtomicBoolean testCompleted = new AtomicBoolean(false);
    
    private static final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    
    public static void main(String[] args) throws Exception {
        setupLogging();
        setupWrapperLogging();
        validateEnvironment();
        
        System.out.println("╔════════════════════════════════════════════════════════════════╗");
        System.out.println("║   Multi-Thread Blue/Green Switchover Test (HikariCP - 20T)    ║");
        System.out.println("╚════════════════════════════════════════════════════════════════╝");
        System.out.println();
        printConfiguration();
        
        HikariDataSource dataSource = null;
        
        try {
            // 创建 HikariCP 数据源
            dataSource = createDataSource();
            
            // 提前创建所有测试表
            createAllTestTables(dataSource);
            
            // 运行多线程测试
            runMultiThreadTest(dataSource);
            
        } finally {
            if (dataSource != null) {
                dataSource.close();
                System.out.println("\n🔌 DataSource closed");
            }
            
            printFinalReport();
        }
    }
    
    private static void setupLogging() {
        System.setProperty("java.util.logging.SimpleFormatter.format",
            "[%1$tF %1$tT] [%4$-7s] %5$s %n");
    }
    
    private static void setupWrapperLogging() {
        // 配置日志级别 - 启用所有插件的详细日志
        System.setProperty("software.amazon.jdbc.level", "FINE");
        
        // 配置 Java Logging
        java.util.logging.Logger rootLogger = java.util.logging.Logger.getLogger("");
        java.util.logging.Handler[] handlers = rootLogger.getHandlers();
        for (java.util.logging.Handler handler : handlers) {
            handler.setLevel(java.util.logging.Level.FINE);
        }
        
        // 配置各个插件的日志级别
        java.util.logging.Logger.getLogger("software.amazon.jdbc.plugin.initialconnection").setLevel(java.util.logging.Level.FINE);
        java.util.logging.Logger.getLogger("software.amazon.jdbc.plugin.auroraconnectiontracker").setLevel(java.util.logging.Level.FINE);
        java.util.logging.Logger.getLogger("software.amazon.jdbc.plugin.bluegreen").setLevel(java.util.logging.Level.FINE);
        java.util.logging.Logger.getLogger("software.amazon.jdbc.plugin.failover2").setLevel(java.util.logging.Level.FINE);
        java.util.logging.Logger.getLogger("software.amazon.jdbc.plugin.efm2").setLevel(java.util.logging.Level.FINE);
        java.util.logging.Logger.getLogger("software.amazon.jdbc").setLevel(java.util.logging.Level.FINE);
        
        System.out.println("🔍 Wrapper Logging Configuration:");
        System.out.println("   software.amazon.jdbc: FINE");
        System.out.println("   initialConnection plugin: FINE");
        System.out.println("   auroraConnectionTracker plugin: FINE");
        System.out.println("   bluegreen plugin: FINE");
        System.out.println("   failover2 plugin: FINE");
        System.out.println("   efm2 plugin: FINE");
        System.out.println();
    }
    
    private static void validateEnvironment() {
        if (CLUSTER_ENDPOINT == null || DB_USER == null || DB_PASSWORD == null || DB_NAME == null) {
            System.err.println("❌ Missing required environment variables:");
            System.err.println("   CLUSTER_ENDPOINT, DB_USER, DB_PASSWORD, DB_NAME");
            System.exit(1);
        }
    }
    
    private static void printConfiguration() {
        System.out.println("📋 Test Configuration:");
        System.out.println("   Endpoint: " + CLUSTER_ENDPOINT);
        System.out.println("   Database: " + DB_NAME);
        System.out.println("   Number of Threads: " + NUM_THREADS);
        System.out.println("   Write Interval: " + (WRITE_INTERVAL_MS / 1000) + " second(s)");
        System.out.println("   Max Writes After Failover: " + MAX_WRITES_AFTER_FAILOVER + " per thread");
        System.out.println();
    }
    
    private static HikariDataSource createDataSource() {
        HikariConfig config = new HikariConfig();
        
        // 构建 JDBC URL，包含所有必需的插件和参数
        String jdbcUrl = String.format(
            "jdbc:aws-wrapper:mysql://%s/%s?" +
            //"wrapperPlugins=initialConnection,auroraConnectionTracker,failover2,efm2,bg&" +
            "wrapperPlugins=initialConnection,auroraConnectionTracker,failover2,efm2,bg",
            //"clusterId=1",
            //"wrapperPlugins=auroraConnectionTracker,failover2,efm2,bg",
            CLUSTER_ENDPOINT, DB_NAME
        );
        
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(DB_USER);
        config.setPassword(DB_PASSWORD);
        config.setDriverClassName("software.amazon.jdbc.Driver");
        
        // 连接池配置 - 优化以支持多线程长连接
        config.setMaximumPoolSize(NUM_THREADS + 2);  // 稍微多一点以支持并发
        config.setMinimumIdle(NUM_THREADS);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(0);  // 禁用空闲超时
        config.setMaxLifetime(0);  // 禁用最大生命周期
        config.setKeepaliveTime(0);  // 禁用keepalive
        
        // 连接测试
        config.setConnectionTestQuery("SELECT 1");
        
        System.out.println("🔧 HikariCP Configuration:");
        System.out.println("   JDBC URL: " + jdbcUrl);
        System.out.println();
        System.out.println("🔌 Wrapper Plugins:");
        System.out.println("   1. initialConnection - 初始化连接");
        System.out.println("   2. auroraConnectionTracker - 跟踪连接状态");
        System.out.println("   3. failover2 - 故障转移");
        System.out.println("   4. efm2 - 增强故障监控");
        System.out.println("   5. bg - Blue/Green 切换检测");
        System.out.println();
        System.out.println("📌 Parameters:");
        System.out.println("   clusterId: 1");
        System.out.println();
        System.out.println("🏊 Connection Pool:");
        System.out.println("   MaximumPoolSize: " + config.getMaximumPoolSize());
        System.out.println("   MinimumIdle: " + config.getMinimumIdle());
        System.out.println("   ConnectionTimeout: " + config.getConnectionTimeout() + "ms");
        System.out.println("   IdleTimeout: " + config.getIdleTimeout() + "ms (0=disabled)");
        System.out.println("   MaxLifetime: " + config.getMaxLifetime() + "ms (0=disabled)");
        System.out.println("   KeepaliveTime: " + config.getKeepaliveTime() + "ms (0=disabled)");
        System.out.println();
        
        return new HikariDataSource(config);
    }
    
    private static void createAllTestTables(HikariDataSource dataSource) {
        System.out.println("📋 [" + now() + "] Creating test tables for all threads...");
        
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            
            // 为每个线程创建独立的表
            for (int i = 1; i <= NUM_THREADS; i++) {
                String tableName = "multi_thread_bg_test_" + i;
                
                // 删除已存在的表
                try {
                    stmt.executeUpdate("DROP TABLE IF EXISTS " + tableName);
                } catch (SQLException e) {
                    // Ignore
                }
                
                // 创建新表
                String createTableSQL = String.format(
                    "CREATE TABLE %s (" +
                    "  id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                    "  thread_id INT NOT NULL," +
                    "  operation_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP," +
                    "  endpoint VARCHAR(255)," +
                    "  phase VARCHAR(50)," +
                    "  test_data VARCHAR(500)," +
                    "  INDEX idx_thread_time (thread_id, operation_time)" +
                    ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4",
                    tableName
                );
                
                stmt.executeUpdate(createTableSQL);
                System.out.println("   ✅ Created table: " + tableName);
            }
            
            System.out.println("✅ [" + now() + "] All " + NUM_THREADS + " test tables created successfully");
            System.out.println();
            
        } catch (SQLException e) {
            System.err.println("❌ Failed to create test tables: " + e.getMessage());
            System.exit(1);
        }
    }
    
    /**
     * 运行多线程测试
     */
    private static void runMultiThreadTest(HikariDataSource dataSource) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(NUM_THREADS);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch completionLatch = new CountDownLatch(NUM_THREADS);
        
        System.out.println("╔════════════════════════════════════════════════════════════════╗");
        System.out.println("║  PHASE 1: All threads executing SELECT SLEEP(3600)           ║");
        System.out.println("║           Waiting for Blue/Green switchover...                ║");
        System.out.println("╚════════════════════════════════════════════════════════════════╝");
        System.out.println();
        
        // 启动所有线程
        for (int i = 1; i <= NUM_THREADS; i++) {
            final int threadId = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    runThreadTest(dataSource, threadId);
                } catch (Exception e) {
                    System.err.println("❌ Thread-" + threadId + " error: " + e.getMessage());
                } finally {
                    completionLatch.countDown();
                }
            });
        }
        
        // 启动所有线程
        System.out.println("🚀 [" + now() + "] Starting " + NUM_THREADS + " threads...");
        System.out.println();
        startLatch.countDown();
        
        // 等待所有线程完成或超时（最多等待2小时）
        boolean completed = completionLatch.await(2, TimeUnit.HOURS);
        
        testCompleted.set(true);
        executor.shutdownNow();
        
        if (!completed) {
            System.out.println("\n⚠️  [" + now() + "] Test timeout - some threads may still be running");
        } else {
            System.out.println("\n✅ [" + now() + "] All threads completed");
        }
    }
    
    /**
     * 单个线程的测试流程
     */
    private static void runThreadTest(HikariDataSource dataSource, int threadId) {
        Connection conn = null;
        
        try {
            // 从连接池获取连接
            conn = dataSource.getConnection();
            String endpoint = getEndpointInfo(conn);
            System.out.println(String.format("✅ [%s] Thread-%d got connection from %s",
                now(), threadId, endpoint));
            
            // 阶段1：执行长时间 SELECT SLEEP 等待 Blue/Green 切换
            boolean failoverDetected = executeLongSleep(conn, threadId);
            
            // 关闭 SLEEP 使用的连接
            if (conn != null) {
                try {
                    conn.close();
                    System.out.println(String.format("🔌 [%s] Thread-%d SLEEP connection returned to pool",
                        now(), threadId));
                } catch (SQLException e) {
                    System.err.println(String.format("⚠️  [%s] Thread-%d error closing SLEEP connection: %s",
                        now(), threadId, e.getMessage()));
                }
                conn = null;
            }
            
            if (failoverDetected) {
                // 阶段2：Failover 后立即开始持续写入（使用连接池）
                System.out.println(String.format("\n╔════════════════════════════════════════════════════════════════╗"));
                System.out.println(String.format("║  Thread-%d: Starting continuous writes after failover        ║", threadId));
                System.out.println(String.format("╚════════════════════════════════════════════════════════════════╝"));
                System.out.println();
                
                executeContinuousWrites(dataSource, threadId);
            } else {
                System.out.println(String.format("\n⚠️  [%s] Thread-%d: No failover detected during SLEEP",
                    now(), threadId));
            }
            
        } catch (SQLException e) {
            System.err.println(String.format("❌ [%s] Thread-%d connection error: %s",
                now(), threadId, e.getMessage()));
        } finally {
            // 确保连接被关闭
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    // Ignore
                }
            }
        }
    }
    
    /**
     * 阶段1：执行长时间 SELECT SLEEP，等待 Blue/Green 切换触发 failover
     * 
     * @return true 如果检测到 failover，false 如果正常完成
     */
    private static boolean executeLongSleep(Connection conn, int threadId) {
        System.out.println(String.format("💤 [%s] Thread-%d: Starting SELECT SLEEP(3600)...",
            now(), threadId));
        
        String endpoint = getEndpointInfo(conn);
        System.out.println(String.format("📍 [%s] Thread-%d: Current endpoint: %s",
            now(), threadId, endpoint));
        
        try (Statement stmt = conn.createStatement()) {
            long startTime = System.currentTimeMillis();
            
            // 执行长时间 SLEEP
            stmt.executeQuery("SELECT SLEEP(3600)");
            
            long duration = System.currentTimeMillis() - startTime;
            System.out.println(String.format("✅ [%s] Thread-%d: SELECT SLEEP completed normally after %dms",
                now(), threadId, duration));
            System.out.println(String.format("   Thread-%d: No failover detected", threadId));
            
            return false;
            
        } catch (SQLException e) {
            String errorMsg = e.getMessage();
            
            System.out.println(String.format("⚠️  [%s] Thread-%d: SELECT SLEEP interrupted by exception:",
                now(), threadId));
            System.out.println("   Error Code: " + e.getErrorCode());
            System.out.println("   SQL State: " + e.getSQLState());
            System.out.println("   Message: " + errorMsg);
            
            // 检查是否是 failover 相关的异常
            if (isFailoverException(e)) {
                failoverCount.incrementAndGet();
                
                System.out.println(String.format("🔄 [%s] Thread-%d: FAILOVER DETECTED!",
                    now(), threadId));
                System.out.println("   Blue/Green switchover has occurred");
                
                // 检查连接是否仍然有效
                try {
                    String newEndpoint = getEndpointInfo(conn);
                    System.out.println("   New endpoint: " + newEndpoint);
                    System.out.println("   Connection is still valid after failover");
                } catch (Exception ex) {
                    System.out.println("   Connection may be invalid: " + ex.getMessage());
                }
                
                System.out.println();
                return true;
            } else {
                System.err.println(String.format("❌ [%s] Thread-%d: Unexpected exception (not failover-related)",
                    now(), threadId));
                return false;
            }
        }
    }
    
    /**
     * 阶段2：Failover 后立即开始持续写入，尝试触发 read-only error
     * 每次写入都从连接池获取新连接
     */
    private static void executeContinuousWrites(HikariDataSource dataSource, int threadId) {
        System.out.println(String.format("✍️  [%s] Thread-%d: Starting continuous writes...",
            now(), threadId));
        System.out.println("   Target: " + MAX_WRITES_AFTER_FAILOVER + " writes");
        System.out.println("   Interval: " + WRITE_INTERVAL_MS + "ms");
        System.out.println("   Strategy: Get fresh connection from pool for each write");
        System.out.println();
        
        long startTime = System.currentTimeMillis();
        
        for (int i = 1; i <= MAX_WRITES_AFTER_FAILOVER; i++) {
            long writeStart = System.currentTimeMillis();
            
            boolean success = executeWrite(dataSource, threadId, i);
            
            long latency = System.currentTimeMillis() - writeStart;
            totalLatency.addAndGet(latency);
            
            if (success) {
                successfulWrites.incrementAndGet();
                
                if (i % 10 == 0) {
                    System.out.println(String.format("📊 [%s] Thread-%d: Progress: %d/%d writes completed",
                        now(), threadId, i, MAX_WRITES_AFTER_FAILOVER));
                }
            } else {
                failedWrites.incrementAndGet();
            }
            
            // 等待下一次写入
            if (i < MAX_WRITES_AFTER_FAILOVER) {
                try {
                    Thread.sleep(WRITE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    System.out.println(String.format("\n⚠️  [%s] Thread-%d: Write loop interrupted",
                        now(), threadId));
                    break;
                }
            }
        }
        
        long totalTime = System.currentTimeMillis() - startTime;
        System.out.println(String.format("\n✅ [%s] Thread-%d: Continuous writes completed",
            now(), threadId));
        System.out.println("   Total time: " + (totalTime / 1000) + " seconds");
        System.out.println();
    }
    
    /**
     * 执行单次写入操作 - 从连接池获取新连接
     */
    private static boolean executeWrite(HikariDataSource dataSource, int threadId, int writeNumber) {
        totalWrites.incrementAndGet();
        
        Connection conn = null;
        try {
            // 从连接池获取新连接
            conn = dataSource.getConnection();
            
            String endpoint = getEndpointInfo(conn);
            String tableName = "multi_thread_bg_test_" + threadId;
            
            String sql = String.format(
                "INSERT INTO %s (thread_id, endpoint, phase, test_data) VALUES (?, ?, ?, ?)",
                tableName
            );
            
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setInt(1, threadId);
                pstmt.setString(2, endpoint);
                pstmt.setString(3, "AFTER_FAILOVER");
                pstmt.setString(4, "Thread-" + threadId + " Write #" + writeNumber + " at " + now());
                pstmt.executeUpdate();
            }
            
            return true;
            
        } catch (SQLException e) {
            String errorMsg = e.getMessage();
            
            // 检查是否是 read-only error
            if (errorMsg.contains("read-only") || errorMsg.contains("READ_ONLY") || 
                errorMsg.contains("read only") || e.getErrorCode() == 1290) {
                
                readOnlyErrors.incrementAndGet();
                
                System.err.println("\n╔════════════════════════════════════════════════════════════════╗");
                System.err.println("║  🎯 READ-ONLY ERROR DETECTED! 🎯                              ║");
                System.err.println("╚════════════════════════════════════════════════════════════════╝");
                System.err.println(String.format("[%s] Thread-%d Write #%d", now(), threadId, writeNumber));
                System.err.println("Error Code: " + e.getErrorCode());
                System.err.println("SQL State: " + e.getSQLState());
                System.err.println("Message: " + errorMsg);
                System.err.println();
                
                // 尝试获取当前端点信息
                if (conn != null) {
                    try {
                        String endpoint = getEndpointInfo(conn);
                        System.err.println("Current endpoint: " + endpoint);
                    } catch (Exception ex) {
                        System.err.println("Cannot get endpoint info: " + ex.getMessage());
                    }
                }
                System.err.println();
                
            } else {
                System.err.println(String.format("❌ [%s] Thread-%d Write #%d failed: %s",
                    now(), threadId, writeNumber, errorMsg));
            }
            
            return false;
        } finally {
            // 归还连接到池
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException e) {
                    // Ignore
                }
            }
        }
    }
    
    /**
     * 判断是否是 failover 相关的异常
     */
    private static boolean isFailoverException(SQLException e) {
        String msg = e.getMessage().toLowerCase();
        return msg.contains("failover") || 
               msg.contains("connection") ||
               msg.contains("communications link failure") ||
               msg.contains("lost connection") ||
               e.getErrorCode() == 1047 || // WSREP has not yet prepared node for application use
               e.getErrorCode() == 1053;   // Server shutdown in progress
    }
    
    /**
     * 获取当前连接的端点信息
     */
    private static String getEndpointInfo(Connection conn) {
        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(
                 "SELECT CONCAT(@@hostname, ':', @@port, ' [', IF(@@read_only=0, 'WRITER', 'READER'), ']') as info")) {
            if (rs.next()) {
                return rs.getString("info");
            }
        } catch (SQLException e) {
            return "unknown (error: " + e.getMessage() + ")";
        }
        return "unknown";
    }
    
    /**
     * 打印最终报告
     */
    private static void printFinalReport() {
        int total = totalWrites.get();
        int success = successfulWrites.get();
        int failed = failedWrites.get();
        int readOnlyCount = readOnlyErrors.get();
        int failovers = failoverCount.get();
        long avgLatency = total > 0 ? totalLatency.get() / total : 0;
        double successRate = total > 0 ? (success * 100.0 / total) : 0;
        
        System.out.println("\n╔════════════════════════════════════════════════════════════════╗");
        System.out.println("║                      FINAL REPORT                              ║");
        System.out.println("╚════════════════════════════════════════════════════════════════╝");
        System.out.println();
        System.out.println("🔄 Failover Statistics:");
        System.out.println("   Threads: " + NUM_THREADS);
        System.out.println("   Failovers Detected: " + failovers);
        System.out.println();
        System.out.println("📊 Write Statistics:");
        System.out.println("   Total Writes: " + total);
        System.out.println("   Successful: " + success);
        System.out.println("   Failed: " + failed);
        System.out.println("   Success Rate: " + String.format("%.2f%%", successRate));
        System.out.println();
        System.out.println("🎯 Read-Only Errors:");
        System.out.println("   Count: " + readOnlyCount);
        if (readOnlyCount > 0) {
            double readOnlyRate = (readOnlyCount * 100.0 / total);
            System.out.println("   Rate: " + String.format("%.2f%%", readOnlyRate));
        }
        System.out.println();
        System.out.println("⚡ Performance:");
        System.out.println("   Average Write Latency: " + avgLatency + "ms");
        System.out.println();
        
        if (readOnlyCount > 0) {
            System.out.println("🎯 TEST RESULT: READ-ONLY ERROR REPRODUCED!");
            System.out.println("   Successfully triggered " + readOnlyCount + " read-only error(s)");
        } else if (failed > 0) {
            System.out.println("⚠️  TEST RESULT: FAILURES DETECTED (but no read-only errors)");
        } else if (failovers > 0) {
            System.out.println("✅ TEST RESULT: FAILOVER HANDLED SUCCESSFULLY");
            System.out.println("   All writes successful after failover (no read-only errors)");
        } else {
            System.out.println("⚠️  TEST RESULT: NO FAILOVER DETECTED");
            System.out.println("   Blue/Green switchover may not have occurred during test");
        }
        System.out.println();
    }
    
    private static String now() {
        return LocalDateTime.now().format(formatter);
    }
}

# Aurora Blue-Green 部署运维手册

## 文档信息

**文档版本**: 1.0
**最后更新**: 2025-12-09
**作者**: Aurora Blue-Green Deployment Lab Project


---

## 目录

1. [概述](#概述)
2. [部署前检查清单](#部署前检查清单)
3. [部署准备](#部署准备)
4. [Blue-Green 部署创建](#blue-green-部署创建)
5. [切换执行](#切换执行)
6. [切换后验证](#切换后验证)
7. [成功标准](#成功标准)

---

## 概述

### 目的
本运维手册提供使用 AWS Advanced JDBC Wrapper 执行 Aurora MySQL Blue-Green 部署的分步说明，实现最小停机时间。

### 预期结果
- **停机时间**: 几秒钟，取决于真实工作负载
- **成功率**: 100%（自动重试）
- **失败事务**: 0 个永久失败
- **Worker 影响**: 部分 worker 在切换期间出现瞬时错误，由 JDBC wrapper 重试机制自动恢复（5 次尝试，500ms 退避）。

---

## 部署前检查清单

### 先决条件验证

####  0. AWS Advanced JDBC Wrapper 版本

**要求**: 应用程序必须使用 AWS Advanced JDBC Wrapper **版本 2.6.8 或更高版本**。

Blue-Green 插件在 2.6.0 版本中引入，2.6.8+ 版本中进行了稳定性改进。

**验证版本:**
```bash
# 检查 pom.xml 中的 Maven 依赖
grep -A 2 "aws-advanced-jdbc-wrapper" pom.xml

# 预期输出（最低版本）:
# <artifactId>aws-advanced-jdbc-wrapper</artifactId>
# <version>2.6.8</version>
```

**如需更新:**
```xml
<!-- pom.xml -->
<dependency>
  <groupId>software.amazon.jdbc</groupId>
  <artifactId>aws-advanced-jdbc-wrapper</artifactId>
  <version>2.6.8</version>
</dependency>
```

**功能验证实验使用版本**: 2.6.8

####  1. 数据库用户权限

**在 Blue 和 Green 集群上验证权限:**
```sql
-- 测试访问拓扑表
SELECT * FROM mysql.rds_topology LIMIT 1;
```

**预期结果**: 查询成功返回（即使为空）

**重要**: 这些权限必须在 Blue 和 Green 集群上都提前授予，切换前不应再撤销或修改。

**如果访问被拒绝 - 授予权限:**
```sql
-- 选项 A: 直接授权
GRANT SELECT ON mysql.rds_topology TO 'your_app_user'@'%';
FLUSH PRIVILEGES;

-- 选项 B: 使用角色（MySQL 8.0+，推荐）
CREATE ROLE IF NOT EXISTS 'bluegreen_reader';
GRANT SELECT ON mysql.rds_topology TO 'bluegreen_reader';
GRANT 'bluegreen_reader' TO 'your_app_user'@'%';
SET DEFAULT ROLE 'bluegreen_reader' TO 'your_app_user'@'%';
FLUSH PRIVILEGES;
```

####  2. 二进制日志配置

**检查二进制日志状态:**
```sql
SHOW VARIABLES LIKE 'binlog_format';
```

**预期结果**: `ROW`、`MIXED` 或 `STATEMENT`（推荐 ROW）

**如果未启用:**
1. 创建/修改数据库集群参数组
2. 设置 `binlog_format = ROW`
3. 将参数组关联到集群
4. **重启集群**（binlog 更改需要）

**验证集群参数组:**
```bash
aws rds describe-db-clusters \
  --db-cluster-identifier <cluster-name> \
  --query 'DBClusters[0].DBClusterParameterGroup'
```

####  3. 多线程复制（推荐）

**检查当前设置:**
```sql
SHOW VARIABLES LIKE 'replica_parallel_workers';
```

**推荐值:**
- Instances < 2xlarge: `0` (disabled)
- Instances 2xlarge - 8xlarge: `4`
- Instances > 8xlarge: `8-16`

**如需更新（无需重启）:**
```bash
# 更新数据库集群参数组
aws rds modify-db-cluster-parameter-group \
  --db-cluster-parameter-group-name <param-group-name> \
  --parameters "ParameterName=replica_parallel_workers,ParameterValue=4,ApplyMethod=immediate"
```

####  4. 应用程序配置

**验证 JDBC URL 包含 Blue-Green 插件:**
```
jdbc:aws-wrapper:mysql://<cluster-endpoint>:3306/<database>?wrapperPlugins=initialConnection,auroraConnectionTracker,bg,failover2,efm2
```

**注意**: 此示例仅显示必需的 `wrapperPlugins` 参数。请在实际连接字符串中保留任何其他现有的 JDBC 参数（例如 `connectTimeout`、`socketTimeout` 等）。

**关键参数检查清单:**
-  `wrapperPlugins=initialConnection,auroraConnectionTracker,bg,failover2,efm2`
  - `initialConnection`: 建立初始连接属性和验证
  - `auroraConnectionTracker`: 跟踪 Aurora 集群拓扑和连接状态
  - `bg` (Blue-Green): 监控 Blue-Green 部署状态以实现协调切换
  - `failover2`: 处理集群级故障转移场景（版本 2）
  - `efm2` (Enhanced Failure Monitoring v2): 主动连接健康监控


####  5. 日志配置（测试/预发布环境）

**启用 Blue-Green 插件的调试日志:**

**选项 1: 在 JDBC URL 中配置**
```
jdbc:aws-wrapper:mysql://<cluster-endpoint>:3306/<database>?wrapperPlugins=initialConnection,auroraConnectionTracker,bg,failover2,efm2&wrapperLoggerLevel=FINE
```

**选项 2: Java 应用程序代码**
```java
// Java 应用程序 - 添加到启动代码
java.util.logging.Logger.getLogger("software.amazon.jdbc.plugin.bluegreen").setLevel(Level.FINE);
```

**选项 3: Log4j2 配置**
```xml
<Logger name="software.amazon.jdbc.plugin.bluegreen" level="FINE" additivity="false">
  <AppenderRef ref="Console"/>
</Logger>
```

####  6. 监控设置

**要监控的 CloudWatch 指标:**
- `DatabaseConnections`（当前连接数）
- `CPUUtilization`（集群负载）
- `AuroraBinlogReplicaLag`（PREPARATION 期间的复制延迟）
- `ReadLatency` 和 `WriteLatency`（性能基线）

**要跟踪的应用程序指标:**
- 连接池使用率
- 事务成功/失败率
- P50、P95、P99 延迟

####  7. 通用最佳实践验证

在继续 Blue-Green 部署之前，验证以下最佳实践:

**连接策略:**
-  对所有连接使用集群端点、读取器端点或自定义端点
-  不要使用实例端点
-  不要使用带有静态或排除列表的自定义端点
-  确保无缝切换，无需更改连接字符串

**Green 环境使用:**
-  在切换之前保持 green 环境为只读
-  谨慎启用写入操作（可能导致复制冲突）
-  避免切换后可能成为生产数据的非预期数据

**模式更改兼容性:**
-  仅进行复制兼容的模式更改
-  **兼容**: 在表末尾添加新列
-  **不兼容**: 重命名列或表（破坏复制）
-  参考: [MySQL 不同表定义的复制](https://dev.mysql.com/doc/refman/8.0/en/replication-features-differing-tables.html)

---

## 部署准备

### 步骤 1: 部署带有 Blue-Green 插件的应用程序

**时间安排**: 切换前 1-2 小时

#### 操作 1.1: 更新应用程序配置
```bash
# 更新 JDBC URL 以包含 'bg' 插件
# 通过滚动重启部署更新的应用程序
```

#### 操作 1.2: 验证插件激活
**检查应用程序日志中的内容:**
```
[bgdId: '1'] BG status: NOT_CREATED
```

**等待 5-10 分钟**，让插件初始化并建立基线监控。

### 步骤 2: 建立性能基线

**时间安排**: 创建 Blue-Green 部署前 10 分钟

#### 操作 2.1: 收集当前指标
```bash
# 查询当前 Aurora 版本
mysql -h <cluster-endpoint> -u <user> -p<password> -e "SELECT @@aurora_version;"

# 收集基线指标
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

## Blue-Green 部署创建

### 步骤 3: 创建 Blue-Green 部署

**时间安排**: Green 集群就绪需要 15-60 分钟

#### 操作 3.1: 启动部署
```bash
aws rds create-blue-green-deployment \
  --blue-green-deployment-name <deployment-name> \
  --source-arn arn:aws:rds:<region>:<account-id>:cluster:<source-cluster-id> \
  --target-engine-version 8.0.mysql_aurora.3.10.0 \
  --tags Key=Environment,Value=production Key=Deployment,Value=blue-green-upgrade
```

**预期输出:**
```json
{
  "BlueGreenDeployment": {
    "BlueGreenDeploymentIdentifier": "bgd-xxxxxxxxxxxxx",
    "Status": "PROVISIONING"
  }
}
```


#### 操作 3.2: 监控部署进度
```bash
# 检查部署状态（每 5 分钟重复一次）
aws rds describe-blue-green-deployments \
  --blue-green-deployment-identifier <deployment-id> \
  --query 'BlueGreenDeployments[0].Status'
```

**预期状态进展:**
1. `PROVISIONING`（5-15 分钟）- 创建 Green 集群
2. `AVAILABLE`（15-45 分钟）- 复制数据
3. **准备好切换** 当状态 = `AVAILABLE` 且复制延迟 < 1 秒

#### 操作 3.3: 验证 Green 集群就绪
```bash
# 检查 Green 集群详细信息
aws rds describe-blue-green-deployments \
  --blue-green-deployment-identifier <deployment-id> \
  --query 'BlueGreenDeployments[0].Target'

# 监控复制延迟（应该 < 1 秒）
aws cloudwatch get-metric-statistics \
  --namespace AWS/RDS \
  --metric-name AuroraBinlogReplicaLag \
  --dimensions Name=DBClusterIdentifier,Value=<green-cluster-id> \
  --start-time $(date -u -d '5 minutes ago' +%Y-%m-%dT%H:%M:%S) \
  --end-time $(date -u +%Y-%m-%dT%H:%M:%S) \
  --period 60 \
  --statistics Average
```

**Green 集群就绪条件:**
-  状态 = `AVAILABLE`
-  复制延迟 < 1 秒
-  Green 集群端点可访问
-  应用程序日志显示: `[bgdId: '1'] BG status: CREATED`

#### 操作 3.4: 管理复制延迟（如适用）

**Aurora MySQL 最佳实践**: 如果 green 环境出现复制延迟，请考虑以下方法:

**选项 1: 临时禁用二进制日志保留**
```bash
# 如果不需要二进制日志保留，请临时禁用它
# 在数据库集群参数组中将 binlog_format 设置回 0
aws rds modify-db-cluster-parameter-group \
  --db-cluster-parameter-group-name <green-param-group-name> \
  --parameters "ParameterName=binlog_format,ParameterValue=OFF,ApplyMethod=immediate"

# 重启 green 写入器数据库实例
aws rds reboot-db-instance --db-instance-identifier <green-writer-instance-id>

# 复制赶上后重新启用
```

**选项 2: 临时调整 innodb_flush_log_at_trx_commit**
```bash
# 在 green 数据库参数组中将 innodb_flush_log_at_trx_commit 设置为 0
aws rds modify-db-parameter-group \
  --db-parameter-group-name <green-param-group-name> \
  --parameters "ParameterName=innodb_flush_log_at_trx_commit,ParameterValue=0,ApplyMethod=immediate"

# 重要: 在切换前恢复为默认值 1
# 警告: 如果使用此设置时发生意外关闭，请重建 green 环境
```

**参考**: [配置日志缓冲区刷新频率](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/AuroraMySQL.BestPractices.FeatureRecommendations.html#AuroraMySQL.BestPractices.Flush)

**监控复制延迟:**
- 每 1-2 分钟监控 `AuroraBinlogReplicaLag` CloudWatch 指标
- 确保 Green 集群的规模不低于工作负载要求
- 检查 Blue 和 Green 上的资源利用率（CPU、内存、IOPS）
- 目标: 在继续切换之前复制延迟 < 1 秒

---

## 切换执行

### 步骤 4: 切换前验证

**时间安排**: 触发切换前立即执行

#### 操作 4.1: 验证应用程序健康状态
```bash
# 检查应用程序连接池
# 验证没有卡住的连接或高错误率
# 确认工作负载正常运行
```

**健康检查:**
-  应用程序日志显示无连接错误
-  连接池利用率 < 80%
-  事务成功率 > 99.9%
-  没有正在进行的手动事务

#### 操作 4.2: 验证部署状态
```bash
aws rds describe-blue-green-deployments \
  --blue-green-deployment-identifier <deployment-id> \
  --query 'BlueGreenDeployments[0].Status'
```

**预期:** `AVAILABLE`

#### 操作 4.3: 通知相关方
**发送通知:**
- 切换开始时间: `<timestamp>`
- 预期停机时间: 几秒钟

### 步骤 5: 执行切换

**时间安排**: 2-7 秒（关键窗口）

#### 操作 5.1: 触发切换
```bash
# 执行切换命令
aws rds switchover-blue-green-deployment \
  --blue-green-deployment-identifier <deployment-id> \
  --switchover-timeout 300
```

**预期输出:**
```json
{
  "BlueGreenDeployment": {
    "Status": "SWITCHOVER_IN_PROGRESS"
  }
}
```

#### 操作 5.2: 监控切换进度

**实时监控应用程序日志:**
```bash
# 查找这些关键日志模式:
# [bgdId: '1'] BG status: PREPARATION → IN_PROGRESS
# [bgdId: '1'] BG status: IN_PROGRESS → POST
# Switched to new host: ip-10-x-x-x
```

**预期时间线:**
```
T+0s:  切换触发
T+3-4s: PREPARATION 阶段（无应用程序影响）
T+5-6s: IN_PROGRESS 阶段（几秒钟停机时间）
        - 自动重试，500ms 退避
        - 预期 100% 恢复
T+7s:   POST 阶段（稳定化）
        - 所有 worker 在 Green 集群上运行
T+55s:  COMPLETED 阶段
```

#### 操作 5.3: 观察 Worker 行为

**监控预期模式:**
-  错误包含: "The active SQL connection has changed"
-  Worker 在 500ms 内自动重试
-  所有 worker 成功重新连接到新主机
-  切换后第一次操作: ~2-2.1 秒延迟（正常）

---

## 切换后验证

### 步骤 6: 立即验证（T+0 到 T+5 分钟）

#### 操作 6.1: 验证集群版本
```sql
-- 连接到集群端点
mysql -h <cluster-endpoint> -u <user> -p<password>

-- 检查 Aurora 版本
SELECT @@aurora_version;
```

**预期:** `3.10.2`（或目标版本）

#### 操作 6.2: 检查应用程序健康状态
**在应用程序日志中验证:**
-  所有 worker 正常运行
-  没有持续的连接错误
-  事务成功率 = 100%
-  新主机已确认: `ip-10-x-x-x`（与旧主机不同）

#### 操作 6.3: 验证部署状态
```bash
aws rds describe-blue-green-deployments \
  --blue-green-deployment-identifier <deployment-id> \
  --query 'BlueGreenDeployments[0].Status'
```

**预期:** `SWITCHOVER_COMPLETED`

### 步骤 7: 性能验证（T+5 到 T+15 分钟）

#### 操作 7.1: 监控读延迟

**收集切换后读延迟:**
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

**预期:** 读延迟不变

#### 操作 7.2: 验证写性能
**检查写延迟（应该不变）:**
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

**预期:** 写延迟不变

#### 操作 7.3: 数据库连接验证
```sql
-- 验证集群拓扑
SELECT * FROM mysql.ro_replica_status WHERE deployment_type = 'BLUE_GREEN';

-- 检查活动连接
SHOW PROCESSLIST;

-- 验证复制状态（如适用）
SHOW REPLICA STATUS\G
```

### 步骤 8: 扩展监控（T+15 到 T+60 分钟）

#### 操作 8.1: 应用程序性能审查
**收集最终指标:**
```bash
# 切换窗口期间的总操作数
# 事务失败计数（应该为 0 个永久失败）
# Worker 受影响率
# 恢复时间（预期几秒钟）
```

---

## 成功标准

### 部署成功检查清单

#### 技术成功标准
-  纯停机时间: 几秒钟
-  永久失败事务: 0
-  事务成功率: 100%（重试后）
-  Aurora 版本已升级: ✅（用 `SELECT @@aurora_version;` 验证）
-  写延迟不变: ✅（保持 2-4ms 基线）
-  所有 worker 已恢复: ✅
-  Blue-Green 生命周期已完成: ✅（NOT_CREATED → COMPLETED）

#### 运维成功标准
-  零手动干预
-  无需应用程序代码更改
-  客户无可见错误
-  无数据丢失或损坏
-  监控仪表板显示健康状态
-  旧 Blue 集群准备好退役

---

## 部署后清理

### 步骤 9: 退役旧 Blue 集群（可选）

**时间安排:** 稳定运行 24-72 小时后

#### 操作 9.1: 最终验证
**验证 Green 集群稳定性:**
-  24+ 小时稳定运行
-  无意外错误或降级
-  读延迟恢复到可接受水平
-  所有监控指标健康

#### 操作 9.2: 删除 Blue-Green 部署
```bash
# 选项 1: 删除部署和旧 Blue 集群
aws rds delete-blue-green-deployment \
  --blue-green-deployment-identifier <deployment-id> \
  --delete-target true

# 选项 2: 保留旧 Blue 集群以延长回滚窗口
aws rds delete-blue-green-deployment \
  --blue-green-deployment-identifier <deployment-id> \
  --delete-target false

# 稍后手动删除旧集群
aws rds delete-db-cluster \
  --db-cluster-identifier <old-blue-cluster-id> \
  --skip-final-snapshot
```

---

## 附录 A: 快速参考命令

### 检查 Aurora 版本
```sql
SELECT @@aurora_version;
```

### 监控 Blue-Green 状态
```bash
aws rds describe-blue-green-deployments \
  --blue-green-deployment-identifier <deployment-id>
```

### 检查复制延迟
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

### 应用程序健康检查
```bash
# 验证 Blue-Green 插件已激活
grep "BG status:" /path/to/application.log | tail -5

# 统计切换期间的连接错误
grep "connection_error" /path/to/application.log | grep "$(date +%Y-%m-%d)" | wc -l
```

---

## 附录 B: 实验室验证结果

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

## 附录 C: Blue-Green 阶段日志关键字

使用这些关键字在应用程序日志中识别 Blue-Green 切换阶段并跟踪切换时间线。

### 阶段转换关键字

**Blue-Green 状态更改:**
```
[bgdId: '1'] BG status: NOT_CREATED
[bgdId: '1'] BG status: CREATED
[bgdId: '1'] BG status: PREPARATION
[bgdId: '1'] BG status: IN_PROGRESS     ← Critical switchover window begins
[bgdId: '1'] BG status: POST            ← Switchover completed
[bgdId: '1'] BG status: COMPLETED
```

**时间线标记:**
- `NOT_CREATED → CREATED` - 检测到 Blue-Green 部署
- `CREATED → PREPARATION` - 正在准备 Green 集群（无应用程序影响）
- `PREPARATION → IN_PROGRESS` - 启动主动切换（停机窗口）
- `IN_PROGRESS → POST` - 切换完成，开始稳定化
- `POST → COMPLETED` - 部署完成，旧环境可以退役

### 连接事件关键字

**主机切换:**
```
Switched to new host: ip-10-x-x-x        ← Worker successfully connected to Green cluster
Routing connections to GREEN_WRITER
Suspending new connections               ← IN_PROGRESS phase detected
```

**故障转移事件:**
```
Failover                                 ← Connection failover triggered
The active SQL connection has changed    ← Typical error during switchover
connection_error                         ← Connection failure logged
Retry 1/5                               ← Automatic retry with backoff
```

### 性能标记关键字

**操作结果:**
```
SUCCESS: Worker-X | Host: ip-10-x-x-x   ← Successful operation
ERROR: Worker-X | connection_lost       ← Transient error during switchover
Latency: 2,062ms                        ← First operation after switchover (elevated)
Latency: 2-4ms                          ← Normal operation latency
```

**统计信息:**
```
STATS: Total: X | Success: Y | Failed: Z | Success Rate: 100.00%
```

### 搜索命令

**查找 Blue-Green 阶段转换:**
```bash
# 搜索所有阶段转换
grep "BG status:" /path/to/application.log

# 查找切换窗口
grep -A 5 "IN_PROGRESS" /path/to/application.log

# 识别切换期间的连接错误
grep "connection_error\|active SQL connection" /path/to/application.log | grep "$(date +%Y-%m-%d)"

# 统计受影响的 worker
grep "Retry 1/5" /path/to/application.log | wc -l

# 查找切换后的第一个新连接
grep "Switched to new host" /path/to/application.log | head -1
```

**时间线重建:**
```bash
# 提取完整的切换时间线
grep -E "BG status:|Switched to new host|connection_error|SUCCESS.*Latency: [0-9]{4}" /path/to/application.log | \
  grep "$(date +%Y-%m-%d)" | \
  awk '{print $1, $2, $0}'
```

### 切换期间的预期日志模式

**典型序列（几秒钟停机时间）:**
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

**从日志中获取的关键指标:**
- **Pure Downtime**: 第一个 `Switched to new host` 和旧主机上最后一次操作之间的时间（预期几秒钟）
- **Workers Affected**: `connection_error` 日志的计数
- **Recovery Time**: 从第一个错误到成功重试的时间
- **First Operation Latency**: 切换后第一次操作的延迟

**切换期间的实际使用:**

1. **切换前** - 验证插件已激活:
   ```bash
   grep "BG status:" /path/to/application.log | tail -1
   # 预期: [bgdId: '1'] BG status: CREATED or PREPARATION
   ```

2. **切换期间** - 实时监控（在单独的终端中打开）:
   ```bash
   tail -f /path/to/application.log | grep --line-buffered -E "BG status:|Switched|connection_error|ERROR"
   ```

3. **切换后** - 验证成功:
   ```bash
   # 检查最终状态
   grep "BG status: COMPLETED" /path/to/application.log

   # 统计失败次数
   grep "connection_error" /path/to/application.log | grep "$(date +%Y-%m-%d)" | wc -l

   # 验证所有 worker 已恢复
   grep "Retry 1/5" /path/to/application.log | wc -l
   ```

---

## 文档修订历史

| Version | Date | Author | Changes |
|---------|------|--------|---------|
| 1.0 | 2025-12-09 | Aurora Blue-Green Lab | Initial runbook creation based on lab testing |

---

**如有问题或疑问，请参考:**
- [AWS Advanced JDBC Wrapper Documentation](https://github.com/aws/aws-advanced-jdbc-wrapper)
- [Aurora Blue-Green Deployments Guide](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/blue-green-deployments.html)
- [Blue-Green 部署最佳实践](https://docs.aws.amazon.com/AmazonRDS/latest/AuroraUserGuide/blue-green-deployments-best-practices.html)
- [Architecture Deep Dive](./aws-jdbc-wrapper-bluegreen-architecture.md)

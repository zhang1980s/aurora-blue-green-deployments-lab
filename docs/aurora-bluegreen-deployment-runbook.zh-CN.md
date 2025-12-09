# Aurora Blue-Green 部署运维手册

## 文档信息

**文档版本**: 1.0
**最后更新**: 2025-12-09
**作者**: Aurora Blue-Green Deployment Lab Project
**基于**: 跨 2 个集群的 Aurora MySQL 3.04.4 → 3.10.2 实验室验证测试

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
- **Worker 影响**: 部分 worker 在切换期间经历瞬态错误，由 JDBC wrapper 重试机制自动恢复（5 次尝试，500ms 退避）。使用 try-catch 代码块的应用程序会观察到 SQLException，但应记录并继续，不应失败。详细的错误处理指南请参见[附录 D](#附录-d-应用程序错误处理与-try-catch-代码块)。

---

## 部署前检查清单

### 先决条件验证

#### ☐ 0. AWS Advanced JDBC Wrapper 版本

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

**实验室测试版本**: 2.6.8

#### ☐ 1. 数据库用户权限

**在 Blue 和 Green 集群上验证权限:**
```sql
-- 测试访问拓扑表
SELECT * FROM mysql.rds_topology LIMIT 1;
```

**预期结果**: 查询成功返回（即使为空）

**重要**: 这些权限必须在 Blue 和 Green 集群上都授予，切换后不应撤销。

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

#### ☐ 2. 二进制日志配置

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

#### ☐ 3. 多线程复制（推荐）

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

#### ☐ 4. 应用程序配置

**验证 JDBC URL 包含 Blue-Green 插件:**
```
jdbc:aws-wrapper:mysql://<cluster-endpoint>:3306/<database>?wrapperPlugins=initialConnection,auroraConnectionTracker,bg,failover2,efm2&connectTimeout=30000&socketTimeout=30000&failoverTimeoutMs=60000&failoverClusterTopologyRefreshRateMs=2000
```

**关键参数检查清单:**
- ☐ `wrapperPlugins=initialConnection,auroraConnectionTracker,bg,failover2,efm2`
  - `initialConnection`: 建立初始连接属性和验证
  - `auroraConnectionTracker`: 跟踪 Aurora 集群拓扑和连接状态
  - `bg` (Blue-Green): 监控 Blue-Green 部署状态以实现协调切换
  - `failover2`: 处理集群级故障转移场景（版本 2）
  - `efm2` (Enhanced Failure Monitoring v2): 主动连接健康监控
- ☐ `bgdId=1` (或自动检测)

#### ☐ 5. 日志配置（测试/预发布环境）

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

#### ☐ 6. 监控设置

**要监控的 CloudWatch 指标:**
- `DatabaseConnections`（当前连接数）
- `CPUUtilization`（集群负载）
- `AuroraBinlogReplicaLag`（PREPARATION 期间的复制延迟）
- `ReadLatency` 和 `WriteLatency`（性能基线）

**要跟踪的应用程序指标:**
- 连接池使用率
- 事务成功/失败率
- P50、P95、P99 延迟

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

**记录部署 ID:** `bgd-_________________`

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
- ☐ 状态 = `AVAILABLE`
- ☐ 复制延迟 < 1 秒
- ☐ Green 集群端点可访问
- ☐ 应用程序日志显示: `[bgdId: '1'] BG status: CREATED` 或 `PREPARATION`

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
- ☐ 应用程序日志显示无连接错误
- ☐ 连接池利用率 < 80%
- ☐ 事务成功率 > 99.9%
- ☐ 没有正在进行的手动事务

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
- 预期影响: 55% 的 worker 经历瞬态错误（自动恢复）
- 监控仪表板: `<link>`

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
        - 55% 的 worker 经历瞬态错误
        - 自动重试，500ms 退避
        - 预期 100% 恢复
T+7s:   POST 阶段（稳定化）
        - 所有 worker 在 Green 集群上运行
        - 读延迟可能升高（+57-133%）
T+55s:  COMPLETED 阶段
```

#### 操作 5.3: 观察 Worker 行为

**监控预期模式:**
- ☐ ~55% 的 worker 记录连接错误（瞬态）
- ☐ 错误包含: "The active SQL connection has changed"
- ☐ Worker 在 500ms 内自动重试
- ☐ 所有 worker 成功重新连接到新主机
- ☐ 切换后第一次操作: ~2-2.1 秒延迟（正常）

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
- ☐ 所有 worker 正常运行
- ☐ 没有持续的连接错误
- ☐ 事务成功率 = 100%
- ☐ 新主机已确认: `ip-10-x-x-x`（与旧主机不同）

#### 操作 6.3: 验证部署状态
```bash
aws rds describe-blue-green-deployments \
  --blue-green-deployment-identifier <deployment-id> \
  --query 'BlueGreenDeployments[0].Status'
```

**预期:** `SWITCHOVER_COMPLETED`

### 步骤 7: 性能验证（T+5 到 T+15 分钟）

#### 操作 7.1: 监控读延迟（关键）

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
- ☐ 纯停机时间: 几秒钟
- ☐ 永久失败事务: 0
- ☐ 事务成功率: 100%（重试后）
- ☐ Aurora 版本已升级: ✅（用 `SELECT @@aurora_version;` 验证）
- ☐ 写延迟不变: ✅（保持 2-4ms 基线）
- ☐ 所有 worker 已恢复: ✅
- ☐ Blue-Green 生命周期已完成: ✅（NOT_CREATED → COMPLETED）

#### 运维成功标准
- ☐ 零手动干预
- ☐ 无需应用程序代码更改
- ☐ 客户无可见错误
- ☐ 无数据丢失或损坏
- ☐ 监控仪表板显示健康状态
- ☐ 旧 Blue 集群准备好退役

---

## 部署后清理

### 步骤 9: 退役旧 Blue 集群（可选）

**时间安排:** 稳定运行 24-72 小时后

#### 操作 9.1: 最终验证
**验证 Green 集群稳定性:**
- ☐ 24+ 小时稳定运行
- ☐ 无意外错误或降级
- ☐ 读延迟恢复到可接受水平
- ☐ 所有监控指标健康

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

## 附录 D: 应用程序错误处理与 Try-Catch 代码块

### 概述

当应用程序在数据库操作周围有 try-catch 代码块时,它们**将会观察到** Blue-Green 切换期间的瞬态 SQLException 错误。然而,JDBC wrapper 的自动重试机制仍然透明工作,确保 100% 成功率。

### 切换期间发生的情况

```java
try {
    statement.execute(sql);
    // 成功路径
} catch (SQLException e) {
    // 应用程序将在切换期间捕获此错误
    logger.error("数据库操作失败", e);
    // 错误消息: "The active SQL connection has changed"
}
```

**预期行为:**
- **55% 的 worker** 将捕获 SQLException(实验室测试中 20 个 worker 中有 11 个)
- **45% 的 worker** 不会经历错误(立即成功)
- **100% 成功率**(重试后)(无永久失败)

### 错误和恢复时间线

```
T+0ms:   切换开始(IN_PROGRESS 阶段)
T+27ms:  Worker 遇到连接错误
         → SQLException 抛给应用程序
         → 应用程序 catch 块执行
         → 应用程序记录错误
T+527ms: JDBC wrapper 自动重试(500ms 退避)
         → 重试在 Green 集群上成功
         → 下一次操作恢复正常延迟
```

### 推荐的应用程序代码模式

#### 模式 1: 记录并继续(✅ 推荐)

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DatabaseService {
    private static final Logger logger = LoggerFactory.getLogger(DatabaseService.class);

    public void executeOperation(Statement statement, String sql) {
        try {
            statement.execute(sql);
            logger.debug("操作成功");

        } catch (SQLException e) {
            // 记录以供监控但不要使操作失败
            logger.warn("数据库操作遇到瞬态错误: {}",
                e.getMessage());

            // 增加指标计数器
            metrics.incrementCounter("database.transient_errors");

            // JDBC wrapper 将自动重试(5 次尝试,500ms 退避)
            // 无需任何操作 - 重试是透明的

            // 可选: 下一次操作前短暂延迟
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
```

**为什么这有效:**
- 应用程序观察到错误(用于警报/监控)
- 但不会使事务失败
- JDBC wrapper 重试自动处理恢复
- 下一次操作在 wrapper 重新连接后成功

#### 模式 2: 应用程序级重试(⚠️ 不推荐)

```java
public void executeWithRetry(Statement statement, String sql) {
    int maxRetries = 3;
    for (int i = 0; i < maxRetries; i++) {
        try {
            statement.execute(sql);
            return; // 成功
        } catch (SQLException e) {
            if (i == maxRetries - 1) {
                throw new DatabaseException("重试后失败", e);
            }
            logger.warn("重试 {}/{}: {}", i+1, maxRetries, e.getMessage());
            Thread.sleep(500);
        }
    }
}
```

**为什么这不必要:**
- JDBC wrapper 已经重试(5 次尝试,500ms 退避)
- 添加重复的重试逻辑
- 可能导致比必要更长的延迟
- 增加代码复杂性

#### 模式 3: 快速失败(❌ 不推荐用于 Blue-Green)

```java
public void executeWithFailFast(Statement statement, String sql) {
    try {
        statement.execute(sql);
    } catch (SQLException e) {
        logger.error("数据库操作失败", e);
        throw new DatabaseException("操作失败", e);
    }
}
```

**为什么这会失败:**
- 应用程序永久终止操作
- JDBC wrapper 重试仍然发生,但应用程序已经失败
- 在切换期间导致不必要的失败
- 违反 Blue-Green 零停机时间目标

### 必需的配置

基于 WorkloadSimulator.java 实验室测试:

```java
// HikariCP 连接池配置
HikariConfig hikariConfig = new HikariConfig();
hikariConfig.setConnectionTimeout(30000);      // 30 秒
hikariConfig.setIdleTimeout(600000);           // 10 分钟
hikariConfig.setMaxLifetime(1800000);          // 30 分钟
hikariConfig.setMaximumPoolSize(100);          // 每个 worker 10 个连接
hikariConfig.setMinimumIdle(10);

// 带有 Blue-Green 插件的 JDBC URL
String jdbcUrl = "jdbc:aws-wrapper:mysql://<cluster-endpoint>:3306/lab_db?" +
    "wrapperPlugins=initialConnection,auroraConnectionTracker,bg,failover2,efm2&" +
    "connectTimeout=30000&" +          // TCP 连接超时
    "socketTimeout=30000&" +           // Socket 读取超时
    "failoverTimeoutMs=60000&" +       // 故障转移完成超时
    "bgConnectTimeoutMs=30000&" +      // BG 切换连接超时
    "bgSwitchoverTimeoutMs=180000";    // BG 切换过程超时(3 分钟)

hikariConfig.setJdbcUrl(jdbcUrl);
```

### 切换期间的预期指标

来自实验室测试(Test 055308 和 070455):

| 指标 | 值 | 注释 |
|------|-----|------|
| **纯停机时间** | 27-29ms | 连接不可用窗口 |
| **捕获错误的 Worker** | 55%(20 个中的 11 个) | 将执行 catch 块 |
| **未受影响的 Worker** | 45%(20 个中的 9 个) | 未观察到 SQLException |
| **重试成功率** | 100% | 所有重试在 Green 集群上成功 |
| **第一次操作延迟** | 2,000-2,100ms | 切换后第一次操作 |
| **正常延迟** | 2-4ms(写入)<br>0.3-0.7ms(读取) | 第一次操作后返回基线 |
| **JDBC Wrapper 重试** | 5 次尝试 | 尝试之间 500ms 退避 |

### 生产部署检查清单

**✅ 应该做:**
- 记录 SQLException 以供监控(warn/info 级别)
- 在指标中跟踪瞬态错误计数
- 信任 JDBC wrapper 的自动重试机制
- 配置连接池超时 ≥ 30 秒
- 监控延迟峰值(预期第一次操作 2+ 秒)
- 设置持续错误警报(> 1 分钟)

**❌ 不应该做:**
- 抛出终止应用程序的异常
- 实现冗余的应用程序级重试逻辑
- 使用短超时值(< 15 秒)
- 在第一次 SQLException 时使操作失败
- 禁用 JDBC wrapper 重试机制

### 监控和警报

**要跟踪的指标:**
```java
// 计数瞬态错误
metrics.incrementCounter("database.transient_errors",
    Tags.of("error_type", "connection_changed"));

// 跟踪延迟峰值
metrics.timer("database.operation_latency").record(duration);

// 警报阈值
if (transientErrorRate > 100 errors/min for > 2 minutes) {
    alert("持续的数据库连接问题");
}
```

**切换期间的预期:**
- **11-20 个瞬态错误**(对于 20 worker 配置)
- **2+ 秒延迟**(第一次操作)
- **500ms 内恢复**(所有 worker)
- **10-15 分钟内返回基线**(读取可能需要更长时间)

### 生产代码示例

包含所有最佳实践的完整示例:

```java
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class ProductionDatabaseService {
    private static final Logger logger = LoggerFactory.getLogger(ProductionDatabaseService.class);
    private final MeterRegistry metrics;

    public ProductionDatabaseService(MeterRegistry metrics) {
        this.metrics = metrics;
    }

    public void executeOperation(Connection connection, String sql) {
        long startTime = System.nanoTime();

        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);

            // 跟踪成功
            long duration = System.nanoTime() - startTime;
            metrics.timer("database.operation.success")
                .record(duration, TimeUnit.NANOSECONDS);

            logger.debug("操作在 {}ms 内成功", duration / 1_000_000);

        } catch (SQLException e) {
            // 记录瞬态错误以供监控
            logger.warn("数据库操作遇到瞬态错误(JDBC wrapper 将重试): {} - {}",
                e.getSQLState(), e.getMessage());

            // 跟踪瞬态错误以供警报
            metrics.counter("database.transient_errors",
                "error_code", e.getSQLState(),
                "error_type", "connection_changed").increment();

            // 不要抛出 - 让 JDBC wrapper 重试机制处理它
            // wrapper 将重试最多 5 次,500ms 退避

            // 可选: 下一次操作前短暂延迟
            try {
                Thread.sleep(100);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
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
- [Architecture Deep Dive](./aws-jdbc-wrapper-bluegreen-architecture.md)

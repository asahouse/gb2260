# GB2260 专题文档 06 - 全链路自动化测试与质量保障体系

本文档详细说明 GB2260 自动化测试套件的架构设计、测试用例清单、运行方法及断点自愈与全量入库的验收标准。

> **导航**：[⬅️ 返回项目主页 (README.md)](../README.md)

---

## 目录

- [一、测试架构与覆盖矩阵](#一测试架构与覆盖矩阵)
- [二、测试执行命令](#二测试执行命令)
- [三、核心测试套件功能说明](#三核心测试套件功能说明)
  - [1. 断点续爬与差集自愈专项测试 (`CrawlRefireEndToEndTest`)](#1-断点续爬与差集自愈专项测试-crawlrefireendtoendtest)
  - [2. 全国全量数据抓取与持久化验收测试 (`NationwideDataCrawlTest`)](#2-全国全量数据抓取与持久化验收测试-nationwidedatacrawltest)
  - [3. Web 控制台与管理查询测试 (`DashboardEndToEndTest`)](#3-web-控制台与管理查询测试-dashboardendtoendtest)
  - [4. 执行器在途原子遥测与生命周期测试 (`RunThreadPoolExecutorTest`)](#4-执行器在途原子遥测与生命周期测试-runthreadpoolexecutortest)
  - [5. 民政部真实 API 连通性测试 (`McaApiFeasibilityTest`)](#5-民政部真实-api-连通性测试-mcaapifeasibilitytest)
  - [6. 离线快照倒排索引构建测试 (`McaSnapshotServiceTest`)](#6-离线快照倒排索引构建测试-mcasnapshotservicetest)
  - [7. 统计局模式全链路测试 (`EndToEndPipelineTest`)](#7-统计局模式全链路测试-endtoendpipelinetest)

---

## 一、测试架构与覆盖矩阵

项目基于 JUnit 4 + Spring Test + Mockito 构建分层测试体系，包含 11 个测试类，共计 **26 个自动化测试用例，通过率 100%（BUILD SUCCESS）**：

| 测试类 | 用例数 | 测试性质 | 覆盖目标 |
| :--- | :---: | :--- | :--- |
| [`CrawlRefireEndToEndTest.java`](../src/test/java/org/codework/tools/gb2260/CrawlRefireEndToEndTest.java) | 4 | 端到端集成 | `/api/run/refire` 连通性、残缺断点自愈补偿（16 区县+300+街道）、幂等重放零冲突、审计流水记录 |
| [`NationwideDataCrawlTest.java`](../src/test/java/org/codework/tools/gb2260/NationwideDataCrawlTest.java) | 1 | 端到端全量验收 | 全国 34 省级、341 地市、2,974 区县、41,351 街道全量 20 秒极速落库与 Redis 同步验证 |
| [`DashboardEndToEndTest.java`](../src/test/java/org/codework/tools/gb2260/DashboardEndToEndTest.java) | 5 | 控制台集成 | Thymeleaf HTML 页面渲染、DB vs Redis 一致性指标、多维分页查询、历史审计与缓存探针 |
| [`RunThreadPoolExecutorTest.java`](../src/test/java/org/codework/tools/gb2260/RunThreadPoolExecutorTest.java) | 2 | 并发单元测试 | 多层异步在途任务原子扣减、isEndTask 平滑收敛、`isNormalCompleted` 与 `shutdownNow` 判定 |
| [`McaApiFeasibilityTest.java`](../src/test/java/org/codework/tools/gb2260/McaApiFeasibilityTest.java) | 4 | 网络与集成 | 民政部真实官方接口连通性、直辖市虚拟城市穿透、省直管县级市三转四穿透、端到端落库 |
| [`McaSnapshotServiceTest.java`](../src/test/java/org/codework/tools/gb2260/McaSnapshotServiceTest.java) | 3 | 单元测试 | 本地 1.9MB 快照读取、3344 个节点正向倒排索引构建、直辖市及前缀检索性能 |
| [`RealHttpLiveCrawlerTest.java`](../src/test/java/org/codework/tools/gb2260/RealHttpLiveCrawlerTest.java) | 1 | 冒烟集成测试 | 真实网络环境请求与分发调度 |
| [`EndToEndPipelineTest.java`](../src/test/java/org/codework/tools/gb2260/EndToEndPipelineTest.java) | 3 | 静态模式集成 | 统计局 HTML 规则抓取与 Mock 全链路 |
| [`LiveDbCrawlAndPrintTest.java`](../src/test/java/org/codework/tools/gb2260/LiveDbCrawlAndPrintTest.java) | 1 | 实盘落库测试 | 数据库即时写入验证 |
| [`AreaInfoTest.java`](../src/test/java/org/codework/tools/gb2260/AreaInfoTest.java) | 1 | 实体单元测试 | 实体 JSON 序列化与反序列化一致性 |
| [`AreaSnatchHelperTest.java`](../src/test/java/org/codework/tools/gb2260/AreaSnatchHelperTest.java) | 1 | 工具类测试 | 12 位标准代码补全与短码截取算法 |

---

## 二、测试执行命令

```powershell
# 运行全量 26 个测试套件（注意：Windows 下 Surefire 推荐带 -DforkCount=0）
mvn test -DforkCount=0

# 专门运行断点续爬与差集自愈端到端测试
mvn test "-Dtest=CrawlRefireEndToEndTest" -DforkCount=0

# 专门运行全国全量抓取与持久化验收测试
mvn test "-Dtest=NationwideDataCrawlTest" -DforkCount=0

# 专门运行 Web 控制台端到端全链路集成测试
mvn test "-Dtest=DashboardEndToEndTest" -DforkCount=0
```

---

## 三、核心测试套件功能说明

### 1. 断点续爬与差集自愈专项测试 (`CrawlRefireEndToEndTest`)
- **连通性测试**：验证 `GET /api/run/refire` 正确返回 HTTP 200 与 `mode: refire`；
- **模拟断点与自愈补偿**：人为保留北京市级，但清空其下属区县与街道，模拟抓取在省级被中断。触发 `refire()` 后验证已有省级记录完好保留，16 个区县与 300+ 街道全部自动补齐入库与写入 Redis；
- **完全幂等性测试**：在数据已完好存在的前提下重复调用 `refire()`，验证零主键冲突，数据行数恒定不变；
- **审计流水验证**：验证 `crawl_history` 记录了以 `refire-` 开头的审计批次，且正常收敛后状态为 `SUCCESS`。

### 2. 全国全量数据抓取与持久化验收测试 (`NationwideDataCrawlTest`)
- 启动民政部 Offline-First 模式，20 秒内并发完成全国全量 89,401 个并发任务；
- 验收指标：
  - `province`: 34 条
  - `city`: 341 条
  - `county`: 2,974 条
  - `town`: 41,351 条
  - Redis 各 Hash 键与 MySQL 业务表同步率达到 100.0%。

### 3. Web 控制台与管理查询测试 (`DashboardEndToEndTest`)
- 验证 Thymeleaf 模板引擎页面成功渲染；
- 验证 `/api/dashboard/stats`、`/api/dashboard/data`、`/api/dashboard/history`、`/api/dashboard/redis/probe` 接口数据完整性与分页正确性。

### 4. 执行器在途原子遥测与生命周期测试 (`RunThreadPoolExecutorTest`)
- 验证在多层异步嵌套派发任务时，`inFlightTasks` 能正确递增递减并在任务收敛时归零；
- 验证 `isNormalCompleted()` 判定与 `shutdownNow()` 中止标记逻辑。

---

**系列专题导航**：
- [01 - 系统全局架构与核心机制](01-architecture.md)
- [02 - 断点续爬与差集自愈机制](02-refire-mechanism.md)
- [03 - 接口详细规范与在线调试](03-api-guide.md)
- [04 - 数据库与缓存拓扑设计规范](04-database-and-cache.md)
- [05 - 生产部署与运维全景指南](05-deployment-guide.md)
- [06 - 全链路自动化测试与质量保障](06-testing-and-validation.md)
- [⬅️ 返回项目主页 (README.md)](../README.md)

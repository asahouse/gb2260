# GB2260 - 全国行政区划高并发数据采集、差集自愈与级联服务

[![Java](https://img.shields.io/badge/Java-1.8-orange.svg)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-2.0.3.RELEASE-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![MyBatis-Plus](https://img.shields.io/badge/MyBatis--Plus-3.0--beta-blue.svg)](https://baomidou.com/)
[![Swagger](https://img.shields.io/badge/Swagger-2.9.2-green.svg)](http://localhost:8080/swagger-ui.html)
[![Tests](https://img.shields.io/badge/Tests-26%20Passed-success.svg)](file:///C:/Users/Benjamin/orca/workspaces/gb2260/analyse/src/test/java/org/codework/tools/gb2260)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

基于 **Spring Boot 2.0.3** 构建的全国行政区划（遵循中华人民共和国国家标准 **GB/T 2260**）多线程高并发采集、双引擎调度、双重持久化（MySQL 关系型 + Redis 高性能缓存）、**基于树状差集推导的断点续爬自愈 (Refire)** 以及面向前端级联选择器的在线查询服务。

---

## 目录

- [一、项目概述](#一项目概述)
- [二、系统架构与核心交互流程图](#二系统架构与核心交互流程图)
  - [2.1 系统全局分层架构图](#21-系统全局分层架构图)
  - [2.2 断点续爬与差集自愈时序图](#22-断点续爬与差集自愈时序图)
- [三、核心设计机制扼要说明](#三核心设计机制扼要说明)
- [四、快速部署方式](#四快速部署方式)
  - [4.1 方式一：Docker Compose 一键全栈编排 (推荐)](#41-方式一docker-compose-一键全栈编排-推荐)
  - [4.2 方式二：Jar 独立进程运行](#42-方式二jar-独立进程运行)
- [五、系列专题文档索引](#五系列专题文档索引)
- [六、常用控制台与服务入口](#六常用控制台与服务入口)

---

## 一、项目概述

GB2260 针对传统行政区划爬虫“易被封禁、递归堆栈溢出、街道办事处数据大幅缺失、任务中断后必须全量重爬破坏已有数据”等行业痛点，研发了新一代高可靠行政区划采集与级联服务系统：

- **官方现势性数据**：全面对接民政部官方政务 API，全国 **41,351 条** 乡镇/街道办事处法定数据 100% 完整收录；
- **全量秒级快速落盘**：内置离线权威快照机制（Offline-First），80~100 并发核心可在 **20 秒内** 完成全国 34 省级、341 地级市、2,974 区县、41,351 街道乡镇（共计 8.9 万个子任务）全量持久化与缓存建立；
- **断点续爬与差集自愈 (Refire)**：支持基于数据树拓扑反推断点，非破坏性重放，严格幂等写入，仅对缺失分支执行靶向补偿下钻；
- **双重存储体系**：MySQL 关系型 5 级外键关联 + Redis 毫秒级级联缓存（Hash 字典 + Set 索引），为前端 Element UI Cascader 等组件提供极致查询性能；
- **内置一体化 Web 控制台**：单 Jar 包开箱即用，提供动态遥测、按需多维检索、Redis 缓存穿透探针与 4 级联动测试沙箱。

---

## 二、系统架构与核心交互流程图

### 2.1 系统全局分层架构图

```
                       ┌───────────────────────────────────────────────┐
                       │           客户端 / Web 前端 (Element/Cascader)  │
                       └───────────────────────┬───────────────────────┘
                                               │
                        RESTful HTTP 请求 (/api/get/*, /api/run/*)
                                               │
                                               ▼
                       ┌───────────────────────────────────────────────┐
                       │        Spring Boot REST Controllers           │
                       │    (RunController, GetController, Swagger)    │
                       └───────────────┬───────────────┬───────────────┘
                                       │               │
                 启动抓取 / 断点续爬 / 调度控制 │               │ 毫秒级级联读取
                                       ▼               │
                       ┌─────────────────────────┐     │
                       │   AreaSnatchHandler     │     │
                       │ (并发控制/限频休眠/多源调度) │     │
                       │ 差集扫描补偿 (Refire)   │     │
                       └───────────┬─────────────┘     │
                                   │                   │
                 ┌─────────────────┴─────────────────┐ │
                 ▼                                   ▼ │
  【引擎1: 民政部国家地名库 (mca)】    【引擎2: 国家统计局镜像 (stats)】│
  - 官方 HTTP API 逐级请求           - 官方静态 HTML 镜像抓取          │
  - 遇 502/网络抖动平滑熔断降级       - GBK 编码防乱码流式解析         │
  - 内置本地权威快照 (Offline-First)  - 5级全量（至居委会约70万条）    │
  - 省直管/直辖市特区三转四层级穿透   - 动态防封长休眠保护             │
                 │                                   │ │
                 └─────────────────┬─────────────────┘ │
                                   ▼                   │
                       ┌─────────────────────────┐     │
                       │     Guava EventBus      │     │
                       │    (单机异步解耦事件总线)   │     │
                       └───────────┬─────────────┘     │
                                   ▼                   │
                       ┌─────────────────────────┐     │
                       │   MsgEventSubscriber    │     │
                       │ (异步消费 / 多级下钻分发)   │     │
                       │ 幂等持久化 (Select-First)   │     │
                       └───────────┬─────────────┘     │
                                   │                   │
                    ┌──────────────┴──────────────┐    │
                    ▼                             ▼    ▼
        ┌───────────────────────┐     ┌───────────────────────┐
        │   MySQL 关系型数据库   │     │    Redis 内存缓存     │
        │ - province (省级)     │     │ - Hash: 实体字典表     │
        │ - city     (地级市)   │     │ - Set:  层级关系索引   │
        │ - county   (区县级)   │     └───────────────────────┘
        │ - town     (乡镇街道) │
        │ - village  (居委会)   │
        │ - crawl_history(审计) │
        └───────────────────────┘
```

### 2.2 断点续爬与差集自愈时序图

```mermaid
sequenceDiagram
    autonumber
    actor Admin as 管理员/定时任务
    participant RunCtrl as RunController
    participant SnatchHandler as AreaSnatchHandler
    participant DB as MySQL 数据库
    participant EventBus as Guava EventBus
    participant Sub as MsgEventSubscriber
    participant Cache as Redis 缓存

    Admin->>RunCtrl: GET /api/run/refire
    RunCtrl->>SnatchHandler: refire(taskFlag)
    Note over SnatchHandler: 1. 检查运行锁，确保无并发任务<br/>2. 绝不执行 truncateAll / flushDB
    SnatchHandler->>DB: 生成批次号 refire-xxxx，写入 crawl_history (RUNNING)
    
    rect rgb(240, 248, 255)
    Note over SnatchHandler, DB: 2. 高性能差集扫描阶段 (通过 groupBy 聚合计算)
    SnatchHandler->>DB: queryGroupCount("city", "province_code")
    SnatchHandler->>DB: queryGroupCount("county", "city_code")
    SnatchHandler->>DB: queryGroupCount("town", "county_code")
    end

    rect rgb(255, 250, 240)
    Note over SnatchHandler, EventBus: 3. 差集推导与靶向补偿下钻
    alt 省级缺失下属市
        SnatchHandler->>EventBus: post(ProvinceEvent) 触发该省补偿
    else 城市缺失下属区县
        SnatchHandler->>EventBus: post(CityEvent) 触发该市补偿
    else 区县缺失下属街道 (最常见中断点)
        SnatchHandler->>EventBus: post(CountyEvent) 触发该区县乡镇补偿
    end
    end

    rect rgb(240, 255, 240)
    Note over Sub, Cache: 4. 幂等消费与持久化
    EventBus->>Sub: 接收 Event 事件
    Sub->>DB: selectById(code)
    alt 记录不存在
        Sub->>DB: insert(entity)
    else 记录已存在
        Sub->>DB: updateById(entity)
    end
    Sub->>Cache: hset / sadd 同步刷新 Redis 缓存与下辖索引
    end

    Note over SnatchHandler: 5. inFlightCount 递减至 0，平滑收敛
    SnatchHandler->>DB: 更新 crawl_history (SUCCESS，记录增量条数与耗时)
    SnatchHandler-->>Admin: 返回 200 OK (断点续爬补偿任务已启动)
```

---

## 三、核心设计机制扼要说明

1. **双引擎体系与平滑熔断降级**：
   - 包含民政部现代化 JSON API 引擎（`mca`，覆盖四级）与统计局历史镜像引擎（`stats`，覆盖五级）；
   - 内置 [`McaSnapshotService.java`](file:///C:/Users/Benjamin/orca/workspaces/gb2260/analyse/src/main/java/org/codework/tools/gb2260/snatch/McaSnapshotService.java) 1.9MB 本地权威快照，遇网络抖动或 502 时毫秒级自动降级，支持秒级入库的 Offline-First 模式。
   - 详见专题文档：[docs/01-architecture.md](file:///C:/Users/Benjamin/orca/workspaces/gb2260/analyse/docs/01-architecture.md)。
2. **Guava EventBus 异步解耦**：
   - 彻底摒弃传统递归爬虫，将行政区划采集解耦为省、市、县、镇各层级独立事件，由 [`MsgEventSubscriber.java`](file:///C:/Users/Benjamin/orca/workspaces/gb2260/analyse/src/main/java/org/codework/tools/gb2260/snatch/guava/MsgEventSubscriber.java) 异步下钻分发，避免递归栈溢出。
3. **在途任务原子遥测与收敛控制**：
   - 自定义 [`RunThreadPoolExecutor.java`](file:///C:/Users/Benjamin/orca/workspaces/gb2260/analyse/src/main/java/org/codework/tools/gb2260/snatch/guava/RunThreadPoolExecutor.java) 维护原子未完成任务计数 `inFlightTasks`；
   - 重写生命周期钩子，通过 `isNormalCompleted()` 精确判定自然完结（`SUCCESS`）与主动终止（`TERMINATED`），任务归零时平滑释放资源并回写审计日志。
4. **双重持久化与严格幂等写入**：
   - 实体类显式标注 MyBatis-Plus `@TableId`，写入前执行 `selectById`，不存在则 `insert`，存在则 `updateById`，杜绝重复消费时的主键冲突；
   - Redis 构建 `gb2260:area:{layer}` Hash 实体字典与 `gb2260:area:index:*` Set 关系索引，毫秒级响应前端级联查询。
   - 详见专题文档：[docs/04-database-and-cache.md](file:///C:/Users/Benjamin/orca/workspaces/gb2260/analyse/docs/04-database-and-cache.md)。
5. **树状差集推导与断点自愈 (Refire)**：
   - 针对进程中断、强制终止或网络超时，提供非破坏性自愈方案；
   - 基于 `groupBy` 批处理单次 SQL 聚合（耗时 < 10ms）在内存中快速推导 2,974 个区县的下级街道差集，仅对缺失分支精准补偿下钻。
   - 详见专题文档：[docs/02-refire-mechanism.md](file:///C:/Users/Benjamin/orca/workspaces/gb2260/analyse/docs/02-refire-mechanism.md)。

---

## 四、快速部署方式

详细生产部署手册、MySQL/Redis 配置规范、Docker 容器化及 Systemd 服务守护请参阅专题文档：[docs/05-deployment-guide.md](file:///C:/Users/Benjamin/orca/workspaces/gb2260/analyse/docs/05-deployment-guide.md)。

### 4.1 方式一：Docker Compose 一键全栈编排 (推荐)

项目根目录下提供了全栈编排文件 [`docker-compose.yml`](file:///C:/Users/Benjamin/orca/workspaces/gb2260/analyse/docker-compose.yml)，内置 MySQL 5.7（自动挂载并执行 `db.sql` 初始化）、Redis 6（带 AOF 持久化与密码保护）以及 GB2260 应用服务：

```bash
# 1. 编译并打包应用 Jar
mvn clean package -DskipTests

# 2. 一键后台启动 MySQL + Redis + GB2260 应用集群
docker-compose up -d --build

# 3. 查看运行状态
docker-compose ps
```

### 4.2 方式二：Jar 独立进程运行

适合本地开发或已有 MySQL/Redis 环境的服务器：

```bash
# 1. 初始化数据库 (确保 MySQL 为 utf8mb4 字符集)
mysql -uroot -p34231724 -e "CREATE DATABASE IF NOT EXISTS gb2260 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
mysql -uroot -p34231724 -Dgb2260 < src/main/resources/db.sql

# 2. 编译打包应用
mvn clean package -DskipTests

# 3. 运行 Spring Boot 应用 (可通过环境变量覆盖数据库与 Redis 连接凭证)
java -Xms512m -Xmx2048m -Dfile.encoding=UTF-8 \
  -DDB_HOST=localhost -DDB_PORT=3306 -DDB_PASSWORD=34231724 \
  -DREDIS_HOST=localhost -DREDIS_PORT=6379 -DREDIS_PASSWORD=34231724 \
  -jar target/gb2260-0.0.1-SNAPSHOT.jar
```

---

## 五、系列专题文档索引

为了保持主文档结构精简清晰，系统全盘所有深度设计与规范已拆分为以下系列专题文档：

| 专题文档 | 主要内容与涵盖模块 | 导航链接 |
| :--- | :--- | :---: |
| **01 - 系统全局架构与核心机制** | 系统全局拓扑、双引擎体系、EventBus 异步解耦、原子遥测与双引擎详细对比 | [查看文档](file:///C:/Users/Benjamin/orca/workspaces/gb2260/analyse/docs/01-architecture.md) |
| **02 - 断点续爬与差集自愈机制** | 中断挑战、四大防护准则、自愈补偿时序图、`groupBy` 批处理聚合与操作规范 | [查看文档](file:///C:/Users/Benjamin/orca/workspaces/gb2260/analyse/docs/02-refire-mechanism.md) |
| **03 - 接口详细规范与在线调试** | Swagger 在线沙箱、Web 控制台接口 (`/api/dashboard/*`)、调度接口 (`/api/run/*`)、级联接口 (`/api/get/*`) | [查看文档](file:///C:/Users/Benjamin/orca/workspaces/gb2260/analyse/docs/03-api-guide.md) |
| **04 - 数据库与缓存拓扑设计规范** | MySQL 5 级表结构与索引设计、`crawl_history` 审计表、Redis Hash/Set 键拓扑与 CLI 命令 | [查看文档](file:///C:/Users/Benjamin/orca/workspaces/gb2260/analyse/docs/04-database-and-cache.md) |
| **05 - 生产部署与运维全景指南** | MySQL 规范、Redis 规范、Jar 进程部署、Docker 部署、Docker Compose 全栈编排、Systemd 守护 | [查看文档](file:///C:/Users/Benjamin/orca/workspaces/gb2260/analyse/docs/05-deployment-guide.md) |
| **06 - 全链路自动化测试与质量保障** | 测试体系架构、26 个测试用例覆盖矩阵、断点自愈验收、全国全量落盘测试指标 | [查看文档](file:///C:/Users/Benjamin/orca/workspaces/gb2260/analyse/docs/06-testing-and-validation.md) |

---

## 六、常用控制台与服务入口

应用启动后，可通过以下地址访问可视化控制台与核心服务：

| 功能模块 | 访问入口 | 说明 |
| :--- | :--- | :--- |
| **Web 可视化管理控制台** | [http://localhost:8080/dashboard](http://localhost:8080/dashboard) | 动态遥测监控、按需检索、缓存探针与联动沙箱 |
| **Swagger 2 在线 API 文档** | [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html) | 交互式 REST API 接口文档与在线调试沙箱 |
| **Druid 数据库连接池监控** | [http://localhost:8080/druid](http://localhost:8080/druid) | SQL 性能监控、连接池状态分布与慢查询分析 |
| **断点续爬补偿触发接口** | `GET` [http://localhost:8080/api/run/refire](http://localhost:8080/api/run/refire) | 非破坏性触发全量缺失层级差集扫描与下钻补偿 |
| **实时任务遥测接口** | `GET` [http://localhost:8080/api/run/query](http://localhost:8080/api/run/query) | 实时返回在途未完成任务数、总任务数与完成计数 |
| **全量全新启动抓取接口** | `GET` [http://localhost:8080/api/run/start](http://localhost:8080/api/run/start) | 清空数据库与 Redis，启动全量全新抓取流水线 |

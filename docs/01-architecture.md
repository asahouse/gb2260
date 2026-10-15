# GB2260 专题文档 01 - 系统全局架构与核心机制深度解析

本文档全面阐述 GB2260 行政区划采集与级联服务的设计哲学、双引擎体系、事件驱动模型及核心机制实现。

> **导航**：[⬅️ 返回项目主页 (README.md)](../README.md)

---

## 目录

- [一、系统全局架构](#一系统全局架构)
- [二、七大核心设计机制详解](#二七大核心设计机制详解)
  - [1. 双引擎可插拔数据源与平滑熔断降级](#1-双引擎可插拔数据源与平滑熔断降级)
  - [2. Guava EventBus 单机异步事件驱动解耦](#2-guava-eventbus-单机异步事件驱动解耦)
  - [3. RunThreadPoolExecutor 在途任务原子遥测与平滑收敛](#3-runthreadpoolexecutor-在途任务原子遥测与平滑收敛)
  - [4. 双重持久化存储架构 (MySQL 关系表 + Redis 高性能缓存)](#4-双重持久化存储架构-mysql-关系表--redis-高性能缓存)
  - [5. 树状差集推导与断点自愈引擎 (Refire)](#5-树状差集推导与断点自愈引擎-refire)
  - [6. 全生命周期抓取审计流水线](#6-全生命周期抓取审计流水线)
  - [7. 零依赖内置可视化 Web 控制台 (Thymeleaf)](#7-零依赖内置可视化-web-控制台-thymeleaf)
- [三、双引擎体系横向对比](#三双引擎体系横向对比)

---

## 一、系统全局架构

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

---

## 二、七大核心设计机制详解

### 1. 双引擎可插拔数据源与平滑熔断降级
- **民政部引擎 (`mca`)**：通过 [`AreaHttpFetcher.java`](../src/main/java/org/codework/tools/gb2260/snatch/AreaHttpFetcher.java) 请求官方全国地名信息查询平台 API，专为政务四级区划（省、市、县、镇/街道）打造。
- **平滑熔断与快照机制**：针对民政部线上 API 存在的 CDN 502、DNS 解析超时或防爬拦截，引入 [`McaSnapshotService.java`](../src/main/java/org/codework/tools/gb2260/snatch/McaSnapshotService.java) 内置 1.9MB 本地权威快照（`pcas-code.json`），实现正向倒排索引秒级解析与 **Offline-First（离线优先）** 模式。
- **统计局引擎 (`stats`)**：针对国家统计局城乡五级（含村委会/居委会）历史静态镜像，自研 GBK 流式解析器，结合动态防封休眠（`sleep-min` / `sleep-max`）保障鲁棒性。

### 2. Guava EventBus 单机异步事件驱动解耦
- 彻底废除方法内部深度递归遍历所有行政区的反模式，消除调用栈溢出（`StackOverflowError`）与单线程瓶颈。
- 逐级抽象出 `ProvinceEvent`、`CityEvent`、`CountyEvent`、`TownEvent`、`VillageEvent`。数据采集与数据持久化完全解耦，上级抓取完毕即发布事件，由 [`MsgEventSubscriber.java`](../src/main/java/org/codework/tools/gb2260/snatch/guava/MsgEventSubscriber.java) 异步消费并触发下一层级的下钻调度。

### 3. RunThreadPoolExecutor 在途任务原子遥测与平滑收敛
- 扩展 JUC `ThreadPoolExecutor` 研发了 [`RunThreadPoolExecutor.java`](../src/main/java/org/codework/tools/gb2260/snatch/guava/RunThreadPoolExecutor.java)，重写 `execute()` 包装 Runnable，通过 `AtomicLong inFlightTasks` 原子追踪在途未完成任务。
- 重写 `shutdownNow()` 记录 `userTerminated` 标志，并通过 `isNormalCompleted()`（`!userTerminated && hasFinish && inFlightTasks == 0`）精准区分正常收敛与强制中止。
- 当 `inFlightTasks == 0` 时调用 `notifyAll()` 唤醒守护监听线程，自动回写审计流水并优雅释放线程池资源。

### 4. 双重持久化存储架构 (MySQL 关系表 + Redis 高性能缓存)
- **MySQL 关系型持久化**：
  - 实体类显式配置 MyBatis-Plus `@TableId` 主键映射；
  - 外键建立 B-Tree 索引，支持按需多维过滤、分页查询与历史溯源；
  - `selectById == null ? insert : updateById` 严格幂等写入。
- **Redis 内存级高性能拓扑**：
  - **实体字典表**：采用 Hash 结构 `gb2260:area:{layer}`，实现 $O(1)$ 常数时间名称解析；
  - **层级下辖索引**：采用 Set 结构 `gb2260:area:index:{layer}:{parentCode}`，实现毫秒级下级列表检索。

### 5. 树状差集推导与断点自愈引擎 (Refire)
- 若抓取任务因进程崩溃、线程中断或网络超时非正常停止，无需清空数据库重新抓取；
- 通过对比上级与下级表分组统计（`groupBy` 聚合优化），自动推导缺失分支并作为种子精准补偿下钻（详见 [02-refire-mechanism.md](02-refire-mechanism.md)）。

### 6. 全生命周期抓取审计流水线
- 由 [`CrawlHistory.java`](../src/main/java/org/codework/tools/gb2260/entity/CrawlHistory.java) 与 [`CrawlHistoryService.java`](../src/main/java/org/codework/tools/gb2260/service/CrawlHistoryService.java) 支撑；
- 记录每次全量任务（`crawl-{timestamp}`）与续爬任务（`refire-{timestamp}`）的批次标识、引擎、范围、落库条数、耗时与最终状态（`RUNNING` / `SUCCESS` / `TERMINATED` / `FAILED`）。

### 7. 零依赖内置可视化 Web 控制台 (Thymeleaf)
- 基于 Spring Boot 内置 Thymeleaf + Bootstrap 5 构建全功能单页控制台 [`dashboard.html`](../src/main/resources/templates/dashboard.html)；
- 提供动态遥测进度条、多维按需检索、历史流水查看、Redis 实时缓存代码穿透探针与前端 4 级联动测试沙箱。

---

## 三、双引擎体系横向对比

| 对比维度 | 方案一：民政部国家地名信息库 (`mca`)（推荐） | 方案二：国家统计局静态镜像 (`stats`) |
| :--- | :--- | :--- |
| **行政层级覆盖** | **4 级**：省、市、区县、乡镇/街道办事处 | **5 级**：省、市、区县、乡镇街道、村委会/居委会 |
| **数据现势性与规范性**| **民政部官方法定标准**，覆盖全部最新撤县设区、乡镇合并变动 | 历史静态镜像（2017），数据较老 |
| **街道办事处完整度**| **极高**（全国实测 **41,351 条** 乡镇街道全部齐全） | 历史数据街道严重缺失 |
| **网络稳定性与策略**| 线上 API + 内置 1.9MB 权威快照自动熔断降级，**100% 成功率** | 官方网站防爬风控极严（易报 502/503），需长休眠 |
| **全国落库耗时** | 开启快照模式约 **20 秒**；线上直连视网络约 3-5 分钟 | 约需 **1 小时**（单 IP 必须限制休眠防止封禁） |
| **断点自愈适应性** | 完美支持省、市、县、街道多级差集补偿下钻 | 支持五级全量树状差集补偿 |

---

**系列专题导航**：
- [01 - 系统全局架构与核心机制](01-architecture.md)
- [02 - 断点续爬与差集自愈机制](02-refire-mechanism.md)
- [03 - 接口详细规范与在线调试](03-api-guide.md)
- [04 - 数据库与缓存拓扑设计规范](04-database-and-cache.md)
- [05 - 生产部署与运维全景指南](05-deployment-guide.md)
- [06 - 全链路自动化测试与质量保障](06-testing-and-validation.md)
- [⬅️ 返回项目主页 (README.md)](../README.md)

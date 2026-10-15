# GB2260 专题文档 04 - 数据库与缓存拓扑设计规范

本文档详尽说明 MySQL 关系型持久化表结构设计、字段说明、索引规范以及 Redis 缓存键拓扑设计。

---

## 目录

- [一、MySQL 表结构体系与设计规范](#一mysql-表结构体系与设计规范)
  - [1. 表关系拓扑图](#1-表关系拓扑图)
  - [2. 实体表详细结构](#2-实体表详细结构)
  - [3. 历史审计流水表 (`crawl_history`)](#3-历史审计流水表-crawl_history)
  - [4. 数据容量指标](#4-数据容量指标)
- [二、Redis 键结构规范与缓存拓扑](#二redis-键结构规范与缓存拓扑)
  - [1. 实体字典表 (`Hash`)](#1-实体字典表-hash)
  - [2. 层级下辖关系索引 (`Set`)](#2-层级下辖关系索引-set)
  - [3. CLI 交互实战命令](#3-cli-交互实战命令)

---

## 一、MySQL 表结构体系与设计规范

数据库初始化脚本完整位于：[`src/main/resources/db.sql`](file:///C:/Users/Benjamin/orca/workspaces/gb2260/analyse/src/main/resources/db.sql)

### 1. 表关系拓扑图

```
province (code[PK], name)
   │
   └── city (code[PK], name, province_code[IDX])
         │
         └── county (code[PK], name, province_code[IDX], city_code[IDX])
               │
               └── town (code[PK], name, province_code, city_code[IDX], county_code[IDX])
                     │
                     └── village (code[PK], name, province_code, city_code, county_code[IDX], town_code[IDX], category_code)

crawl_history (id[PK], task_flag[IDX], data_source, province_filter, is_offline_first, start_time[IDX], end_time, duration_ms, province_count, city_count, county_count, town_count, village_count, status, error_message)
```

### 2. 实体表详细结构

所有实体类均已在 `code` 属性上显式标注 MyBatis-Plus `@TableId` 注解，确保 `selectById` 与 `updateById` 准确生效。

- **`province`（省级行政区划表）**
  - `code`：VARCHAR(12) NOT NULL PRIMARY KEY，12 位标准行政区划代码（如 `110000000000`）；
  - `name`：VARCHAR(100) NOT NULL，省份名称（如 `北京市`、`广东省`）。
- **`city`（地级市行政区划表）**
  - `code`：VARCHAR(12) NOT NULL PRIMARY KEY；
  - `name`：VARCHAR(100) NOT NULL；
  - `province_code`：VARCHAR(12) NOT NULL，所属省级代码，建立 B-Tree 索引。
- **`county`（区县级行政区划表）**
  - `code`：VARCHAR(12) NOT NULL PRIMARY KEY；
  - `name`：VARCHAR(100) NOT NULL；
  - `province_code`：VARCHAR(12) NOT NULL，建立索引；
  - `city_code`：VARCHAR(12) NOT NULL，建立索引。
- **`town`（乡镇/街道办事处表）**
  - `code`：VARCHAR(12) NOT NULL PRIMARY KEY；
  - `name`：VARCHAR(100) NOT NULL；
  - `province_code`：VARCHAR(12) NOT NULL；
  - `city_code`：VARCHAR(12) NOT NULL，建立索引；
  - `county_code`：VARCHAR(12) NOT NULL，建立索引（保证 `groupBy` 聚合毫秒级响应）。
- **`village`（居委会/村委会表，仅统计局模式）**
  - `code`：VARCHAR(12) NOT NULL PRIMARY KEY；
  - `name`：VARCHAR(100) NOT NULL；
  - `county_code`：VARCHAR(12) NOT NULL，建立索引；
  - `town_code`：VARCHAR(12) NOT NULL，建立索引；
  - `category_code`：VARCHAR(10)，城乡分类代码（如 `111` 主城区、`220` 村庄等）。

### 3. 历史审计流水表 (`crawl_history`)

持久化于实体类 [`CrawlHistory.java`](file:///C:/Users/Benjamin/orca/workspaces/gb2260/analyse/src/main/java/org/codework/tools/gb2260/entity/CrawlHistory.java)：
- `id`：BIGINT AUTO_INCREMENT PRIMARY KEY；
- `task_flag`：VARCHAR(64) NOT NULL UNIQUE，批次标识（全量为时间戳毫秒数，续爬为 `refire-{timestamp}`）；
- `data_source`：VARCHAR(32)，数据源引擎（`mca` 或 `stats`）；
- `province_filter`：VARCHAR(255)，省份前缀过滤参数（空代表全量全国）；
- `is_offline_first`：TINYINT(1)，是否开启离线快照优先；
- `start_time` / `end_time`：DATETIME，任务启动与收敛结束时间戳；
- `duration_ms`：BIGINT，总耗时（毫秒）；
- `province_count` / `city_count` / `county_count` / `town_count` / `village_count`：各级实际入库条数；
- `status`：VARCHAR(32)，终态标识（`RUNNING` / `SUCCESS` / `TERMINATED` / `FAILED`）；
- `error_message`：TEXT，异常摘要或主动中止原因说明。

### 4. 数据容量指标

| 数据库表 | 民政部模式条数 (`mca`) | 统计局模式条数 (`stats`) |
| :--- | :---: | :---: |
| `province` (省级) | **34** 条 | **34** 条 |
| `city` (地级市) | **341** 条 | **341** 条 |
| `county` (区县) | **2,974** 条 | **2,974** 条 |
| `town` (乡镇街道) | **41,351** 条 | 约 35,000 条 (历史数据缺失严重) |
| `village` (居委会/村委会) | 无 (民政部法定至街道) | **约 65~70 万条** |

---

## 二、Redis 键结构规范与缓存拓扑

系统采用内存级 Hash 与 Set 复合结构，默认存放于 `database: 10`。

### 1. 实体字典表 (`Hash`)
- **命名规范**：`gb2260:area:{layer}`
- **示例**：
  - `gb2260:area:province`
  - `gb2260:area:city`
  - `gb2260:area:county`
  - `gb2260:area:town`
  - `gb2260:area:village`
- **存储拓扑**：
  - `Field`: 12 位标准编码（如 `110101001000`）
  - `Value`: 行政区划名称（如 `东华门街道`）
- **性能优势**：直接实现名称字典与单条详情 $O(1)$ 常数时间查询。

### 2. 层级下辖关系索引 (`Set`)
- **命名规范**：`gb2260:area:index:{layer}:{parentCode}`
- **示例**：
  - `gb2260:area:index:city:110000000000`（北京市下属城市索引）
  - `gb2260:area:index:county:110100000000`（市辖区下属区县索引）
  - `gb2260:area:index:town:110101000000`（东城区下属街道索引）
- **存储拓扑**：
  - `Member`: 属于该父级的所有子节点 12 位标准代码集合
- **性能优势**：级联接口直读 Set 集合，毫秒级读取并排序输出，无需关系型复杂 Join 计算。

### 3. CLI 交互实战命令

```bash
# 查看全国省份数量
redis-cli -n 10 HLEN gb2260:area:province

# 查询东城区下辖所有街道代码
redis-cli -n 10 SMEMBERS gb2260:area:index:town:110101000000

# 穿透查询特定街道名称
redis-cli -n 10 HGET gb2260:area:town 110101001000
```

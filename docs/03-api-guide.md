# GB2260 专题文档 03 - 接口详细规范与在线调试指南

本文档汇编 GB2260 系统的全部 HTTP RESTful API 接口规范、参数说明、响应示例及 Swagger 在线调试方法。

---

## 目录

- [一、Swagger 在线 API 调试文档](#一swagger-在线-api-调试文档)
- [二、Web 控制台与管理查询接口 (`/api/dashboard`)](#二web-控制台与管理查询接口-apidashboard)
- [三、爬虫调度与续爬控制接口 (`/api/run`)](#三爬虫调度与续爬控制接口-apirun)
- [四、级联数据查询接口 (`/api/get`)](#四级联数据查询接口-apiget)

---

## 一、Swagger 在线 API 调试文档

系统集成了 **Swagger 2 (`springfox-swagger2` 2.9.2)**，提供了可视化接口文档与在线调试沙箱。

- **Swagger UI 访问入口**：`http://localhost:8080/swagger-ui.html`
- **OpenAPI v2 Spec 地址**：`http://localhost:8080/v2/api-docs`
- 核心配置类：[`SwaggerConfig.java`](file:///C:/Users/Benjamin/orca/workspaces/gb2260/analyse/src/main/java/org/codework/tools/gb2260/config/swagger/SwaggerConfig.java)

---

## 二、Web 控制台与管理查询接口 (`/api/dashboard`)

控制器实现：[`DashboardController.java`](file:///C:/Users/Benjamin/orca/workspaces/gb2260/analyse/src/main/java/org/codework/tools/gb2260/controller/DashboardController.java)

| 请求方式 | 接口路径 | 接口描述 | 参数说明 |
| :---: | :--- | :--- | :--- |
| `GET` | `/api/dashboard/stats` | 获取系统整体概览指标 | 无参数。返回 MySQL 与 Redis 各级数量、同步一致性与线程池遥测指标 |
| `GET` | `/api/dashboard/data` | 多维按需查询行政区划数据 | `level`（province/city/county/town/village）、`keyword`（代码或名称）、`parentCode`、`page`、`size` |
| `GET` | `/api/dashboard/history` | 分页查询抓取历史审计记录 | `page`（页码）、`size`（每页条数），返回历史任务批次流水与各级入库指标 |
| `GET` | `/api/dashboard/redis/probe` | Redis 缓存代码一键穿透探针 | `code`（12位全码或短码），穿透返回 Hash 实体、所属层级及 Set 子节点索引 |

#### 概览指标响应示例 (`GET /api/dashboard/stats`)：
```json
{
  "statusCode": 200,
  "message": "OK",
  "dbCounts": {
    "province": 34,
    "city": 341,
    "county": 2974,
    "town": 41351,
    "village": 0
  },
  "redisCounts": {
    "province": 34,
    "city": 341,
    "county": 2974,
    "town": 41351,
    "village": 0
  },
  "isSynced": true,
  "syncRate": "100.0%",
  "inFlightTasks": 0,
  "running": false
}
```

---

## 三、爬虫调度与续爬控制接口 (`/api/run`)

控制器实现：[`RunController.java`](file:///C:/Users/Benjamin/orca/workspaces/gb2260/analyse/src/main/java/org/codework/tools/gb2260/controller/RunController.java)

| 请求方式 | 接口路径 | 接口描述 | 说明 |
| :---: | :--- | :--- | :--- |
| `GET` | `/api/run/start` | 启动全量抓取流水线 | 参数：`dataSource`、`provinceFilter`、`offlineFirst`、`sleepMin`、`sleepMax`、`refire`。若 `refire=true` 转为断点续爬，否则前置清空库表与缓存 |
| `GET` | `/api/run/refire` | **触发断点续爬补偿 (Refire)** | **非破坏性运行**：保留已有数据，扫描缺失层级并执行靶向补偿抓取 |
| `GET` | `/api/run/query` | 实时遥测在途任务与线程指标 | 返回当前线程池是否运行中、完成任务数、总任务数、在途未完成任务数等 |
| `GET` | `/api/run/termination` | 紧急强制终止当前任务 | 发送 `shutdownNow` 信号中断线程池排队与在途任务并回写审计记录 |

#### 进度查询响应示例 (`GET /api/run/query`)：
```json
{
  "statusCode": 200,
  "message": "OK",
  "running": true,
  "completeCount": 58912,
  "taskCount": 89401,
  "activeCount": 68,
  "inFlightCount": 30489
}
```

---

## 四、级联数据查询接口 (`/api/get`)

控制器实现：[`GetController.java`](file:///C:/Users/Benjamin/orca/workspaces/gb2260/analyse/src/main/java/org/codework/tools/gb2260/controller/GetController.java)

面向 Web 前端下拉级联组件（如 Element UI Cascader），直读 Redis 缓存，毫秒级响应，按 12 位标准编码升序排列。

| 请求方式 | 接口路径 | 接口描述 | 路径参数示例 |
| :---: | :--- | :--- | :--- |
| `GET` | `/api/get/province` | 获取全国 34 个省级行政区字典 | 无参数，返回全国各省全量哈希映射 |
| `GET` | `/api/get/{province}/city` | 获取指定省份下属城市列表 | `{province}` 支持短码 `44` 或全码 `440000000000` |
| `GET` | `/api/get/{city}/county` | 获取指定城市下属区县列表 | `{city}` 支持短码 `4401` 或全码 `440100000000` |
| `GET` | `/api/get/{county}/town` | 获取指定区县下属街道/乡镇列表 | `{county}` 支持短码 `440106` 或全码 `440106000000` |
| `GET` | `/api/get/{town}/village` | 获取指定街道下属居委/村委会列表 | `{town}` 支持短码 `440106001` 或全码 `440106001000` |

#### 下属街道查询响应示例 (`GET /api/get/110101000000/town`)：
```json
{
  "statusCode": 200,
  "message": "OK",
  "body": [
    { "code": "110101001000", "name": "东华门街道" },
    { "code": "110101002000", "name": "景山街道" },
    { "code": "110101003000", "name": "交道口街道" },
    { "code": "110101004000", "name": "安定门街道" },
    { "code": "110101005000", "name": "北新桥街道" }
  ]
}
```

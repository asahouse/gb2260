# GB2260 专题文档 05 - 生产部署与运维全景指南

本文档汇编生产环境下针对 MySQL、Redis、独立 Jar、Docker 容器、Docker Compose 全栈编排及 Linux Systemd 守护进程的完整部署流程与运维手册。

> **导航**：[⬅️ 返回项目主页 (README.md)](../README.md)

---

## 目录

- [一、数据库 (MySQL) 的使用与部署规范](#一数据库-mysql-的使用与部署规范)
- [二、缓存 (Redis) 的使用与运维规范](#二缓存-redis-的使用与运维规范)
- [三、部署方式一：Jar 独立进程部署 (Linux / Windows)](#三部署方式一jar-独立进程部署-linux--windows)
- [四、部署方式二：Docker 容器化部署](#四部署方式二docker-容器化部署)
- [五、部署方式三：Docker Compose 一键全栈编排部署 (推荐)](#五部署方式三docker-compose-一键全栈编排部署-推荐)
- [六、部署方式四：Linux Systemd 系统服务守护配置](#六部署方式四linux-systemd-系统服务守护配置)
- [七、监控检查、生产调优与端口一览](#七监控检查生产调优与端口一览)

---

## 一、数据库 (MySQL) 的使用与部署规范

系统持久层依托 MySQL 关系型数据库存储五级行政区划与爬虫执行审计流水，配置与使用规范如下：

1. **字符集与排序规则**：
   - **强制要求**：字符集必须为 `utf8mb4`，排序规则建议 `utf8mb4_unicode_ci`；
   - **原因**：我国部分偏远地区、少数民族自治区及特殊行政区划地名中包含生僻汉字（如“𰚼”、“䴙”等），若使用普通 `utf8`（utf8mb3）会导致持久化时报 `Incorrect string value` 异常截断。
2. **初始化建表与数据脚本**：
   - 建表脚本位于项目源码中：[`src/main/resources/db.sql`](../src/main/resources/db.sql)；
   - 脚本包含 `province`（省）、`city`（市）、`county`（县区）、`town`（乡镇街道）、`village`（村居委）以及 `crawl_history`（历史审计表）共 6 张数据表；
   - 命令行一键初始化命令：
     ```bash
     mysql -h <DB_HOST> -P <DB_PORT> -u <DB_USER> -p<DB_PWD> \
       -e "CREATE DATABASE IF NOT EXISTS gb2260 DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
     mysql -h <DB_HOST> -P <DB_PORT> -u <DB_USER> -p<DB_PWD> -Dgb2260 < src/main/resources/db.sql
     ```
3. **连接池与连接数配置 (Alibaba Druid)**：
   - 针对高并发多线程下钻采集（峰值并发达 80~100 线程），Druid 连接池配置建议：
     - `initial-size`: 10
     - `max-active`: 100
     - `min-idle`: 10
     - `max-wait`: 60000ms
   - MySQL 实例端配置要求：请确保 MySQL 的 `max_connections >= 300`，避免并发采集时触发 `Too many connections`。
4. **Druid 运维监控台**：
   - 系统内置了 Druid StatViewServlet，启动后直接访问：`http://localhost:8080/druid`；
   - 可实时观测 SQL 执行耗时、并发连接数分布、慢查询与连接池命中率。

---

## 二、缓存 (Redis) 的使用与运维规范

系统引入 Redis 作为前端高性能毫秒级级联查询引擎，设计与运维规范如下：

1. **Redis 实例与数据库分区选择**：
   - **默认数据库**：推荐使用独立 DB（系统默认配置为 `database: 10`），便于数据隔离与一键重置；
   - **内存容量估算**：
     - **民政部模式 (四级至乡镇街道)**：全国 4.4 万条数据，Redis 内存占用仅约 **15MB ~ 25MB**；
     - **统计局模式 (五级至居委会)**：全国近 70 万条数据，Redis 内存占用约 **120MB ~ 160MB**；
     - 生产环境建议为 Redis 实例预留不少于 512MB 可用内存。
2. **两种核心键结构规范与 CLI 常用排查命令**：
   - **实体字典表 (`Hash`)**：
     - Key：`gb2260:area:{layer}`（如 `gb2260:area:province`、`gb2260:area:town`）
     - Field：12 位行政区划标准编码（如 `110101001000`）
     - Value：行政区名称（如 `东华门街道`）
     - CLI 验证：
       ```bash
       redis-cli -n 10 HGET gb2260:area:town 110101001000
       # 返回: "东华门街道"
       redis-cli -n 10 HLEN gb2260:area:town
       # 返回: (integer) 41351
       ```
   - **层级下辖关系索引 (`Set`)**：
     - Key：`gb2260:area:index:{layer}:{parentCode}`（如 `gb2260:area:index:town:110101000000`）
     - Member：该上级下属的所有子级 12 位代码集合
     - CLI 验证：
       ```bash
       redis-cli -n 10 SMEMBERS gb2260:area:index:town:110101000000
       # 返回该区县下辖所有街道代码列表
       redis-cli -n 10 SCARD gb2260:area:index:town:110101000000
       # 返回下辖街道数量
       ```
3. **持久化与高可用配置推荐**：
   - 生产环境中建议开启 AOF（`appendonly yes`，`appendfsync everysec`），防止 Redis 节点偶发重启导致级联数据丢失。

---

## 三、部署方式一：Jar 独立进程部署 (Linux / Windows)

适用于裸金属服务器、虚拟机或开发者本地快速部署运行。

#### 1. 环境准备与编译构建
- 确保已安装 JDK 1.8+ 与 Maven 3.6+；
- 执行构建打包：
  ```bash
  # 编译并打包为可执行 Fat Jar
  mvn clean package -DskipTests
  
  # 构建产物位于: target/gb2260-0.0.1-SNAPSHOT.jar
  ```

#### 2. 前台调试运行
```bash
java -Dfile.encoding=UTF-8 -jar target/gb2260-0.0.1-SNAPSHOT.jar
```

#### 3. 生产后台守护运行 (Linux nohup)
```bash
nohup java -Xms512m -Xmx2048m -Dfile.encoding=UTF-8 \
  -jar target/gb2260-0.0.1-SNAPSHOT.jar \
  > gb2260.log 2>&1 &

# 查看实时日志
tail -f gb2260.log
```

#### 4. 环境变量外部化传参覆盖 (无缝适配不同生产环境)
应用核心参数全部支持环境变量或命令行 `-D` 参数动态重载，无需修改 Jar 内配置文件：
```bash
java -Xms512m -Xmx2048m \
  -DDB_HOST=192.168.1.100 \
  -DDB_PORT=3306 \
  -DDB_USERNAME=prod_user \
  -DDB_PASSWORD=ProdPass123! \
  -DREDIS_HOST=192.168.1.101 \
  -DREDIS_PORT=6379 \
  -DREDIS_PASSWORD=RedisPass123! \
  -DGB2260_SOURCE=mca \
  -DMCA_OFFLINE_FIRST=true \
  -jar target/gb2260-0.0.1-SNAPSHOT.jar
```

---

## 四、部署方式二：Docker 容器化部署

项目根目录已内置轻量级 [`Dockerfile`](../Dockerfile)，基于 Alpine Linux + OpenJDK 8 JRE 构建，体积精简。

#### 1. 构建 Docker 镜像
```bash
# 确保已先完成本地 maven package
mvn clean package -DskipTests

# 构建名为 gb2260 的 Docker 镜像
docker build -t gb2260:v1.0.0 .
```

#### 2. 运行 Docker 容器
通过 `-e` 注入中间件连接凭证，通过 `-p` 映射服务端口：
```bash
docker run -d \
  --name gb2260-app \
  -p 8080:8080 \
  -e DB_HOST="172.17.0.1" \
  -e DB_PORT="3306" \
  -e DB_USERNAME="root" \
  -e DB_PASSWORD="your_mysql_password" \
  -e REDIS_HOST="172.17.0.1" \
  -e REDIS_PORT="6379" \
  -e REDIS_PASSWORD="your_redis_password" \
  -e GB2260_SOURCE="mca" \
  -e MCA_OFFLINE_FIRST="true" \
  --restart always \
  gb2260:v1.0.0
```

#### 3. 查看容器日志
```bash
docker logs -f gb2260-app
```

---

## 五、部署方式三：Docker Compose 一键全栈编排部署 (推荐)

项目根目录下提供了开箱即用的 [`docker-compose.yml`](../docker-compose.yml)，内置 MySQL 5.7（自动挂载并执行 `db.sql` 初始化）、Redis 6（带 AOF 持久化与密码保护）以及 GB2260 应用程序，三者处于同一容器专用网桥中，一键拉起整套集群。

#### 1. 一键启动全栈服务
```bash
# 后台拉起 mysql、redis 与 gb2260-app 全量服务
docker-compose up -d --build
```

#### 2. 检查全栈运行状态
```bash
docker-compose ps
```
输出示例：
```text
    Name                  Command               State           Ports
-----------------------------------------------------------------------------
gb2260-app     sh -c java $JAVA_OPTS -jar ...   Up      0.0.0.0:8080->8080/tcp
gb2260-mysql   docker-entrypoint.sh --cha ...   Up      0.0.0.0:3306->3306/tcp
gb2260-redis   docker-entrypoint.sh redis ...   Up      0.0.0.0:6379->6379/tcp
```

#### 3. 停止或重启服务
```bash
# 停止全栈容器
docker-compose down

# 重启应用服务
docker-compose restart gb2260-app
```

---

## 六、部署方式四：Linux Systemd 系统服务守护配置

在生产 Linux 服务器（CentOS 7+ / Ubuntu 18.04+ / Debian 10+）上，建议将应用托管至 `systemd`，实现开机自启、崩溃自愈与进程监控。

#### 1. 创建服务定义文件
创建 `/etc/systemd/system/gb2260.service`：
```ini
[Unit]
Description=GB2260 Area Snatch and Cascader Service
After=syslog.target network.target mysql.service redis.service

[Service]
Type=simple
User=root
Group=root
WorkingDirectory=/opt/gb2260
Environment="JAVA_HOME=/usr/lib/jvm/java-1.8.0-openjdk"
Environment="DB_HOST=127.0.0.1"
Environment="DB_PORT=3306"
Environment="DB_PASSWORD=YourPassword"
Environment="REDIS_HOST=127.0.0.1"
Environment="REDIS_PORT=6379"
ExecStart=/usr/bin/java -Xms512m -Xmx2048m -Dfile.encoding=UTF-8 -jar /opt/gb2260/gb2260-0.0.1-SNAPSHOT.jar
Restart=always
RestartSec=10
StandardOutput=syslog
StandardError=syslog
SyslogIdentifier=gb2260

[Install]
WantedBy=multi-user.target
```

#### 2. 服务管理常用命令
```bash
# 重新加载服务定义
systemctl daemon-reload

# 设置开机自启并立即启动
systemctl enable --now gb2260

# 查看运行状态
systemctl status gb2260

# 重启或停止服务
systemctl restart gb2260
systemctl stop gb2260

# 查看 journalctl 实时日志
journalctl -u gb2260 -f
```

---

## 七、监控检查、生产调优与端口一览

### 1. 核心端口与服务地址总览

| 模块名称 | 访问地址 | 默认端口 | 说明 |
| :--- | :--- | :---: | :--- |
| **Web 可视化管理控制台** | `http://<IP>:8080/dashboard` | 8080 | 综合遥测监控、按需检索、缓存探针与联动沙箱 |
| **Swagger 在线 API 文档** | `http://<IP>:8080/swagger-ui.html` | 8080 | 交互式 REST API 接口文档与在线调试 |
| **Druid 数据库连接池监控** | `http://<IP>:8080/druid` | 8080 | 监控 SQL 执行性能、连接状态与慢查询 |
| **任务遥测健康接口** | `http://<IP>:8080/api/run/query` | 8080 | 返回当前是否有运行任务、在途任务数与完成计数 |
| **MySQL 关系型数据库** | `tcp://<IP>:3306` | 3306 | 业务数据持久层 |
| **Redis 内存缓存** | `tcp://<IP>:6379` | 6379 | 高并发毫秒级级联缓存 |

### 2. 生产 JVM 堆内存与 GC 参数建议
在单机多线程全量并发处理 8.9 万个子任务时，峰值对象创建较为集中，推荐参数：
```bash
-server -Xms1024m -Xmx2048m -XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=256m \
-XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+PrintGCDetails -XX:+PrintGCDateStamps \
-Dfile.encoding=UTF-8
```

---

**系列专题导航**：
- [01 - 系统全局架构与核心机制](01-architecture.md)
- [02 - 断点续爬与差集自愈机制](02-refire-mechanism.md)
- [03 - 接口详细规范与在线调试](03-api-guide.md)
- [04 - 数据库与缓存拓扑设计规范](04-database-and-cache.md)
- [05 - 生产部署与运维全景指南](05-deployment-guide.md)
- [06 - 全链路自动化测试与质量保障](06-testing-and-validation.md)
- [⬅️ 返回项目主页 (README.md)](../README.md)

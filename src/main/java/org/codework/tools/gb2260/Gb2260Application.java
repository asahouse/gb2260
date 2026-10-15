package org.codework.tools.gb2260;

import okhttp3.OkHttpClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.retry.annotation.EnableRetry;

import java.util.concurrent.TimeUnit;

/**
 * GB2260 全国行政区划数据爬虫与查询服务启动主类
 * <p>
 * 运行环境依赖：
 * 1. MySQL (存储关系型 5 级行政区划: province, city, county, town, village)
 * 2. Redis (存储面向前端级联选择器的 Hash 结构及 Set 索引)
 * <p>
 * 核心调度流程：
 * 1. 通过 GET /api/run/start 接口启动抓取流水线，每次启动前自动 TRUNCATE MySQL 表结构并 FLUSHDB Redis。
 * 2. 通过 GET /api/run/query 接口实时遥测在途任务、活跃线程数与已完成总数。
 * 3. 通过 GET /api/run/termination 接口发出 shutdownNow 信号紧急中止爬虫任务。
 * 4. 访问 Swagger 在线 API 文档：http://localhost:8080/swagger-ui.html
 * <p>
 * 数据源双引擎架构：
 * 1. 方案一【国家统计局模式 (stats)】：
 *    - 抓取统计局官方静态 HTML 镜像（5级全量至居委会/村委会，约 70 万条记录）。
 *    - 注意：统计局网站设有限频风控（单 IP 阈值控制，超频返回 502/503），系统提供 sleepMin/sleepMax 控制访问间距，全量需 1 小时左右。
 * 2. 方案二【民政部国家地名信息库模式 (mca)】（推荐）：
 *    - 对接民政部官方 API（https://dmfw.mca.gov.cn/9095/xzqh/getList），覆盖法定四级（省、市、区县、街道乡镇）。
 *    - 内置标准权威离线快照与智能熔断降级机制，支持 20 秒极速入库 4.1 万条全量全国街道乡镇数据。
 * <p>
 * 历史演进与架构决策说明：
 * 1. 单线程递归方式（原 AreaSnatchMethodHandler）：递归所有下级节点被局限在单线程中，效率极低，已废弃。
 * 2. Redis Pub/Sub 方式：由于 Spring 与 Jedis 注入易出现循环依赖，且单机进程内分发吞吐不及 Guava EventBus，已重构。
 * 3. HTTP 客户端选型：对于统计局 GBK 静态网页使用标准 HttpURLConnection 处理编码；对于民政部 API 及高并发请求使用 OkHttpClient。
 *
 * @author benjaminkc
 */
@EnableRetry
@SpringBootApplication
public class Gb2260Application {

    public static void main(String[] args) {
        SpringApplication.run(Gb2260Application.class, args);
    }

    /**
     * 配置全局通用的 OkHttpClient Bean 实例
     * 配置合理的连接、写入与读取超时时间
     *
     * @return OkHttpClient 实例
     */
    @Bean
    public OkHttpClient okHttpClient() {
        return new OkHttpClient().newBuilder()
                .connectTimeout(50, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
    }
}

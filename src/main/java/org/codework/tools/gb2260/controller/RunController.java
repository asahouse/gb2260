package org.codework.tools.gb2260.controller;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import lombok.extern.java.Log;
import org.codework.tools.gb2260.mapper.DDLMapper;
import org.codework.tools.gb2260.snatch.AreaLayer;
import org.codework.tools.gb2260.snatch.AreaSnatchHandler;
import org.codework.tools.gb2260.snatch.guava.RunThreadPoolExecutor;
import io.swagger.annotations.ApiParam;
import org.codework.tools.gb2260.service.CrawlHistoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 爬虫任务调度与监控控制器
 * <p>
 * 提供爬虫任务异步启动、在途进度指标实时查询、以及紧急中止控制等 RESTful 接口。
 * 支持双重防重入保护，并在启动时前置清空 MySQL 5级表结构与 Redis 缓存。
 *
 * @author benjaminkc
 */
@Api(tags = "爬虫任务调度控制接口")
@Log
@RestController
@RequestMapping("/api/run")
public class RunController {

    /**
     * 任务运行态防重入原子标志
     */
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    private final AreaSnatchHandler handler;
    private final ConcurrentHashMap<String, RunThreadPoolExecutor> executors;
    private final DDLMapper ddlMapper;
    private final JedisPool jedisPool;

    @Autowired(required = false)
    private CrawlHistoryService crawlHistoryService;

    /**
     * 构造函数注入依赖
     *
     * @param handler    抓取核心处理器
     * @param executors  并发执行器集中管理表
     * @param ddlMapper  表清空 DDL 数据访问对象
     * @param jedisPool  Redis 客户端连接池
     */
    public RunController(AreaSnatchHandler handler,
                         ConcurrentHashMap<String, RunThreadPoolExecutor> executors,
                         DDLMapper ddlMapper,
                         JedisPool jedisPool) {
        this.handler = handler;
        this.executors = executors;
        this.ddlMapper = ddlMapper;
        this.jedisPool = jedisPool;
    }

    /**
     * 触发行政区划数据抓取流水线
     * <p>
     * 异步流水线步骤：
     * 1. 检查是否存在运行中的执行器，防止并发冲突与重复抓取
     * 2. TRUNCATE 历史数据库表（province, city, county, town, village）
     * 3. 清空 Redis 当前 DB 缓存
     * 4. 触发 AreaSnatchHandler 开始多线程下钻采集
     *
     * @param dataSource     数据源引擎（mca / stats）
     * @param provinceFilter 省份过滤参数
     * @param offlineFirst   是否离线快照优先
     * @param sleepMin       随机休眠下限
     * @param sleepMax       随机休眠上限
     * @return 响应结果（若正在运行则返回 msg: running）
     */
    /**
     * 触发行政区划数据抓取流水线（默认参数重载）
     *
     * @return 响应结果
     */
    public Response start() {
        return start(null, null, null, null, null, null);
    }

    @ApiOperation(value = "启动全量数据抓取任务", notes = "清空现有库表与Redis缓存，并启动后台异步并发爬虫任务。若设置 refire=true 则转为断点续爬模式")
    @GetMapping("start")
    public Response start(
            @ApiParam(value = "数据源引擎(mca/stats)", required = false) @RequestParam(required = false) String dataSource,
            @ApiParam(value = "省份代码前缀过滤(例如: 11,44)", required = false) @RequestParam(required = false) String provinceFilter,
            @ApiParam(value = "是否优先使用离线快照", required = false) @RequestParam(required = false) Boolean offlineFirst,
            @ApiParam(value = "最小随机休眠毫秒数", required = false) @RequestParam(required = false) Integer sleepMin,
            @ApiParam(value = "最大随机休眠毫秒数", required = false) @RequestParam(required = false) Integer sleepMax,
            @ApiParam(value = "是否启用断点续爬补偿模式(默认false)", required = false) @RequestParam(required = false) Boolean refire) {

        if (Boolean.TRUE.equals(refire)) {
            return refire(dataSource, provinceFilter, offlineFirst, sleepMin, sleepMax);
        }

        if (StringUtils.hasText(dataSource)) {
            handler.setDataSource(dataSource);
        }
        if (provinceFilter != null) {
            handler.setProvinceFilter(provinceFilter);
        }
        if (offlineFirst != null) {
            handler.setOfflineFirst(offlineFirst);
        }
        if (sleepMin != null) {
            handler.setMcaSleepMin(sleepMin);
        }
        if (sleepMax != null) {
            handler.setMcaSleepMax(sleepMax);
        }

        // 清理已结束的历史执行器实例
        executors.entrySet().removeIf(item -> item.getValue().isShutdown());

        // 双重防重入校验：原子布尔变量与活跃执行器检测
        boolean hasRunningExecutor = executors.values().stream().anyMatch(item -> !item.isShutdown());
        if (hasRunningExecutor || !isRunning.compareAndSet(false, true)) {
            return Response.ok("msg", "running");
        }

        CompletableFuture.runAsync(this::truncateAll)
                .thenRunAsync(this::flushDB)
                .thenRunAsync(() -> handler.snatch())
                .exceptionally(ex -> {
                    log.warning("爬虫抓取流水线启动异常: " + ex.getMessage());
                    isRunning.set(false);
                    return null;
                });

        return Response.ok();
    }

    /**
     * 触发断点续爬补偿流水线（方案一：层级差集推导与补偿下钻）
     * <p>
     * 核心特性：
     * 1. 绝对不执行 truncateAll() 与 flushDB()，保留现有数据库与 Redis 数据成果；
     * 2. 深度扫描省、市、县、镇各级数据是否存在断点/缺失，针对残缺节点重新发布下钻抓取事件；
     * 3. 依赖已改造的幂等持久化机制（存在则更新，不存在则插入），杜绝主键冲突；
     * 4. 自动记录独立的 refire 历史审计批次。
     *
     * @param dataSource     数据源引擎（mca / stats）
     * @param provinceFilter 省份过滤参数
     * @param offlineFirst   是否离线快照优先
     * @param sleepMin       随机休眠下限
     * @param sleepMax       随机休眠上限
     * @return 响应结果
     */
    @ApiOperation(value = "断点续爬补偿重放 (Refire)", notes = "不执行 truncateTable 与 flushDB，保留现有成果，深度扫描现有数据库与缓存缺失节点并触发增量下钻补偿")
    @GetMapping("refire")
    public Response refire(
            @ApiParam(value = "数据源引擎(mca/stats)", required = false) @RequestParam(required = false) String dataSource,
            @ApiParam(value = "省份代码前缀过滤(例如: 11,44)", required = false) @RequestParam(required = false) String provinceFilter,
            @ApiParam(value = "是否优先使用离线快照", required = false) @RequestParam(required = false) Boolean offlineFirst,
            @ApiParam(value = "最小随机休眠毫秒数", required = false) @RequestParam(required = false) Integer sleepMin,
            @ApiParam(value = "最大随机休眠毫秒数", required = false) @RequestParam(required = false) Integer sleepMax) {

        if (StringUtils.hasText(dataSource)) {
            handler.setDataSource(dataSource);
        }
        if (provinceFilter != null) {
            handler.setProvinceFilter(provinceFilter);
        }
        if (offlineFirst != null) {
            handler.setOfflineFirst(offlineFirst);
        }
        if (sleepMin != null) {
            handler.setMcaSleepMin(sleepMin);
        }
        if (sleepMax != null) {
            handler.setMcaSleepMax(sleepMax);
        }

        // 清理已结束的历史执行器实例
        executors.entrySet().removeIf(item -> item.getValue().isShutdown());

        // 双重防重入校验：原子布尔变量与活跃执行器检测
        boolean hasRunningExecutor = executors.values().stream().anyMatch(item -> !item.isShutdown());
        if (hasRunningExecutor || !isRunning.compareAndSet(false, true)) {
            return Response.ok("msg", "running");
        }

        // 启动异步断点续爬补偿流程（绝对不调用 truncateAll 与 flushDB）
        CompletableFuture.runAsync(() -> handler.refire())
                .exceptionally(ex -> {
                    log.warning("断点续爬流水线启动异常: " + ex.getMessage());
                    isRunning.set(false);
                    return null;
                });

        return Response.ok("mode", "refire");
    }

    /**
     * 实时查询当前爬虫任务运行状态与线程池遥测指标
     *
     * @return 包含 running（是否运行中）、completeCount（已完成数）、taskCount（总任务数）、activeCount（活跃线程数）、inFlightCount（在途待处理数）
     */
    @ApiOperation(value = "查询任务实时进度与指标", notes = "返回当前线程池内完成数、总任务数、在途未完成任务数等遥测指标")
    @GetMapping("query")
    public Response query() {
        executors.entrySet().removeIf(item -> item.getValue().isShutdown());
        Optional<Map.Entry<String, RunThreadPoolExecutor>> op = executors.entrySet().stream()
                .filter(item -> !item.getValue().isShutdown()).findFirst();

        if (op.isPresent()) {
            RunThreadPoolExecutor executor = op.get().getValue();
            boolean running = !executor.isShutdown();
            long completeCount = executor.getCompletedTaskCount();
            long taskCount = executor.getTaskCount();
            long activeCount = executor.getActiveCount();
            long inFlightCount = executor.getInFlightTasks();
            return Response.ok("running", running)
                    .add("completeCount", completeCount)
                    .add("taskCount", taskCount)
                    .add("activeCount", activeCount)
                    .add("inFlightCount", inFlightCount);
        } else {
            isRunning.set(false);
            return Response.ok("running", false);
        }
    }

    /**
     * 强制立即终止当前所有运行中的爬虫任务
     *
     * @return 终止结果响应
     */
    @ApiOperation(value = "强制终止当前抓取任务", notes = "发出 shutdownNow 信号中断线程池中所有正在执行与排队中的任务")
    @GetMapping("termination")
    public Response termination() {
        try {
            boolean found = false;
            for (RunThreadPoolExecutor executor : executors.values()) {
                if (!executor.isShutdown()) {
                    executor.shutdownNow();
                    found = true;
                }
            }
            executors.clear();
            isRunning.set(false);
            if (crawlHistoryService != null) {
                crawlHistoryService.recordTermination();
            }
            if (!found) {
                return Response.error("当前无运行中的任务执行器！");
            }
        } catch (RejectedExecutionException ex) {
            log.info("终止执行器拒绝异常: " + ex.getMessage());
        }
        return Response.ok();
    }

    /**
     * 清空五级行政区划基础数据库表
     */
    public void truncateAll() {
        ddlMapper.truncateTable(AreaLayer.Province.name().toLowerCase());
        ddlMapper.truncateTable(AreaLayer.City.name().toLowerCase());
        ddlMapper.truncateTable(AreaLayer.County.name().toLowerCase());
        ddlMapper.truncateTable(AreaLayer.Town.name().toLowerCase());
        ddlMapper.truncateTable(AreaLayer.Village.name().toLowerCase());
    }

    /**
     * 清空当前 Redis 数据库中的所有键值缓存
     */
    public void flushDB() {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.flushDB();
        }
    }
}

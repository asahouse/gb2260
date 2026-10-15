package org.codework.tools.gb2260.controller;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.extern.java.Log;
import org.codework.tools.gb2260.entity.*;
import org.codework.tools.gb2260.mapper.*;
import org.codework.tools.gb2260.service.CrawlHistoryService;
import org.codework.tools.gb2260.snatch.AreaCommon;
import org.codework.tools.gb2260.snatch.AreaLayer;
import org.codework.tools.gb2260.snatch.AreaSnatchHandler;
import org.codework.tools.gb2260.snatch.guava.RunThreadPoolExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 行政区划管理控制台控制器
 * <p>
 * 提供 Dashboard Web 页面路由渲染及多维按需查询、实时遥测、Redis 缓存探针与历史审计 API
 *
 * @author benjaminkc
 */
@Api(tags = "Web 控制台管理与按需查询接口")
@Log
@Controller
public class DashboardController {

    private final ProvinceMapper provinceMapper;
    private final CityMapper cityMapper;
    private final CountyMapper countyMapper;
    private final TownMapper townMapper;
    private final VillageMapper villageMapper;
    private final CrawlHistoryService crawlHistoryService;
    private final AreaSnatchHandler handler;
    private final JedisPool jedisPool;
    private final ConcurrentHashMap<String, RunThreadPoolExecutor> executors;

    @Autowired
    public DashboardController(ProvinceMapper provinceMapper,
                               CityMapper cityMapper,
                               CountyMapper countyMapper,
                               TownMapper townMapper,
                               VillageMapper villageMapper,
                               CrawlHistoryService crawlHistoryService,
                               AreaSnatchHandler handler,
                               JedisPool jedisPool,
                               ConcurrentHashMap<String, RunThreadPoolExecutor> executors) {
        this.provinceMapper = provinceMapper;
        this.cityMapper = cityMapper;
        this.countyMapper = countyMapper;
        this.townMapper = townMapper;
        this.villageMapper = villageMapper;
        this.crawlHistoryService = crawlHistoryService;
        this.handler = handler;
        this.jedisPool = jedisPool;
        this.executors = executors;
    }

    /**
     * 控制台 HTML 页面入口
     *
     * @param model 视图模型
     * @return 模板页面名称
     */
    @GetMapping({"/", "/dashboard"})
    public String dashboard(Model model) {
        model.addAttribute("dataSource", handler.getDataSource());
        model.addAttribute("isOfflineFirst", handler.isOfflineFirst());
        model.addAttribute("provinceFilter", handler.getProvinceFilter());
        return "dashboard";
    }

    /**
     * 查询系统整体概览指标（MySQL 数据量、Redis 缓存量、一致性核验与爬虫运行态）
     *
     * @return 统计指标报文
     */
    @ApiOperation(value = "获取系统整体概览指标与健康状态", notes = "返回 MySQL 与 Redis 存储对比、一致性指标与实时线程池状态")
    @ResponseBody
    @GetMapping("/api/dashboard/stats")
    public Response stats() {
        int dbP = Optional.ofNullable(provinceMapper.selectCount(null)).orElse(0);
        int dbC = Optional.ofNullable(cityMapper.selectCount(null)).orElse(0);
        int dbCo = Optional.ofNullable(countyMapper.selectCount(null)).orElse(0);
        int dbT = Optional.ofNullable(townMapper.selectCount(null)).orElse(0);
        int dbV = Optional.ofNullable(villageMapper.selectCount(null)).orElse(0);
        int totalDb = dbP + dbC + dbCo + dbT + dbV;

        long redisP = 0;
        long redisC = 0;
        long redisCo = 0;
        long redisT = 0;
        long redisV = 0;
        long redisIndexCount = 0;

        try (Jedis jedis = jedisPool.getResource()) {
            redisP = jedis.hlen(AreaCommon.CACHE_SYS_ROOT_PREFIX + AreaLayer.Province.name().toLowerCase());
            redisC = jedis.hlen(AreaCommon.CACHE_SYS_ROOT_PREFIX + AreaLayer.City.name().toLowerCase());
            redisCo = jedis.hlen(AreaCommon.CACHE_SYS_ROOT_PREFIX + AreaLayer.County.name().toLowerCase());
            redisT = jedis.hlen(AreaCommon.CACHE_SYS_ROOT_PREFIX + AreaLayer.Town.name().toLowerCase());
            redisV = jedis.hlen(AreaCommon.CACHE_SYS_ROOT_PREFIX + AreaLayer.Village.name().toLowerCase());
            Set<String> indexKeys = jedis.keys(AreaCommon.CACHE_SYS_INDEX_PREFIX + "*");
            if (indexKeys != null) {
                redisIndexCount = indexKeys.size();
            }
        } catch (Exception e) {
            log.warning("读取 Redis 缓存统计异常: " + e.getMessage());
        }

        long totalRedis = redisP + redisC + redisCo + redisT + redisV;
        boolean isConsistent = (dbP == redisP && dbC == redisC && dbCo == redisCo && dbT == redisT && dbV == redisV);
        double syncRatio = (totalDb == 0 && totalRedis == 0) ? 100.0 :
                (totalDb == 0 ? 0.0 : Math.min(100.0, Math.round(((double) totalRedis / totalDb) * 10000.0) / 100.0));

        // 线程池活跃态检测
        boolean isRunning = executors.values().stream().anyMatch(e -> !e.isShutdown());
        long inFlightCount = 0;
        long completeCount = 0;
        long taskCount = 0;
        long activeCount = 0;

        Optional<Map.Entry<String, RunThreadPoolExecutor>> activeOpt = executors.entrySet().stream()
                .filter(e -> !e.getValue().isShutdown()).findFirst();
        if (activeOpt.isPresent()) {
            RunThreadPoolExecutor executor = activeOpt.get().getValue();
            inFlightCount = executor.getInFlightTasks();
            completeCount = executor.getCompletedTaskCount();
            taskCount = executor.getTaskCount();
            activeCount = executor.getActiveCount();
        }

        Map<String, Object> dbCounts = new LinkedHashMap<>();
        dbCounts.put("province", dbP);
        dbCounts.put("city", dbC);
        dbCounts.put("county", dbCo);
        dbCounts.put("town", dbT);
        dbCounts.put("village", dbV);
        dbCounts.put("total", totalDb);

        Map<String, Object> redisCounts = new LinkedHashMap<>();
        redisCounts.put("province", redisP);
        redisCounts.put("city", redisC);
        redisCounts.put("county", redisCo);
        redisCounts.put("town", redisT);
        redisCounts.put("village", redisV);
        redisCounts.put("index", redisIndexCount);
        redisCounts.put("total", totalRedis);

        return Response.ok()
                .add("dbCounts", dbCounts)
                .add("redisCounts", redisCounts)
                .add("isConsistent", isConsistent)
                .add("syncRatio", syncRatio)
                .add("isRunning", isRunning)
                .add("inFlightCount", inFlightCount)
                .add("completeCount", completeCount)
                .add("taskCount", taskCount)
                .add("activeCount", activeCount)
                .add("currentDataSource", handler.getDataSource())
                .add("isOfflineFirst", handler.isOfflineFirst())
                .add("provinceFilter", handler.getProvinceFilter());
    }

    /**
     * 分页查询爬虫执行历史审计记录
     *
     * @param page 页码（默认 1）
     * @param size 每页条数（默认 10）
     * @return 分页历史记录报文
     */
    @ApiOperation(value = "分页查询抓取历史审计记录", notes = "返回按启动时间倒序排列的抓取任务历史批次流水")
    @ResponseBody
    @GetMapping("/api/dashboard/history")
    public Response history(
            @ApiParam(value = "当前页码", defaultValue = "1") @RequestParam(defaultValue = "1") long page,
            @ApiParam(value = "每页记录数", defaultValue = "10") @RequestParam(defaultValue = "10") long size) {
        Page<CrawlHistory> historyPage = crawlHistoryService.getHistoryPage(page, size);
        return Response.ok("page", historyPage);
    }

    /**
     * MySQL 多维多层级按需分页检索数据
     *
     * @param level      行政层级（province, city, county, town, village）
     * @param keyword    搜索关键词（编码或名称）
     * @param parentCode 所属上级代码
     * @param page       当前页码
     * @param size       每页条数
     * @return 分页数据结果
     */
    @ApiOperation(value = "多维按需查询行政区划数据", notes = "支持按层级切换、关键字模糊检索、上级代码筛选与分页加载")
    @ResponseBody
    @GetMapping("/api/dashboard/data")
    public Response data(
            @ApiParam(value = "行政层级(province/city/county/town/village)", defaultValue = "province")
            @RequestParam(defaultValue = "province") String level,
            @ApiParam(value = "检索关键字(代码或名称)") @RequestParam(required = false) String keyword,
            @ApiParam(value = "上级父节点行政代码") @RequestParam(required = false) String parentCode,
            @ApiParam(value = "当前页码", defaultValue = "1") @RequestParam(defaultValue = "1") long page,
            @ApiParam(value = "每页条数", defaultValue = "10") @RequestParam(defaultValue = "10") long size) {

        String lvl = level.toLowerCase();
        Page<?> resultPage;

        if ("city".equals(lvl)) {
            QueryWrapper<City> qw = new QueryWrapper<>();
            if (StringUtils.hasText(parentCode)) {
                qw.eq("province_code", parentCode);
            }
            if (StringUtils.hasText(keyword)) {
                qw.and(w -> w.like("code", keyword).or().like("name", keyword));
            }
            qw.orderByAsc("code");
            resultPage = (Page<City>) cityMapper.selectPage(new Page<>(page, size), qw);

        } else if ("county".equals(lvl)) {
            QueryWrapper<County> qw = new QueryWrapper<>();
            if (StringUtils.hasText(parentCode)) {
                qw.and(w -> w.eq("city_code", parentCode).or().eq("province_code", parentCode));
            }
            if (StringUtils.hasText(keyword)) {
                qw.and(w -> w.like("code", keyword).or().like("name", keyword));
            }
            qw.orderByAsc("code");
            resultPage = (Page<County>) countyMapper.selectPage(new Page<>(page, size), qw);

        } else if ("town".equals(lvl)) {
            QueryWrapper<Town> qw = new QueryWrapper<>();
            if (StringUtils.hasText(parentCode)) {
                qw.and(w -> w.eq("county_code", parentCode).or().eq("city_code", parentCode));
            }
            if (StringUtils.hasText(keyword)) {
                qw.and(w -> w.like("code", keyword).or().like("name", keyword));
            }
            qw.orderByAsc("code");
            resultPage = (Page<Town>) townMapper.selectPage(new Page<>(page, size), qw);

        } else if ("village".equals(lvl)) {
            QueryWrapper<Village> qw = new QueryWrapper<>();
            if (StringUtils.hasText(parentCode)) {
                qw.eq("town_code", parentCode);
            }
            if (StringUtils.hasText(keyword)) {
                qw.and(w -> w.like("code", keyword).or().like("name", keyword));
            }
            qw.orderByAsc("code");
            resultPage = (Page<Village>) villageMapper.selectPage(new Page<>(page, size), qw);

        } else {
            // 默认 province
            QueryWrapper<Province> qw = new QueryWrapper<>();
            if (StringUtils.hasText(keyword)) {
                qw.and(w -> w.like("code", keyword).or().like("name", keyword));
            }
            qw.orderByAsc("code");
            resultPage = (Page<Province>) provinceMapper.selectPage(new Page<>(page, size), qw);
        }

        return Response.ok("page", resultPage).add("level", lvl);
    }

    /**
     * Redis 实时缓存探针接口
     *
     * @param code 待探测的行政区划代码
     * @return 包含该代码在 Redis 中的名称、层级归属及下辖子节点列表
     */
    @ApiOperation(value = "Redis 缓存实时探针", notes = "输入任意行政代码，一键穿透探测 Redis 中 Hash 实体与 Set 子节点索引")
    @ResponseBody
    @GetMapping("/api/dashboard/redis/probe")
    public Response probeRedis(@ApiParam(value = "12位或短码行政代码", required = true) @RequestParam String code) {
        if (!StringUtils.hasText(code)) {
            return Response.error("行政代码不能为空");
        }

        String searchCode = code.trim();
        if (searchCode.length() < 12) {
            searchCode = String.format("%-12s", searchCode).replace(' ', '0');
        }

        String name = null;
        String layer = null;
        List<String> children = new ArrayList<>();

        try (Jedis jedis = jedisPool.getResource()) {
            // 逐级探测 Hash 表
            String[] layers = {"province", "city", "county", "town", "village"};
            for (String l : layers) {
                String val = jedis.hget(AreaCommon.CACHE_SYS_ROOT_PREFIX + l, searchCode);
                if (val != null) {
                    name = val;
                    layer = l;
                    break;
                }
            }

            // 探测下级索引
            String[] childLayers = {"city", "county", "town", "village"};
            for (String cl : childLayers) {
                Set<String> set = jedis.smembers(AreaCommon.CACHE_SYS_INDEX_PREFIX + cl + ":" + searchCode);
                if (set != null && !set.isEmpty()) {
                    children.addAll(set);
                }
            }
        } catch (Exception e) {
            log.warning("探测 Redis 异常: " + e.getMessage());
            return Response.error("Redis 探测异常: " + e.getMessage());
        }

        return Response.ok()
                .add("code", searchCode)
                .add("name", name != null ? name : "未找到匹配缓存记录")
                .add("layer", layer != null ? layer : "未知")
                .add("childCount", children.size())
                .add("children", children);
    }
}

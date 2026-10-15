package org.codework.tools.gb2260.snatch;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.google.common.eventbus.EventBus;
import lombok.SneakyThrows;
import lombok.extern.java.Log;
import org.codework.tools.gb2260.snatch.guava.Msg;
import org.codework.tools.gb2260.snatch.guava.RunThreadPoolExecutor;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.codework.tools.gb2260.entity.City;
import org.codework.tools.gb2260.entity.County;
import org.codework.tools.gb2260.entity.Province;
import org.codework.tools.gb2260.entity.Town;
import org.codework.tools.gb2260.entity.Village;
import org.codework.tools.gb2260.mapper.CityMapper;
import org.codework.tools.gb2260.mapper.CountyMapper;
import org.codework.tools.gb2260.mapper.ProvinceMapper;
import org.codework.tools.gb2260.mapper.TownMapper;
import org.codework.tools.gb2260.mapper.VillageMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.codework.tools.gb2260.service.CrawlHistoryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 行政区划抓取与下钻解析核心处理器
 * <p>
 * 核心架构特性：
 * 1. 双数据源引擎支持：
 *    - "mca"：对接民政部官方全国地名信息库 API，支持 4 级行政区划与本地快照无缝降级。
 *    - "stats"：国家统计局官方静态 HTML 镜像，支持 5 级全量（至村委会/居委会）。
 * 2. 基于 Guava EventBus 的事件驱动模型：
 *    - 省级、市级、区县级、街道级逐层通过 EventBus 发布异步消息，由订阅者统一落库并分发下一级采集任务，实现完全解耦。
 * 3. 并发与限频控制：
 *    - 具备核心/最大线程池动态调度、可配置的随机休眠区间（防止官方 API 频控拦截）、以及 10 万级阻塞队列支撑。
 *
 * @author benjaminkc
 */
@Log
@Component
public class AreaSnatchHandler {

    /**
     * 数据源引擎类型配置（"mca" 或 "stats"）
     */
    @Value("${gb2260.source:mca}")
    private String dataSource = "mca";

    /**
     * 民政部官方地名信息库接口地址
     */
    @Value("${gb2260.mca.url:https://dmfw.mca.gov.cn/9095/xzqh/getList}")
    private String mcaUrl = "https://dmfw.mca.gov.cn/9095/xzqh/getList";

    /**
     * 民政部抓取省级过滤配置（为空抓取全国 34 个省级；填如 "11,44" 仅抓取北京与广东）
     */
    @Value("${gb2260.mca.province-filter:}")
    private String provinceFilter = "";

    /**
     * 民政部抓取每个请求最小休眠毫秒数
     */
    @Value("${gb2260.mca.sleep-min:0}")
    private int mcaSleepMin = 0;

    /**
     * 民政部抓取每个请求最大休眠毫秒数
     */
    @Value("${gb2260.mca.sleep-max:10}")
    private int mcaSleepMax = 10;

    /**
     * 统计局数据年份镜像子路径
     */
    @Value("${gb2260.year:2017/}")
    private String govYear = "2017/";

    /**
     * 统计局行政区划根路径
     */
    @Value("${gb2260.url:http://www.stats.gov.cn/tjsj/tjbz/tjyqhdmhcxhfdm/}")
    private String govUrl = "http://www.stats.gov.cn/tjsj/tjbz/tjyqhdmhcxhfdm/";

    private static final String GOV_INDEX = "index.html";
    private static final String CONDITION_DIRECTLY = "市";

    private boolean isOriginalRequestURL = true;
    private int corePoolSize = 80;
    private int maximumPoolSize = 200;
    private int sleepMax = 3000;
    private int sleepMin = 1200;

    private final AreaHttpFetcher httpFetcher;
    private final EventBus eventBus;
    private final ConcurrentHashMap<String, RunThreadPoolExecutor> executors;

    @Autowired(required = false)
    private CrawlHistoryService crawlHistoryService;

    @Autowired(required = false)
    private ProvinceMapper provinceMapper;

    @Autowired(required = false)
    private CityMapper cityMapper;

    @Autowired(required = false)
    private CountyMapper countyMapper;

    @Autowired(required = false)
    private TownMapper townMapper;

    @Autowired(required = false)
    private VillageMapper villageMapper;

    @Autowired(required = false)
    private McaSnapshotService mcaSnapshotService;

    /**
     * 构造函数注入核心依赖
     *
     * @param httpFetcher HTTP 抓取服务组件
     * @param eventBus    Guava 单机事件总线
     * @param executors   并发任务执行器集中存储表
     */
    public AreaSnatchHandler(AreaHttpFetcher httpFetcher, EventBus eventBus,
                             ConcurrentHashMap<String, RunThreadPoolExecutor> executors) {
        this.httpFetcher = httpFetcher;
        this.eventBus = eventBus;
        this.executors = executors;
    }

    public CrawlHistoryService getCrawlHistoryService() {
        return crawlHistoryService;
    }

    public void setCrawlHistoryService(CrawlHistoryService crawlHistoryService) {
        this.crawlHistoryService = crawlHistoryService;
    }

    public void setProvinceMapper(ProvinceMapper provinceMapper) {
        this.provinceMapper = provinceMapper;
    }

    public void setCityMapper(CityMapper cityMapper) {
        this.cityMapper = cityMapper;
    }

    public void setCountyMapper(CountyMapper countyMapper) {
        this.countyMapper = countyMapper;
    }

    public void setTownMapper(TownMapper townMapper) {
        this.townMapper = townMapper;
    }

    public void setVillageMapper(VillageMapper villageMapper) {
        this.villageMapper = villageMapper;
    }

    public void setMcaSnapshotService(McaSnapshotService mcaSnapshotService) {
        this.mcaSnapshotService = mcaSnapshotService;
    }

    public boolean isOfflineFirst() {
        return httpFetcher != null && httpFetcher.isOfflineFirst();
    }

    public void setOfflineFirst(boolean offlineFirst) {
        if (httpFetcher != null) {
            httpFetcher.setOfflineFirst(offlineFirst);
        }
    }

    public String getDataSource() {
        return dataSource;
    }

    public void setDataSource(String dataSource) {
        this.dataSource = dataSource;
    }

    public String getMcaUrl() {
        return mcaUrl;
    }

    public void setMcaUrl(String mcaUrl) {
        this.mcaUrl = mcaUrl;
    }

    public String getProvinceFilter() {
        return provinceFilter;
    }

    public void setProvinceFilter(String provinceFilter) {
        this.provinceFilter = provinceFilter;
    }

    public String getGovYear() {
        return govYear;
    }

    public void setGovYear(String govYear) {
        this.govYear = govYear;
    }

    public String getGovUrl() {
        return govUrl;
    }

    public void setGovUrl(String govUrl) {
        this.govUrl = govUrl;
    }

    public int getMcaSleepMin() {
        return mcaSleepMin;
    }

    public void setMcaSleepMin(int mcaSleepMin) {
        this.mcaSleepMin = mcaSleepMin;
    }

    public int getMcaSleepMax() {
        return mcaSleepMax;
    }

    public void setMcaSleepMax(int mcaSleepMax) {
        this.mcaSleepMax = mcaSleepMax;
    }

    @SneakyThrows
    private Optional<String> decode(String targetURL, boolean isOriginalMethod) {
        return httpFetcher.fetch(targetURL, isOriginalMethod);
    }

    /**
     * 处理器的启动入口方法
     * <p>
     * 初始化任务专属的 {@link RunThreadPoolExecutor} 并注册至 executors 全局表，
     * 随后异步启动顶级（省级）行政区划抓取，并由监控线程持续检测任务是否全部执行完毕。
     */
    @SneakyThrows
    public void snatch() {
        RunThreadPoolExecutor executorService = new RunThreadPoolExecutor(
                corePoolSize, maximumPoolSize,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100000),
                new ThreadPoolExecutor.CallerRunsPolicy());

        String flag = String.valueOf(System.currentTimeMillis());
        // 加入集中管理队列
        executors.put(flag, executorService);

        if ("mca".equalsIgnoreCase(dataSource)) {
            // 民政部国家地名信息库官方 API 模式
            CompletableFuture.runAsync(() -> this.snatchMcaProvinces(flag), executorService);
        } else {
            // 原有国家统计局 HTML 静态镜像抓取模式
            CompletableFuture.runAsync(() -> {
                Optional<String> r = this.decode(govUrl + govYear + GOV_INDEX, isOriginalRequestURL);
                if (!r.isPresent()) {
                    log.severe("无法获取国家统计局根页面数据，请检查网络或网站防爬拦截: " + govUrl + govYear + GOV_INDEX);
                }
                r.ifPresent(resp -> {
                    Document provinceDoc = Jsoup.parse(resp);
                    Elements provinceItems = provinceDoc.select("tr[class=provincetr] td a");

                    provinceItems.parallelStream().forEach(a -> {
                        boolean isDirectlyCity = a.text().contains(CONDITION_DIRECTLY);
                        String GOV_PATH = a.attr("href");
                        String CODE = GOV_PATH.substring(0, GOV_PATH.indexOf("."));

                        AreaInfo info = AreaInfo.builder()
                                .code(AreaSnatchHelper.getFullCodeByShortCode(CODE))
                                .name(a.text())
                                .path(GOV_PATH)
                                .isDirectlyCity(isDirectlyCity)
                                .parentCode("0")
                                .build();

                        eventBus.post(Msg.builder()
                                .executorFlag(flag)
                                .channel(AreaCommon.CHANNEL_PROVINCE)
                                .message(info.toJson()).build());
                    });
                });

            }, executorService);
        }

        if (crawlHistoryService != null) {
            crawlHistoryService.recordStart(flag, dataSource, provinceFilter, isOfflineFirst());
        }

        // 异步守护线程对当前执行器进行收敛监听
        CompletableFuture.runAsync(() -> {
            executorService.isEndTask();
            if (crawlHistoryService != null) {
                String status = executorService.isNormalCompleted() ? "SUCCESS" : "TERMINATED";
                crawlHistoryService.recordFinish(flag, status, null);
            }
        });
    }

    /**
     * 断点续爬与差集推导补偿启动入口
     */
    public void refire() {
        String flag = "refire-" + System.currentTimeMillis();
        refire(flag);
    }

    /**
     * 指定批次标识的断点续爬补偿入口（方案一：层级差集推导与补偿下钻）
     *
     * @param flag 任务批次标识
     */
    @SneakyThrows
    public void refire(String flag) {
        RunThreadPoolExecutor executorService = new RunThreadPoolExecutor(
                corePoolSize, maximumPoolSize,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100000),
                new ThreadPoolExecutor.CallerRunsPolicy());

        executors.put(flag, executorService);

        if (crawlHistoryService != null) {
            crawlHistoryService.recordStart(flag, dataSource, provinceFilter, isOfflineFirst());
        }

        CompletableFuture.runAsync(() -> {
            try {
                if ("mca".equalsIgnoreCase(dataSource)) {
                    this.refireMcaCompensation(flag);
                } else {
                    this.refireStatsCompensation(flag);
                }
            } catch (Exception ex) {
                log.severe("执行断点续爬差集补偿异常: " + ex.getMessage());
            }
        }, executorService);

        // 异步守护线程对当前执行器进行收敛监听与审计回写
        CompletableFuture.runAsync(() -> {
            executorService.isEndTask();
            if (crawlHistoryService != null) {
                String status = executorService.isNormalCompleted() ? "SUCCESS" : "TERMINATED";
                crawlHistoryService.recordFinish(flag, status, null);
            }
        });
    }

    /**
     * 民政部模式层级差集推导与补偿下钻
     *
     * @param flag 任务批次标识
     */
    private void refireMcaCompensation(String flag) {
        log.info("【断点续爬】开始执行民政部数据源层级差集扫描与补偿分析...");
        if (provinceMapper == null || cityMapper == null || countyMapper == null || townMapper == null) {
            log.warning("Mapper 未装配，回退至全量抓取");
            this.snatchMcaProvinces(flag);
            return;
        }

        // 1. 省级比对
        List<Province> provinces = provinceMapper.selectList(null);
        if (provinces == null || provinces.isEmpty()) {
            log.info("【断点续爬】当前数据库无任何省级数据，直接从根节点启动民政部抓取");
            this.snatchMcaProvinces(flag);
            return;
        }

        Set<String> existingProvinceCodes = new HashSet<>();
        for (Province p : provinces) {
            existingProvinceCodes.add(p.getCode());
        }

        // 检查是否有缺失省份
        try {
            Optional<String> provJsonOpt = httpFetcher.fetchMcaApi(mcaUrl + "?code=&maxLevel=1");
            if (provJsonOpt.isPresent()) {
                JSONObject root = JSON.parseObject(provJsonOpt.get());
                JSONObject data = root.getJSONObject("data");
                if (data != null && data.getJSONArray("children") != null) {
                    JSONArray children = data.getJSONArray("children");
                    for (int i = 0; i < children.size(); i++) {
                        JSONObject item = children.getJSONObject(i);
                        String code = item.getString("code");
                        String name = item.getString("name");
                        String type = item.getString("type");
                        if (!StringUtils.hasText(code) || !AreaSnatchHelper.isContainsDigital(code) || code.length() != 12) {
                            continue;
                        }

                        if (StringUtils.hasText(provinceFilter)) {
                            boolean match = false;
                            for (String pfx : provinceFilter.split(",")) {
                                if (code.startsWith(pfx.trim())) {
                                    match = true;
                                    break;
                                }
                            }
                            if (!match) continue;
                        }

                        if (!existingProvinceCodes.contains(code)) {
                            log.info("【断点续爬】发现缺失省级行政区: " + name + " (" + code + ")，发布补全事件");
                            boolean isDirectlyCity = "直辖市".equals(type) || (name != null && name.endsWith("市")
                                    && (code.startsWith("11") || code.startsWith("12") || code.startsWith("31") || code.startsWith("50")));
                            AreaInfo info = AreaInfo.builder()
                                    .code(code)
                                    .name(name)
                                    .parentCode("0")
                                    .isDirectlyCity(isDirectlyCity)
                                    .path(code)
                                    .build();
                            eventBus.post(Msg.builder()
                                    .executorFlag(flag)
                                    .channel(AreaCommon.CHANNEL_PROVINCE)
                                    .message(info.toJson())
                                    .build());
                        }
                    }
                }
            }
        } catch (Exception ex) {
            log.warning("检查省级缺失异常: " + ex.getMessage());
        }

        // 2. 预先以 GROUP BY 批查市、县、镇的子项规模统计
        Map<String, Integer> cityCountByProvince = queryGroupCount(cityMapper, "province_code");
        Map<String, Integer> countyCountByCity = queryGroupCount(countyMapper, "city_code");
        Map<String, Integer> townCountByCity = queryGroupCount(townMapper, "city_code");
        Map<String, Integer> townCountByCounty = queryGroupCount(townMapper, "county_code");

        // 3. 逐省检查下属城市/区县完整度
        for (Province p : provinces) {
            boolean isDirectlyCity = p.getName() != null && (
                    p.getCode().startsWith("11") || p.getCode().startsWith("12") ||
                    p.getCode().startsWith("31") || p.getCode().startsWith("50"));

            if (isDirectlyCity) {
                String virtualCityCode = p.getCode().substring(0, 2) + "0100000000";
                City virtualCity = cityMapper.selectById(virtualCityCode);
                int countyCount = countyMapper.selectCount(new QueryWrapper<County>().likeRight("code", p.getCode().substring(0, 2)));
                if (virtualCity == null || countyCount == 0) {
                    log.info("【断点续爬】直辖市 [" + p.getName() + "] 下辖区县数据缺失(现有: " + countyCount + ")，重按下钻抓取");
                    AreaInfo pInfo = AreaInfo.builder()
                            .code(p.getCode()).name(p.getName()).parentCode("0").isDirectlyCity(true).path(p.getCode())
                            .build();
                    this.snatchMcaProvinceUnderCityCollection(flag, pInfo);
                }
            } else {
                int cCount = cityCountByProvince.getOrDefault(p.getCode(), 0);
                if (cCount == 0) {
                    log.info("【断点续爬】省份 [" + p.getName() + "] 下辖地级市缺失，重按下钻抓取");
                    AreaInfo pInfo = AreaInfo.builder()
                            .code(p.getCode()).name(p.getName()).parentCode("0").isDirectlyCity(false).path(p.getCode())
                            .build();
                    this.snatchMcaProvinceUnderCityCollection(flag, pInfo);
                }
            }
        }

        // 4. 逐市检查下辖区县/乡镇完整度
        List<City> cities = cityMapper.selectList(null);
        if (cities != null) {
            for (City c : cities) {
                boolean isNoCountyCity = (c.getCode() != null && (
                        c.getCode().startsWith("4419") || c.getCode().startsWith("4420") ||
                        c.getCode().startsWith("4604") || c.getCode().startsWith("6202")));

                if (isNoCountyCity) {
                    int tCount = townCountByCity.getOrDefault(c.getCode(), 0);
                    if (tCount == 0) {
                        log.info("【断点续爬】不设区地级市 [" + c.getName() + "] 街道缺失，重按下钻抓取");
                        AreaInfo cInfo = AreaInfo.builder()
                                .code(c.getCode()).name(c.getName()).parentCode(c.getProvinceCode()).path(c.getCode())
                                .build();
                        this.snatchMcaCityUnderCountyCollection(flag, cInfo);
                    }
                } else {
                    int coCount = countyCountByCity.getOrDefault(c.getCode(), 0);
                    if (coCount == 0) {
                        log.info("【断点续爬】地级市 [" + c.getName() + "] 区县缺失，重按下钻抓取");
                        AreaInfo cInfo = AreaInfo.builder()
                                .code(c.getCode()).name(c.getName()).parentCode(c.getProvinceCode()).path(c.getCode())
                                .build();
                        this.snatchMcaCityUnderCountyCollection(flag, cInfo);
                    }
                }
            }
        }

        // 5. 逐区县检查下辖乡镇/街道完整度
        List<County> counties = countyMapper.selectList(null);
        if (counties != null) {
            for (County co : counties) {
                int tCount = townCountByCounty.getOrDefault(co.getCode(), 0);
                if (tCount == 0) {
                    log.info("【断点续爬】区县 [" + co.getName() + "] (" + co.getCode() + ") 下辖乡镇/街道缺失，重按下钻抓取");
                    AreaInfo coInfo = AreaInfo.builder()
                            .code(co.getCode()).name(co.getName()).parentCode(co.getCityCode()).path(co.getCode())
                            .build();
                    this.snatchMcaCountyUnderTownCollection(flag, coInfo);
                }
            }
        }
        log.info("【断点续爬】层级差集扫描与补偿派发完成，等待在途任务收敛！");
    }

    /**
     * 统计局模式层级差集推导与补偿下钻
     *
     * @param flag 任务批次标识
     */
    private void refireStatsCompensation(String flag) {
        log.info("【断点续爬】开始执行国家统计局数据源层级差集扫描与补偿分析...");
        if (provinceMapper == null || cityMapper == null || countyMapper == null || townMapper == null || villageMapper == null) {
            log.warning("Mapper 未装配，回退至全量抓取");
            this.snatch();
            return;
        }

        List<Province> provinces = provinceMapper.selectList(null);
        if (provinces == null || provinces.isEmpty()) {
            this.snatch();
            return;
        }

        Map<String, Integer> cityCountByProvince = queryGroupCount(cityMapper, "province_code");
        Map<String, Integer> countyCountByCity = queryGroupCount(countyMapper, "city_code");
        Map<String, Integer> townCountByCounty = queryGroupCount(townMapper, "county_code");
        Map<String, Integer> villageCountByTown = queryGroupCount(villageMapper, "town_code");

        // 检查各省市
        for (Province p : provinces) {
            int cCount = cityCountByProvince.getOrDefault(p.getCode(), 0);
            if (cCount == 0) {
                String shortCode = p.getCode() != null && p.getCode().length() >= 2 ? p.getCode().substring(0, 2) : p.getCode();
                AreaInfo pInfo = AreaInfo.builder()
                        .code(p.getCode()).name(p.getName()).parentCode("0").path(shortCode + ".html")
                        .build();
                this.snatchProvinceUnderCityCollection(flag, pInfo);
            }
        }

        // 检查各市
        List<City> cities = cityMapper.selectList(null);
        if (cities != null) {
            for (City c : cities) {
                int coCount = countyCountByCity.getOrDefault(c.getCode(), 0);
                if (coCount == 0) {
                    String pShort = c.getProvinceCode().substring(0, 2);
                    String cShort = c.getCode().substring(2, 4);
                    AreaInfo cInfo = AreaInfo.builder()
                            .code(c.getCode()).name(c.getName()).parentCode(c.getProvinceCode()).path(pShort + "/" + pShort + cShort + ".html")
                            .build();
                    this.snatchCityUnderCountyCollection(flag, cInfo);
                }
            }
        }

        // 检查各区县
        List<County> counties = countyMapper.selectList(null);
        if (counties != null) {
            for (County co : counties) {
                int tCount = townCountByCounty.getOrDefault(co.getCode(), 0);
                if (tCount == 0) {
                    String pShort = co.getCode().substring(0, 2);
                    String coShort = co.getCode().substring(2, 6);
                    AreaInfo coInfo = AreaInfo.builder()
                            .code(co.getCode()).name(co.getName()).parentCode(co.getCityCode()).path(pShort + "/" + coShort + ".html")
                            .build();
                    this.snatchCountyUnderTownCollection(flag, coInfo);
                }
            }
        }

        // 检查各街道
        List<Town> towns = townMapper.selectList(null);
        if (towns != null) {
            for (Town t : towns) {
                int vCount = villageCountByTown.getOrDefault(t.getCode(), 0);
                if (vCount == 0) {
                    String pShort = t.getCode().substring(0, 2);
                    String coShort = t.getCountyCode() != null && t.getCountyCode().length() >= 6 ? t.getCountyCode().substring(2, 6) : t.getCode().substring(2, 6);
                    String tShort = t.getCode().substring(6, 9);
                    AreaInfo tInfo = AreaInfo.builder()
                            .code(t.getCode()).name(t.getName()).parentCode(t.getCountyCode()).path(pShort + "/" + coShort + "/" + tShort + ".html")
                            .build();
                    this.snatchTownUnderVillageCollection(flag, tInfo);
                }
            }
        }
    }

    /**
     * 通用 GROUP BY 统计各上级编码下的子项记录数
     *
     * @param mapper      目标表的 BaseMapper
     * @param groupColumn 上级分组外键字段名
     * @param <T>         实体类型
     * @return 分组统计映射 (外键值 -> 记录数)
     */
    private <T> Map<String, Integer> queryGroupCount(BaseMapper<T> mapper, String groupColumn) {
        Map<String, Integer> result = new HashMap<>();
        try {
            List<Map<String, Object>> list = mapper.selectMaps(new QueryWrapper<T>()
                    .select(groupColumn + ", count(1) as cnt")
                    .isNotNull(groupColumn)
                    .groupBy(groupColumn));
            if (list != null) {
                for (Map<String, Object> map : list) {
                    String key = (String) map.get(groupColumn);
                    Number cnt = (Number) map.get("cnt");
                    if (key != null && cnt != null) {
                        result.put(key, cnt.intValue());
                    }
                }
            }
        } catch (Exception ex) {
            log.warning("分组查询 " + groupColumn + " 统计异常: " + ex.getMessage());
        }
        return result;
    }

    /**
     * 接收每个省份的信息，打开省份链接或调用 API 遍历其下级地级市并发送事件
     *
     * @param executorFlag 执行器标识
     * @param info         省级行政区信息
     */
    @SneakyThrows
    public void snatchProvinceUnderCityCollection(String executorFlag, AreaInfo info) {
        if ("mca".equalsIgnoreCase(dataSource)) {
            this.snatchMcaProvinceUnderCityCollection(executorFlag, info);
            return;
        }
        Elements elements = this.getElements(info.getPath(), "citytr");
        this.transElements(info.getCode(), elements, i -> {
            if (StringUtils.hasText(i.getPath())) {
                eventBus.post(Msg.builder().executorFlag(executorFlag).channel(AreaCommon.CHANNEL_CITY).message(i.toJson()).build());
            }
        });
    }

    /**
     * 接收每个城市的信息，遍历其下辖区县（或直筒子市直辖街道）并发送事件
     *
     * @param executorFlag 执行器标识
     * @param info         市级行政区信息
     */
    @SneakyThrows
    public void snatchCityUnderCountyCollection(String executorFlag, AreaInfo info) {
        if ("mca".equalsIgnoreCase(dataSource)) {
            this.snatchMcaCityUnderCountyCollection(executorFlag, info);
            return;
        }
        Elements elements = this.getElements(info.getPath(), "countytr");

        // 遇上无法抓取任何元素时（如东莞、中山等直筒子市跳过区县直接抓街道）
        if (elements.isEmpty()) {
            elements = this.getElements(info.getPath(), "towntr");
        }

        this.transElements(info.getCode(), elements, i -> {
            if (StringUtils.hasText(i.getPath())) {
                // 根据实体编码判定直接进入街道或区县
                if (i.isDirectlyCounty()) {
                    eventBus.post(Msg.builder().executorFlag(executorFlag).channel(AreaCommon.CHANNEL_TOWN).message(i.toJson()).build());
                } else {
                    eventBus.post(Msg.builder().executorFlag(executorFlag).channel(AreaCommon.CHANNEL_COUNTY).message(i.toJson()).build());
                }
            }
        });
    }

    /**
     * 接收每个区县的信息，遍历其下辖乡镇/街道办事处并发送事件
     *
     * @param executorFlag 执行器标识
     * @param info         区县级行政区信息
     */
    @SneakyThrows
    public void snatchCountyUnderTownCollection(String executorFlag, AreaInfo info) {
        if ("mca".equalsIgnoreCase(dataSource)) {
            this.snatchMcaCountyUnderTownCollection(executorFlag, info);
            return;
        }
        String parentShortCode = info.getCode().substring(0, 2) + "/";

        Elements elements = this.getElements(parentShortCode + info.getPath(), "towntr");
        this.transElements(info.getCode(), elements, i -> {
            if (StringUtils.hasText(i.getPath())) {
                eventBus.post(Msg.builder().executorFlag(executorFlag).channel(AreaCommon.CHANNEL_TOWN).message(i.toJson()).build());
            }
        });
    }

    /**
     * 接收每个乡镇/街道的信息，遍历其下辖居委会/村委会并发送事件（仅统计局数据源提供）
     *
     * @param executorFlag 执行器标识
     * @param info         乡镇/街道级行政区信息
     */
    @SneakyThrows
    public void snatchTownUnderVillageCollection(String executorFlag, AreaInfo info) {
        if ("mca".equalsIgnoreCase(dataSource)) {
            // 民政部国家地名信息库管理至法定第四级（乡镇/街道），街道下无五级居委会数据，直接退出
            return;
        }
        String grandShortCode = info.getCode().substring(0, 2) + "/";
        String parentShortCode = info.isDirectlyCounty() ? "" : info.getCode().substring(2, 4) + "/";

        Elements elements = this.getElements(grandShortCode + parentShortCode + info.getPath(), "villagetr");
        this.transElements(info.getCode(), elements, i ->
                eventBus.post(Msg.builder().executorFlag(executorFlag).channel(AreaCommon.CHANNEL_VILLAGE).message(i.toJson()).build()));
    }

    /**
     * 民政部模式：请求全国省级行政区划列表
     *
     * @param flag 任务标识
     */
    private void snatchMcaProvinces(String flag) {
        log.info("【民政部数据源】正在请求全国省级行政区划列表: " + mcaUrl + "?code=&maxLevel=1");
        try {
            Optional<String> jsonOpt = httpFetcher.fetchMcaApi(mcaUrl + "?code=&maxLevel=1");
            if (!jsonOpt.isPresent()) {
                log.severe("无法获取民政部省级数据，请检查网络或接口地址: " + mcaUrl);
                return;
            }
            JSONObject root = JSON.parseObject(jsonOpt.get());
            JSONObject data = root.getJSONObject("data");
            if (data == null) {
                log.severe("民政部省级数据 data 节点为空");
                return;
            }
            JSONArray children = data.getJSONArray("children");
            if (children == null || children.isEmpty()) {
                log.severe("民政部未返回任何省级子节点");
                return;
            }

            for (int i = 0; i < children.size(); i++) {
                JSONObject item = children.getJSONObject(i);
                String code = item.getString("code");
                String name = item.getString("name");
                String type = item.getString("type");

                if (!StringUtils.hasText(code) || !AreaSnatchHelper.isContainsDigital(code) || code.length() != 12) {
                    continue; // 过滤非12位数字代码（如“资料暂缺”）
                }

                // 支持按配置的省份前缀过滤（如仅跑北京 11 与广东 44）
                if (StringUtils.hasText(provinceFilter)) {
                    String[] allowed = provinceFilter.split(",");
                    boolean matched = false;
                    for (String prefix : allowed) {
                        if (code.startsWith(prefix.trim())) {
                            matched = true;
                            break;
                        }
                    }
                    if (!matched) {
                        continue;
                    }
                }

                boolean isDirectlyCity = "直辖市".equals(type) || (name != null && name.endsWith("市")
                        && (code.startsWith("11") || code.startsWith("12") || code.startsWith("31") || code.startsWith("50")));

                AreaInfo info = AreaInfo.builder()
                        .code(code)
                        .name(name)
                        .parentCode("0")
                        .isDirectlyCity(isDirectlyCity)
                        .path(code)
                        .build();

                eventBus.post(Msg.builder()
                        .executorFlag(flag)
                        .channel(AreaCommon.CHANNEL_PROVINCE)
                        .message(info.toJson())
                        .build());
            }
        } catch (Exception e) {
            log.severe("抓取民政部省级数据异常: " + e.getMessage());
        }
    }

    /**
     * 民政部模式：获取省份下辖地级市或直辖市区县
     *
     * @param executorFlag 执行器标识
     * @param info         省级行政区信息
     */
    private void snatchMcaProvinceUnderCityCollection(String executorFlag, AreaInfo info) {
        try {
            if (mcaSleepMax > 0) {
                Thread.sleep(AreaSnatchHelper.ran(mcaSleepMax, mcaSleepMin));
            }
            Optional<String> jsonOpt = httpFetcher.fetchMcaApi(mcaUrl + "?code=" + info.getCode() + "&maxLevel=1");
            if (!jsonOpt.isPresent()) {
                return;
            }

            JSONObject root = JSON.parseObject(jsonOpt.get());
            JSONObject data = root.getJSONObject("data");
            if (data == null) {
                return;
            }
            JSONArray children = data.getJSONArray("children");
            if (children == null || children.isEmpty()) {
                return;
            }

            // 直辖市特殊处理：北京/天津/上海/重庆 其直接下级在民政部是 Level 3 (市辖区)
            if (info.isDirectlyCity()) {
                String virtualCityCode = info.getCode().substring(0, 2) + "0100000000";
                AreaInfo cityInfo = AreaInfo.builder()
                        .code(virtualCityCode)
                        .name("市辖区")
                        .parentCode(info.getCode())
                        .isDirectlyCity(true)
                        .path(virtualCityCode)
                        .build();

                eventBus.post(Msg.builder()
                        .executorFlag(executorFlag)
                        .channel(AreaCommon.CHANNEL_CITY)
                        .message(cityInfo.toJson())
                        .build());

                for (int i = 0; i < children.size(); i++) {
                    JSONObject item = children.getJSONObject(i);
                    String code = item.getString("code");
                    String name = item.getString("name");
                    if (!StringUtils.hasText(code) || !AreaSnatchHelper.isContainsDigital(code) || code.length() != 12) {
                        continue;
                    }

                    AreaInfo countyInfo = AreaInfo.builder()
                            .code(code)
                            .name(name)
                            .parentCode(virtualCityCode)
                            .path(code)
                            .build();

                    eventBus.post(Msg.builder()
                            .executorFlag(executorFlag)
                            .channel(AreaCommon.CHANNEL_COUNTY)
                            .message(countyInfo.toJson())
                            .build());
                }
            } else {
                for (int i = 0; i < children.size(); i++) {
                    JSONObject item = children.getJSONObject(i);
                    String code = item.getString("code");
                    String name = item.getString("name");
                    if (!StringUtils.hasText(code) || !AreaSnatchHelper.isContainsDigital(code) || code.length() != 12) {
                        continue;
                    }

                    AreaInfo cityInfo = AreaInfo.builder()
                            .code(code)
                            .name(name)
                            .parentCode(info.getCode())
                            .path(code)
                            .build();

                    eventBus.post(Msg.builder()
                            .executorFlag(executorFlag)
                            .channel(AreaCommon.CHANNEL_CITY)
                            .message(cityInfo.toJson())
                            .build());
                }
            }
        } catch (Exception e) {
            log.warning("民政部获取省下级城市异常: " + info.getCode() + ", " + e.getMessage());
        }
    }

    /**
     * 民政部模式：获取地级市下辖区县（或直筒子市直达乡镇街道）
     *
     * @param executorFlag 执行器标识
     * @param info         市级行政区信息
     */
    private void snatchMcaCityUnderCountyCollection(String executorFlag, AreaInfo info) {
        try {
            if (mcaSleepMax > 0) {
                Thread.sleep(AreaSnatchHelper.ran(mcaSleepMax, mcaSleepMin));
            }
            // 判断是否为不设区地级市（如东莞 4419、中山 4420、儋州 4604、嘉峪关 6202）
            boolean isNoCountyCity = (info.getCode() != null && (
                    info.getCode().startsWith("4419") ||
                    info.getCode().startsWith("4420") ||
                    info.getCode().startsWith("4604") ||
                    info.getCode().startsWith("6202")));

            if (isNoCountyCity) {
                Optional<String> jsonOpt = httpFetcher.fetchMcaApi(mcaUrl + "?code=" + info.getCode() + "&maxLevel=2");
                if (!jsonOpt.isPresent()) {
                    return;
                }
                JSONObject root = JSON.parseObject(jsonOpt.get());
                JSONObject data = root.getJSONObject("data");
                if (data == null) {
                    return;
                }
                JSONArray children = data.getJSONArray("children");
                if (children == null || children.isEmpty()) {
                    return;
                }

                for (int i = 0; i < children.size(); i++) {
                    JSONObject item = children.getJSONObject(i);
                    String code = item.getString("code");
                    String name = item.getString("name");
                    if (!StringUtils.hasText(code) || !AreaSnatchHelper.isContainsDigital(code) || code.length() != 12) {
                        continue;
                    }

                    AreaInfo townInfo = AreaInfo.builder()
                            .code(code)
                            .name(name)
                            .parentCode(info.getCode())
                            .path(code)
                            .build();

                    eventBus.post(Msg.builder()
                            .executorFlag(executorFlag)
                            .channel(AreaCommon.CHANNEL_TOWN)
                            .message(townInfo.toJson())
                            .build());
                }
            } else {
                Optional<String> jsonOpt = httpFetcher.fetchMcaApi(mcaUrl + "?code=" + info.getCode() + "&maxLevel=1");
                if (!jsonOpt.isPresent()) {
                    return;
                }
                JSONObject root = JSON.parseObject(jsonOpt.get());
                JSONObject data = root.getJSONObject("data");
                if (data == null) {
                    return;
                }
                JSONArray children = data.getJSONArray("children");
                if (children == null || children.isEmpty()) {
                    return;
                }

                for (int i = 0; i < children.size(); i++) {
                    JSONObject item = children.getJSONObject(i);
                    String code = item.getString("code");
                    String name = item.getString("name");
                    if (!StringUtils.hasText(code) || !AreaSnatchHelper.isContainsDigital(code) || code.length() != 12) {
                        continue;
                    }

                    AreaInfo countyInfo = AreaInfo.builder()
                            .code(code)
                            .name(name)
                            .parentCode(info.getCode())
                            .path(code)
                            .build();

                    eventBus.post(Msg.builder()
                            .executorFlag(executorFlag)
                            .channel(AreaCommon.CHANNEL_COUNTY)
                            .message(countyInfo.toJson())
                            .build());
                }
            }
        } catch (Exception e) {
            log.warning("民政部获取市下级区县异常: " + info.getCode() + ", " + e.getMessage());
        }
    }

    /**
     * 民政部模式：获取区县下辖乡镇/街道办事处
     *
     * @param executorFlag 执行器标识
     * @param info         区县级行政区信息
     */
    private void snatchMcaCountyUnderTownCollection(String executorFlag, AreaInfo info) {
        try {
            if (mcaSleepMax > 0) {
                Thread.sleep(AreaSnatchHelper.ran(mcaSleepMax, mcaSleepMin));
            }
            Optional<String> jsonOpt = httpFetcher.fetchMcaApi(mcaUrl + "?code=" + info.getCode() + "&maxLevel=1");
            if (!jsonOpt.isPresent()) {
                return;
            }
            JSONObject root = JSON.parseObject(jsonOpt.get());
            JSONObject data = root.getJSONObject("data");
            if (data == null) {
                return;
            }
            JSONArray children = data.getJSONArray("children");
            if (children == null || children.isEmpty()) {
                return;
            }

            for (int i = 0; i < children.size(); i++) {
                JSONObject item = children.getJSONObject(i);
                String code = item.getString("code");
                String name = item.getString("name");
                if (!StringUtils.hasText(code) || !AreaSnatchHelper.isContainsDigital(code) || code.length() != 12) {
                    continue;
                }

                AreaInfo townInfo = AreaInfo.builder()
                        .code(code)
                        .name(name)
                        .parentCode(info.getCode())
                        .path(code)
                        .build();

                eventBus.post(Msg.builder()
                        .executorFlag(executorFlag)
                        .channel(AreaCommon.CHANNEL_TOWN)
                        .message(townInfo.toJson())
                        .build());
            }
        } catch (Exception e) {
            log.warning("民政部获取区县下级街道乡镇异常: " + info.getCode() + ", " + e.getMessage());
        }
    }

    /**
     * 按父元素对静态 HTML 网页的集合元素进行抓取并提取对应 class 的行标签
     *
     * @param parentPath   父级相对链接
     * @param subClassName 子级 TR 样式类名（如 citytr, countytr, towntr, villagetr）
     * @return 解析出的 Jsoup Elements 集合
     * @throws InterruptedException 中断异常
     */
    private Elements getElements(String parentPath, String subClassName) throws InterruptedException {
        // 请求间隔防抓取限频休眠
        Thread.sleep(AreaSnatchHelper.ran(sleepMax, sleepMin));

        String targetURL = govUrl + govYear + parentPath;
        String selectQuery = "tr[class=\"" + subClassName + "\"]";

        Optional<String> resp = this.decode(targetURL, isOriginalRequestURL);

        if (resp.isPresent()) {
            Document doc = Jsoup.parse(resp.get());
            return doc.select(selectQuery);
        } else {
            return new Elements();
        }
    }

    /**
     * 转换 HTML 元素集合为行政区划模型对象集合，并通过回调处理转发事件
     *
     * @param parentCode 父级行政编码
     * @param elements   Jsoup Elements 行集合
     * @param process    结果处理回调
     */
    @SneakyThrows
    private void transElements(String parentCode, Elements elements, Process process) {
        elements.stream().forEachOrdered(tr -> {
            AreaInfo i = new AreaInfo();
            i.setParentCode(parentCode);

            // 每个数据行 TR 中的超链接或单元格
            Elements targetElements = !tr.select("a").isEmpty() ? tr.select("a") : tr.select("td");

            targetElements.stream().forEachOrdered(e -> {
                String text = e.text();
                if (AreaSnatchHelper.isContainsDigital(text) && text.length() == 12) {
                    i.setCode(text);
                } else if (AreaSnatchHelper.isContainsChinese(text)) {
                    i.setName(e.text());
                } else if (AreaSnatchHelper.isContainsDigital(text) && text.length() == 3) {
                    i.setCategoryCode(text);
                }

                if (e.hasAttr("href")) {
                    i.setPath(e.attr("href"));
                }
            });

            // 过滤无效空行与表头行
            if (StringUtils.hasText(i.getCode())) {
                process.run(i);
            }
        });
    }
}

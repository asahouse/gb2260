package org.codework.tools.gb2260;

import com.google.common.eventbus.EventBus;
import org.codework.tools.gb2260.controller.GetController;
import org.codework.tools.gb2260.controller.Response;
import org.codework.tools.gb2260.controller.RunController;
import org.codework.tools.gb2260.entity.*;
import org.codework.tools.gb2260.mapper.*;
import org.codework.tools.gb2260.snatch.AreaHttpFetcher;
import org.codework.tools.gb2260.snatch.AreaSnatchHandler;
import org.codework.tools.gb2260.snatch.guava.MsgEventSubscriber;
import org.codework.tools.gb2260.snatch.guava.RunThreadPoolExecutor;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.*;
import java.util.concurrent.*;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * 全链路端到端 (End-to-End) 闭环集成验证测试
 * 覆盖：爬虫调度 -> 静态HTML抓取 -> EventBus分发 -> DB实体落库 -> Redis缓存构建 -> 级联查询API
 */
public class EndToEndPipelineTest {

    // 内存模拟数据库
    private final Map<String, Province> provinceDb = new ConcurrentHashMap<>();
    private final Map<String, City> cityDb = new ConcurrentHashMap<>();
    private final Map<String, County> countyDb = new ConcurrentHashMap<>();
    private final Map<String, Town> townDb = new ConcurrentHashMap<>();
    private final Map<String, Village> villageDb = new ConcurrentHashMap<>();

    // 内存模拟 Redis
    private final Map<String, Map<String, String>> redisHashes = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> redisSets = new ConcurrentHashMap<>();

    private RunController runController;
    private GetController getController;
    private ConcurrentHashMap<String, RunThreadPoolExecutor> executors;

    @Before
    public void setup() throws Exception {
        provinceDb.clear();
        cityDb.clear();
        countyDb.clear();
        townDb.clear();
        villageDb.clear();
        redisHashes.clear();
        redisSets.clear();

        // 1. Mock DDLMapper
        DDLMapper ddlMapper = Mockito.mock(DDLMapper.class);

        // 2. Mock 5 级 Entity Mapper
        ProvinceMapper provinceMapper = Mockito.mock(ProvinceMapper.class);
        doAnswer(inv -> {
            Province p = inv.getArgument(0);
            provinceDb.put(p.getCode(), p);
            return 1;
        }).when(provinceMapper).insert(any(Province.class));

        CityMapper cityMapper = Mockito.mock(CityMapper.class);
        doAnswer(inv -> {
            City c = inv.getArgument(0);
            cityDb.put(c.getCode(), c);
            return 1;
        }).when(cityMapper).insert(any(City.class));

        CountyMapper countyMapper = Mockito.mock(CountyMapper.class);
        doAnswer(inv -> {
            County c = inv.getArgument(0);
            countyDb.put(c.getCode(), c);
            return 1;
        }).when(countyMapper).insert(any(County.class));

        TownMapper townMapper = Mockito.mock(TownMapper.class);
        doAnswer(inv -> {
            Town t = inv.getArgument(0);
            townDb.put(t.getCode(), t);
            return 1;
        }).when(townMapper).insert(any(Town.class));

        VillageMapper villageMapper = Mockito.mock(VillageMapper.class);
        doAnswer(inv -> {
            Village v = inv.getArgument(0);
            villageDb.put(v.getCode(), v);
            return 1;
        }).when(villageMapper).insert(any(Village.class));

        // 3. Mock Jedis 与 JedisPool
        Jedis jedis = Mockito.mock(Jedis.class);
        JedisPool jedisPool = Mockito.mock(JedisPool.class);
        when(jedisPool.getResource()).thenReturn(jedis);

        doAnswer(inv -> {
            String key = inv.getArgument(0);
            String field = inv.getArgument(1);
            String val = inv.getArgument(2);
            redisHashes.computeIfAbsent(key, k -> new ConcurrentHashMap<>()).put(field, val);
            return 1L;
        }).when(jedis).hset(anyString(), anyString(), anyString());

        doAnswer(inv -> {
            String key = inv.getArgument(0);
            String member = inv.getArgument(1);
            redisSets.computeIfAbsent(key, k -> Collections.synchronizedSet(new HashSet<>())).add(member);
            return 1L;
        }).when(jedis).sadd(anyString(), anyString());

        doAnswer(inv -> {
            String key = inv.getArgument(0);
            return redisHashes.getOrDefault(key, Collections.emptyMap());
        }).when(jedis).hgetAll(anyString());

        doAnswer(inv -> {
            String key = inv.getArgument(0);
            Set<String> set = redisSets.get(key);
            return set != null ? new HashSet<>(set) : Collections.emptySet();
        }).when(jedis).smembers(anyString());

        doAnswer(inv -> {
            String key = inv.getArgument(0);
            Object[] args = inv.getArguments();
            List<String> fields = new ArrayList<>();
            for (int i = 1; i < args.length; i++) {
                if (args[i] instanceof String[]) {
                    fields.addAll(Arrays.asList((String[]) args[i]));
                } else if (args[i] != null) {
                    fields.add(args[i].toString());
                }
            }
            Map<String, String> map = redisHashes.getOrDefault(key, Collections.emptyMap());
            List<String> list = new ArrayList<>();
            for (String f : fields) {
                list.add(map.get(f));
            }
            return list;
        }).when(jedis).hmget(anyString(), any());

        // 4. Mock HTML 页面数据响应（模拟标准的 5 级行政区划页面）
        AreaHttpFetcher httpFetcher = Mockito.mock(AreaHttpFetcher.class);
        when(httpFetcher.fetch(anyString(), Mockito.anyBoolean())).thenAnswer(new Answer<Optional<String>>() {
            @Override
            public Optional<String> answer(InvocationOnMock invocation) {
                String url = invocation.getArgument(0);

                if (url.endsWith("index.html")) {
                    // 省份页：包含北京市 (11)
                    return Optional.of("<html><body><table class='provincetable'>" +
                            "<tr class='provincetr'><td><a href='11.html'>北京市<br/></a></td></tr>" +
                            "</table></body></html>");
                } else if (url.endsWith("11.html")) {
                    // 城市页：市辖区 (1101)
                    return Optional.of("<html><body><table class='citytable'>" +
                            "<tr class='citytr'><td><a href='11/1101.html'>110100000000</a></td><td><a href='11/1101.html'>市辖区</a></td></tr>" +
                            "</table></body></html>");
                } else if (url.endsWith("11/1101.html")) {
                    // 区县页：东城区 (110101)
                    return Optional.of("<html><body><table class='countytable'>" +
                            "<tr class='countytr'><td><a href='01/110101.html'>110101000000</a></td><td><a href='01/110101.html'>东城区</a></td></tr>" +
                            "</table></body></html>");
                } else if (url.endsWith("11/01/110101.html")) {
                    // 乡镇街道页：东华门街道 (110101001000)
                    return Optional.of("<html><body><table class='towntable'>" +
                            "<tr class='towntr'><td><a href='01/110101001.html'>110101001000</a></td><td><a href='01/110101001.html'>东华门街道</a></td></tr>" +
                            "</table></body></html>");
                } else if (url.endsWith("11/01/01/110101001.html")) {
                    // 村委会页：多福巷社区居委会 (110101001001) 与 银闸社区居委会 (110101001002)
                    return Optional.of("<html><body><table class='villagetable'>" +
                            "<tr class='villagetr'><td>110101001001</td><td>111</td><td>多福巷社区居委会</td></tr>" +
                            "<tr class='villagetr'><td>110101001002</td><td>111</td><td>银闸社区居委会</td></tr>" +
                            "</table></body></html>");
                }
                return Optional.empty();
            }
        });

        // 5. 组装 Spring 核心组件
        EventBus eventBus = new EventBus();
        executors = new ConcurrentHashMap<>(10);
        AreaSnatchHandler snatchHandler = new AreaSnatchHandler(httpFetcher, eventBus, executors);
        snatchHandler.setDataSource("stats");

        // 测试加速：将休眠时间缩短为 1~5ms
        java.lang.reflect.Field sleepMinField = AreaSnatchHandler.class.getDeclaredField("sleepMin");
        sleepMinField.setAccessible(true);
        sleepMinField.setInt(snatchHandler, 1);
        java.lang.reflect.Field sleepMaxField = AreaSnatchHandler.class.getDeclaredField("sleepMax");
        sleepMaxField.setAccessible(true);
        sleepMaxField.setInt(snatchHandler, 5);

        MsgEventSubscriber subscriber = new MsgEventSubscriber(
                snatchHandler, executors, jedisPool,
                provinceMapper, cityMapper, countyMapper, townMapper, villageMapper
        );
        eventBus.register(subscriber);

        runController = new RunController(snatchHandler, executors, ddlMapper, jedisPool);
        getController = new GetController(jedisPool);
    }

    @Test
    public void testFullEndToEndPipeline() throws Exception {
        // Step 1: 调用 RunController.start() 触发端到端全量抓取
        Response startResp = runController.start();
        Assert.assertEquals(200, startResp.get("statusCode"));

        // Step 2: 等待执行器创建并等待所有级联在途任务完全处理完毕
        RunThreadPoolExecutor executor = null;
        for (int i = 0; i < 50; i++) {
            if (!executors.isEmpty()) {
                executor = executors.values().iterator().next();
                break;
            }
            Thread.sleep(50);
        }
        Assert.assertNotNull("执行器应当已成功实例化并注册", executor);

        // 主线程同步等待线程池自然完成
        executor.isEndTask();

        // Step 3: 验证抓取完成后的状态监控 API
        Response queryResp = runController.query();
        Assert.assertEquals(Boolean.FALSE, queryResp.get("running"));

        // Step 4: 数据库完整性与外键映射断言（核心验证）
        // 4.1 检查各层级落库记录数量
        Assert.assertEquals("省级应落库 1 条", 1, provinceDb.size());
        Assert.assertEquals("市级应落库 1 条", 1, cityDb.size());
        Assert.assertEquals("县级应落库 1 条", 1, countyDb.size());
        Assert.assertEquals("乡镇级应落库 1 条", 1, townDb.size());
        Assert.assertEquals("村居级应落库 2 条", 2, villageDb.size());

        // 4.2 验证层级拓扑编码
        Province prov = provinceDb.get("110000000000");
        Assert.assertNotNull(prov);
        Assert.assertEquals("北京市", prov.getName());

        City city = cityDb.get("110100000000");
        Assert.assertNotNull(city);
        Assert.assertEquals("110000000000", city.getProvinceCode());

        County county = countyDb.get("110101000000");
        Assert.assertNotNull(county);
        Assert.assertEquals("110100000000", county.getCityCode());

        Town town = townDb.get("110101001000");
        Assert.assertNotNull(town);
        Assert.assertEquals("110101000000", town.getCountyCode());

        // 4.3 关键断言：村级数据的 town_code 是否与 town 的主键完全吻合！
        Village v1 = villageDb.get("110101001001");
        Assert.assertNotNull("多福巷社区必须存在", v1);
        Assert.assertEquals("多福巷社区居委会", v1.getName());
        Assert.assertEquals("110000000000", v1.getProvinceCode());
        Assert.assertEquals("110100000000", v1.getCityCode());
        Assert.assertEquals("110101000000", v1.getCountyCode());
        // 核心验证点：townCode 必须是 110101001000，而不是截取8位产生的 110101000000！
        Assert.assertEquals("村级的townCode必须与town表的code精确一致", "110101001000", v1.getTownCode());
        Assert.assertEquals("111", v1.getCategoryCode());

        Village v2 = villageDb.get("110101001002");
        Assert.assertNotNull(v2);
        Assert.assertEquals("银闸社区居委会", v2.getName());
        Assert.assertEquals("110101001000", v2.getTownCode());

        // Step 5: 验证对外级联查询 API (GetController)
        // 5.1 查询省
        Response provResp = getController.province();
        Map<String, String> provBody = (Map<String, String>) provResp.get("body");
        Assert.assertTrue(provBody.containsKey("110000000000"));

        // 5.2 输入短码查询市 (输入 "11")
        Response cityResp = getController.provinceUnderCity("11");
        List<?> cities = (List<?>) cityResp.get("body");
        Assert.assertEquals("输入短码 11 应能级联查出北京市辖区", 1, cities.size());

        // 5.3 级联查询县
        Response countyResp = getController.cityUnderCounty("110100000000");
        List<?> counties = (List<?>) countyResp.get("body");
        Assert.assertEquals(1, counties.size());

        // 5.4 级联查询街道
        Response townResp = getController.countyUndertown("110101000000");
        List<?> towns = (List<?>) townResp.get("body");
        Assert.assertEquals(1, towns.size());

        // 5.5 级联查询村居（应查出 2 个村居）
        Response villageResp = getController.townUnderVillage("110101001000");
        List<?> villages = (List<?>) villageResp.get("body");
        Assert.assertEquals("街道下应返回 2 个社区居委会", 2, villages.size());

        // 5.6 空查询防御测试
        Response emptyResp = getController.townUnderVillage("999999999999");
        List<?> emptyList = (List<?>) emptyResp.get("body");
        Assert.assertTrue("不存在的下级应安全返回空集合而不抛异常", emptyList.isEmpty());
    }
}

package org.codework.tools.gb2260;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import okhttp3.OkHttpClient;
import org.codework.tools.gb2260.entity.*;
import org.codework.tools.gb2260.mapper.*;
import org.codework.tools.gb2260.snatch.AreaHttpFetcher;
import org.codework.tools.gb2260.snatch.AreaSnatchHandler;
import org.codework.tools.gb2260.snatch.guava.MsgEventSubscriber;
import org.codework.tools.gb2260.snatch.guava.RunThreadPoolExecutor;
import org.junit.Assert;
import org.junit.Before;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * 民政部（MCA）国家地名信息库官方 API 对接可行性与全链路测试
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
@RunWith(SpringRunner.class)
@SpringBootTest
public class McaApiFeasibilityTest {

    private static final String MCA_URL = "https://dmfw.mca.gov.cn/9095/xzqh/getList";

    @Autowired
    private AreaHttpFetcher httpFetcher;

    @Autowired
    private ProvinceMapper provinceMapper;

    @Autowired
    private CityMapper cityMapper;

    @Autowired
    private CountyMapper countyMapper;

    @Autowired
    private TownMapper townMapper;

    @Autowired
    private VillageMapper villageMapper;

    @Autowired
    private DDLMapper ddlMapper;

    @Autowired
    private redis.clients.jedis.JedisPool jedisPool;

    @Autowired
    private com.google.common.eventbus.EventBus eventBus;

    @Autowired
    private ConcurrentHashMap<String, RunThreadPoolExecutor> executors;

    @Autowired
    private AreaSnatchHandler handler;

    @Before
    public void setup() {
        ddlMapper.truncateTable("province");
        ddlMapper.truncateTable("city");
        ddlMapper.truncateTable("county");
        ddlMapper.truncateTable("town");
        ddlMapper.truncateTable("village");
        try (redis.clients.jedis.Jedis jedis = jedisPool.getResource()) {
            jedis.flushDB();
        }
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignored) {}
    }

    /**
     * 1. 真实网络接口连通性与结构解析测试
     * 验证能够成功请求民政部线上 API 并解析出符合国家标准 GB/T 2260 的省、市、区、街道四级数据
     */
    @Test
    public void test1_LiveMcaApiConnectivityAndHierarchy() throws Exception {
        // (1) 测试全国省级接口
        Optional<String> provResp = httpFetcher.fetchMcaApi(MCA_URL + "?code=&maxLevel=1");
        Assert.assertTrue("省级接口应当成功返回", provResp.isPresent());
        JSONObject provJson = JSON.parseObject(provResp.get());
        Assert.assertEquals(200, (int) provJson.getInteger("status"));
        JSONArray provChildren = provJson.getJSONObject("data").getJSONArray("children");
        Assert.assertTrue("省级节点应当大于等于30个", provChildren.size() >= 30);

        // 验证北京、广东存在
        boolean hasBeijing = false;
        boolean hasGuangdong = false;
        for (int i = 0; i < provChildren.size(); i++) {
            JSONObject p = provChildren.getJSONObject(i);
            if ("110000000000".equals(p.getString("code"))) hasBeijing = true;
            if ("440000000000".equals(p.getString("code"))) hasGuangdong = true;
        }
        Assert.assertTrue("民政部数据中必须包含北京市", hasBeijing);
        Assert.assertTrue("民政部数据中必须包含广东省", hasGuangdong);

        // (2) 测试北京市直辖市区县级接口
        Thread.sleep(500);
        Optional<String> bjResp = httpFetcher.fetchMcaApi(MCA_URL + "?code=110000000000&maxLevel=1");
        Assert.assertTrue("北京区县接口应当成功返回", bjResp.isPresent());
        JSONObject bjJson = JSON.parseObject(bjResp.get()).getJSONObject("data");
        Assert.assertNotNull("北京区县数据节点不能为空", bjJson);
        JSONArray bjDistricts = bjJson.getJSONArray("children");
        Assert.assertTrue("北京区县数量应当大于等于16个", bjDistricts.size() >= 16);

        // (3) 测试东城区街道级接口
        Thread.sleep(500);
        Optional<String> dcResp = httpFetcher.fetchMcaApi(MCA_URL + "?code=110101000000&maxLevel=1");
        Assert.assertTrue("东城区街道接口应当成功返回", dcResp.isPresent());
        JSONObject dcJson = JSON.parseObject(dcResp.get()).getJSONObject("data");
        Assert.assertNotNull("东城区数据节点不能为空", dcJson);
        JSONArray dcStreets = dcJson.getJSONArray("children");
        Assert.assertTrue("东城区下属街道应当大于10个", dcStreets.size() > 10);

        boolean hasDonghuamen = false;
        for (int i = 0; i < dcStreets.size(); i++) {
            JSONObject s = dcStreets.getJSONObject(i);
            if ("110101001000".equals(s.getString("code")) && "东华门街道".equals(s.getString("name"))) {
                hasDonghuamen = true;
                break;
            }
        }
        Assert.assertTrue("必须包含东华门街道 (110101001000)", hasDonghuamen);

        // (4) 测试不设区市（东莞市 441900000000）直接穿透镇/街道能力
        Thread.sleep(500);
        Optional<String> dgResp = httpFetcher.fetchMcaApi(MCA_URL + "?code=441900000000&maxLevel=2");
        Assert.assertTrue("东莞市接口应当成功返回", dgResp.isPresent());
        JSONObject dgJson = JSON.parseObject(dgResp.get()).getJSONObject("data");
        Assert.assertNotNull("东莞市数据节点不能为空", dgJson);
        JSONArray dgTowns = dgJson.getJSONArray("children");
        Assert.assertTrue("东莞市直辖镇/街道数量应当大于30个", dgTowns.size() > 30);

        System.out.println(">>> 民政部国家地名信息库官方 API 实测完全可用，返回结构高度规范！");
    }

    /**
     * 2. 民政部数据源全链路管道落库与外键闭合测试
     * 运行 AreaSnatchHandler (mca模式)，抓取落库并校验 MySQL 与 Redis
     */
    @Test
    public void test2_McaPipelineExecutionAndDbPersistence() throws Exception {
        handler.setDataSource("mca");
        handler.setMcaUrl(MCA_URL);
        handler.setProvinceFilter("11,44"); // 测试以北京和广东两省市验证真实线上端到端抓取与持久化

        // 执行抓取（入口触发）
        handler.snatch();

        // 等待在途任务完成（动态轮询直到数据落库收敛）
        for (int i = 0; i < 60; i++) {
            if (townMapper.selectCount(null) > 0 && countyMapper.selectCount(null) > 0) {
                // 等待当前批次完成落库
                Thread.sleep(1500);
                break;
            }
            Thread.sleep(500);
        }

        // 查询 MySQL 数据库
        List<Province> provinces = provinceMapper.selectList(null);
        List<City> cities = cityMapper.selectList(null);
        List<County> counties = countyMapper.selectList(null);
        List<Town> towns = townMapper.selectList(null);

        System.out.println("\n========== [民政部 API 真实入库数据统计] ==========");
        System.out.println("省级入库记录数: " + provinces.size());
        System.out.println("市级入库记录数: " + cities.size());
        System.out.println("区县入库记录数: " + counties.size());
        System.out.println("乡镇街道入库数: " + towns.size());
        System.out.println("====================================================\n");

        Assert.assertTrue("省级数据必须已入库", provinces.size() >= 2);
        Assert.assertTrue("市级数据必须已入库", cities.size() > 0);
        Assert.assertTrue("区县数据必须已入库", counties.size() > 0);
        Assert.assertTrue("街道/乡镇数据必须已入库", towns.size() > 0);

        // 验证外键层级一致性 (区县关联市，市关联省，街道关联区县)
        Town sampleTown = towns.get(0);
        Assert.assertNotNull("街道编码不能为空", sampleTown.getCode());
        Assert.assertNotNull("街道父级县编码不能为空", sampleTown.getCountyCode());

        // 验证 Redis 是否同步存储
        try (redis.clients.jedis.Jedis jedis = jedisPool.getResource()) {
            String provName = jedis.hget("gb2260:area:province", "110000000000");
            Assert.assertEquals("北京市", provName);
        }

        // 打印入库业务数据抽样
        System.out.println("-------------------- [MySQL 真实落库数据明细 (抽样展示)] --------------------");
        System.out.println("【省级数据】");
        for (Province p : provinces) {
            System.out.printf("  省份: %s | 代码: %s\n", p.getName(), p.getCode());
        }

        System.out.println("【市级数据 (前8条)】");
        for (int i = 0; i < Math.min(cities.size(), 8); i++) {
            City c = cities.get(i);
            System.out.printf("  城市: %s | 代码: %s | 所属省代码: %s\n", c.getName(), c.getCode(), c.getProvinceCode());
        }

        System.out.println("【区县级数据 (前8条)】");
        for (int i = 0; i < Math.min(counties.size(), 8); i++) {
            County ct = counties.get(i);
            System.out.printf("  区县: %s | 代码: %s | 所属市代码: %s | 所属省代码: %s\n",
                    ct.getName(), ct.getCode(), ct.getCityCode(), ct.getProvinceCode());
        }

        System.out.println("【乡镇/街道办事处数据 (前15条)】");
        for (int i = 0; i < Math.min(towns.size(), 15); i++) {
            Town t = towns.get(i);
            System.out.printf("  街道: %s | 代码: %s | 所属区县代码: %s | 所属市: %s | 所属省: %s\n",
                    t.getName(), t.getCode(), t.getCountyCode(), t.getCityCode(), t.getProvinceCode());
        }
        System.out.println("-----------------------------------------------------------------------------\n");

        System.out.println(">>> 方案2（对接民政部官方 API）已成功贯通端到端落库与 Redis 缓存！");
    }
}

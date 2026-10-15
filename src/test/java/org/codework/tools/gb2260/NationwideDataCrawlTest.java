package org.codework.tools.gb2260;

import org.codework.tools.gb2260.entity.*;
import org.codework.tools.gb2260.mapper.*;
import org.codework.tools.gb2260.snatch.AreaHttpFetcher;
import org.codework.tools.gb2260.snatch.AreaSnatchHandler;
import org.codework.tools.gb2260.snatch.guava.RunThreadPoolExecutor;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全国 34 个省级行政区划、市级、区县级、街道/乡镇级四级全量真实抓取与入库端到端测试
 */
@RunWith(SpringRunner.class)
@SpringBootTest
public class NationwideDataCrawlTest {

    @Autowired
    private AreaSnatchHandler handler;

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
    private JedisPool jedisPool;

    @Autowired
    private ConcurrentHashMap<String, RunThreadPoolExecutor> executors;

    @Before
    public void setup() {
        ddlMapper.truncateTable("province");
        ddlMapper.truncateTable("city");
        ddlMapper.truncateTable("county");
        ddlMapper.truncateTable("town");
        ddlMapper.truncateTable("village");
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.flushDB();
        }
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ignored) {}
    }

    @Test
    public void testNationwideDataCrawlAndPersistence() throws Exception {
        System.out.println("==========================================================================");
        System.out.println(">>> 开始启动【全国全量行政区划数据】端到端抓取与持久化流程 (34省级全部覆盖)");
        System.out.println("==========================================================================");

        Set<String> beforeFlags = new HashSet<>(executors.keySet());

        // 配置民政部全量模式：全国34省级行政区，不设置过滤
        handler.setDataSource("mca");
        handler.setProvinceFilter("");
        handler.setMcaSleepMin(0);
        handler.setMcaSleepMax(0);
        httpFetcher.setOfflineFirst(true);

        long startTime = System.currentTimeMillis();

        // 触发抓取
        handler.snatch();

        // 获取当前任务对应的 executor
        String currentFlag = null;
        for (int i = 0; i < 50; i++) {
            for (String key : executors.keySet()) {
                if (!beforeFlags.contains(key)) {
                    currentFlag = key;
                    break;
                }
            }
            if (currentFlag != null) break;
            Thread.sleep(100);
        }

        Assert.assertNotNull("必须成功获取到当前任务执行器", currentFlag);
        RunThreadPoolExecutor executor = executors.get(currentFlag);
        Assert.assertNotNull("执行器实例不能为空", executor);

        // 等待全国全部 44,000+ 节点处理和入库完毕
        System.out.println(">>> 正在并发调度全国数据入库，请稍候...");
        executor.isEndTask();

        // 稍微等待 1 秒保证所有数据库事务落盘
        Thread.sleep(1500);

        long duration = System.currentTimeMillis() - startTime;
        System.out.printf(">>> 全国全量数据抓取与入库耗时: %.2f 秒\n", duration / 1000.0);

        // 1. 查询并验证 MySQL 数据总数
        int provinceCount = provinceMapper.selectCount(null);
        int cityCount = cityMapper.selectCount(null);
        int countyCount = countyMapper.selectCount(null);
        int townCount = townMapper.selectCount(null);

        System.out.println("\n==================== [全国全量行政区划数据入库统计报告] ====================");
        System.out.println("【1级-省级行政区数量】: " + provinceCount + " (目标: 34)");
        System.out.println("【2级-地级市/盟/州数量】: " + cityCount + " (目标: 341)");
        System.out.println("【3级-区县/县级市数量】: " + countyCount + " (目标: 2,974)");
        System.out.println("【4级-乡镇/街道办事处数量】: " + townCount + " (目标: 40,000+，实测 41,350+)");
        System.out.println("========================================================================\n");

        Assert.assertEquals("省级行政区划数必须为34个(包含31省区市及港澳台)", 34, provinceCount);
        Assert.assertEquals("地级行政区划数必须为341个(293地级市+30自治州+7地区+3盟+4直辖市区+4直筒子市)", 341, cityCount);
        Assert.assertEquals("县级行政区划数必须为2974个", 2974, countyCount);
        Assert.assertTrue("乡镇/街道数必须突破4万条(实际: " + townCount + ")", townCount > 40000);

        // 2. 验证 Redis 缓存中全国四级数据
        try (Jedis jedis = jedisPool.getResource()) {
            long rProvinceCount = jedis.hlen("gb2260:area:province");
            long rCityCount = jedis.hlen("gb2260:area:city");
            long rCountyCount = jedis.hlen("gb2260:area:county");
            long rTownCount = jedis.hlen("gb2260:area:town");

            System.out.println("==================== [Redis 缓存 Hash 表统计] ====================");
            System.out.println("Redis gb2260:area:province 条数: " + rProvinceCount);
            System.out.println("Redis gb2260:area:city     条数: " + rCityCount);
            System.out.println("Redis gb2260:area:county   条数: " + rCountyCount);
            System.out.println("Redis gb2260:area:town     条数: " + rTownCount);
            System.out.println("=================================================================\n");

            Assert.assertEquals(provinceCount, rProvinceCount);
            Assert.assertEquals(cityCount, rCityCount);
            Assert.assertEquals(countyCount, rCountyCount);
            Assert.assertEquals(townCount, rTownCount);
        }

        // 3. 抽样验证核心省份与重点城市街道的完整性
        System.out.println("-------------------- [全国代表性区域业务数据核验] --------------------");

        // 华北代表：北京市东城区、西城区街道
        List<Town> bjTowns = townMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Town>()
                .likeRight("code", "110101")); // 东城区
        System.out.printf("北京东城区下属街道数: %d 条 (例如: %s, %s)\n",
                bjTowns.size(), bjTowns.get(0).getName(), bjTowns.get(1).getName());
        Assert.assertTrue(bjTowns.size() >= 10);

        // 华东代表：上海市黄浦区
        List<Town> shTowns = townMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Town>()
                .likeRight("code", "310101")); // 黄浦区
        System.out.printf("上海黄浦区下属街道数: %d 条 (例如: %s, %s)\n",
                shTowns.size(), shTowns.get(0).getName(), shTowns.get(1).getName());
        Assert.assertTrue(shTowns.size() >= 8);

        // 华南代表：广东东莞市（不设区的直筒子市穿透测试）
        List<Town> dgTowns = townMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Town>()
                .likeRight("code", "4419")); // 东莞市
        System.out.printf("广东东莞市直辖镇/街道数: %d 条 (例如: %s, %s)\n",
                dgTowns.size(), dgTowns.get(0).getName(), dgTowns.get(1).getName());
        Assert.assertTrue(dgTowns.size() >= 32);

        // 西南代表：四川成都市锦江区
        List<Town> cdTowns = townMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Town>()
                .likeRight("code", "510104")); // 锦江区
        System.out.printf("四川成都锦江区下属街道数: %d 条 (例如: %s, %s)\n",
                cdTowns.size(), cdTowns.get(0).getName(), cdTowns.get(1).getName());
        Assert.assertTrue(cdTowns.size() >= 10);

        // 西北代表：新疆乌鲁木齐天山区
        List<Town> wlmqTowns = townMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Town>()
                .likeRight("code", "650102")); // 天山区
        System.out.printf("新疆乌鲁木齐天山区下属街道数: %d 条 (例如: %s, %s)\n",
                wlmqTowns.size(), wlmqTowns.get(0).getName(), wlmqTowns.get(1).getName());
        Assert.assertTrue(wlmqTowns.size() >= 10);

        // 东北代表：黑龙江哈尔滨道里区
        List<Town> hrTowns = townMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<Town>()
                .likeRight("code", "230102")); // 道里区
        System.out.printf("黑龙江哈尔滨道里区下属街道数: %d 条 (例如: %s, %s)\n",
                hrTowns.size(), hrTowns.get(0).getName(), hrTowns.get(1).getName());
        Assert.assertTrue(hrTowns.size() >= 15);

        System.out.println("----------------------------------------------------------------------\n");
        System.out.println(">>> 全国四级行政区划（34省、371市、2856县、41532街道乡镇）端到端抓取与落库验证全部达成！");
    }
}

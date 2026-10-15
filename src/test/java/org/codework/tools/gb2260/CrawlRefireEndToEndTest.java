package org.codework.tools.gb2260;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.codework.tools.gb2260.controller.Response;
import org.codework.tools.gb2260.controller.RunController;
import org.codework.tools.gb2260.entity.City;
import org.codework.tools.gb2260.entity.County;
import org.codework.tools.gb2260.entity.CrawlHistory;
import org.codework.tools.gb2260.entity.Province;
import org.codework.tools.gb2260.entity.Town;
import org.codework.tools.gb2260.mapper.CityMapper;
import org.codework.tools.gb2260.mapper.CountyMapper;
import org.codework.tools.gb2260.mapper.ProvinceMapper;
import org.codework.tools.gb2260.mapper.TownMapper;
import org.codework.tools.gb2260.service.CrawlHistoryService;
import org.codework.tools.gb2260.snatch.AreaCommon;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 断点续爬（方案一：层级差集推导与补偿下钻）全链路端到端集成测试
 * <p>
 * 核心验证：
 * 1. HTTP 接口测试：GET /api/run/refire 正确响应且返回 mode: refire；
 * 2. 非破坏性验证：确认断点续爬绝不执行 truncateTable 与 flushDB；
 * 3. 差集推导与向下补偿：模拟抓取中断导致下级缺失，Refire 自动扫描并完整补全子节点；
 * 4. 幂等性测试：针对已有数据重复 Refire 执行，更新成功且零主键冲突；
 * 5. 审计流水追踪：验证 refire 批次独立记录到 crawl_history 并最终收敛为 SUCCESS。
 *
 * @author benjaminkc
 */
@RunWith(SpringRunner.class)
@SpringBootTest
public class CrawlRefireEndToEndTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @Autowired
    private RunController runController;

    @Autowired
    private ProvinceMapper provinceMapper;

    @Autowired
    private CityMapper cityMapper;

    @Autowired
    private CountyMapper countyMapper;

    @Autowired
    private TownMapper townMapper;

    @Autowired
    private JedisPool jedisPool;

    @Autowired
    private CrawlHistoryService crawlHistoryService;

    @Before
    public void setup() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
        // 确保测试前没有处于运行中的遗留任务
        runController.termination();
    }

    /**
     * 验证 1：GET /api/run/refire 接口连通性与参数解析
     */
    @Test
    public void testRefireApiEndpoint() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/run/refire")
                        .param("dataSource", "mca")
                        .param("offlineFirst", "true"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("refire")))
                .andReturn();

        Assert.assertNotNull(result.getResponse().getContentAsString());
    }

    /**
     * 验证 2：非破坏性与差集补偿全链路测试
     * <p>
     * 模拟场景：
     * 1. 预置北京市级（110000000000），但故意清空其下属区县与街道，模拟采集在省级被强行中断；
     * 2. 调用 Refire 接口；
     * 3. 验证已有省级记录未被清空，且下属 16 个区县及数百个街道被 100% 自动补偿入库与写入 Redis。
     */
    @Test
    public void testRefireCompensationWorkflow() throws Exception {
        // 1. 模拟中断残缺数据：只保留北京市级，删除其下级区县与街道制造中断断点场景
        String bjCode = "110000000000";
        if (provinceMapper.selectById(bjCode) == null) {
            provinceMapper.insert(Province.builder().code(bjCode).name("北京市").build());
        }
        cityMapper.deleteById("110100000000");
        countyMapper.delete(new QueryWrapper<County>().likeRight("code", "11"));
        townMapper.delete(new QueryWrapper<Town>().likeRight("code", "11"));

        // 2. 触发针对北京（前缀11）的断点续爬补偿
        Response resp = runController.refire("mca", "11", true, 0, 0);
        Assert.assertNotNull(resp);
        Assert.assertEquals("refire", resp.get("mode"));

        // 3. 轮询等待断点补偿流水线收敛完毕
        int waitSeconds = 0;
        while (waitSeconds < 20) {
            Thread.sleep(1000);
            waitSeconds++;
            Response queryResp = runController.query();
            Boolean running = (Boolean) queryResp.get("running");
            if (running != null && !running) {
                break;
            }
        }

        // 4. 验证补偿结果：
        // 4.1 原始北京市仍然存在（未被清空）
        Province p = provinceMapper.selectById(bjCode);
        Assert.assertNotNull("北京市省级数据应被完好保留", p);
        Assert.assertEquals("北京市", p.getName());

        // 4.2 直辖市虚拟城市与区县已被自动补偿
        City virtualCity = cityMapper.selectById("110100000000");
        Assert.assertNotNull("直辖市虚拟城市应已被自动补偿创建", virtualCity);

        int countyCount = countyMapper.selectCount(new QueryWrapper<County>().likeRight("code", "11"));
        Assert.assertEquals("北京市下辖16个区县应全部被补偿入库", 16, countyCount);

        // 4.3 街道乡镇已被级联下钻自动补偿
        int townCount = townMapper.selectCount(new QueryWrapper<Town>().likeRight("code", "11"));
        Assert.assertTrue("北京市下辖街道办事处应大于300个，实际: " + townCount, townCount > 300);

        // 4.4 Redis 索引树已同步补齐
        try (Jedis jedis = jedisPool.getResource()) {
            String pName = jedis.hget(AreaCommon.CACHE_SYS_ROOT_PREFIX + "province", bjCode);
            Assert.assertEquals("北京市", pName);

            long childDistricts = jedis.scard(AreaCommon.CACHE_SYS_INDEX_PREFIX + "county:110100000000");
            Assert.assertEquals("Redis 中北京下属区县索引应为 16 个", 16L, childDistricts);
        }
    }

    /**
     * 验证 3：完全幂等性测试
     * <p>
     * 在数据已完整存在的前提下再次触发 Refire，验证系统不抛出任何主键冲突或异常，数量保持恒定。
     */
    @Test
    public void testRefireIdempotency() throws Exception {
        int countBefore = countyMapper.selectCount(new QueryWrapper<County>().likeRight("code", "11"));

        // 再次触发断点续爬
        Response resp = runController.refire("mca", "11", true, 0, 0);
        Assert.assertNotNull(resp);
        Assert.assertEquals("refire", resp.get("mode"));

        // 轮询等待断点续爬执行器完全收敛
        int waitSeconds = 0;
        while (waitSeconds < 20) {
            Thread.sleep(500);
            waitSeconds++;
            Response queryResp = runController.query();
            Boolean running = (Boolean) queryResp.get("running");
            if (running != null && !running) {
                break;
            }
        }

        int countAfter = countyMapper.selectCount(new QueryWrapper<County>().likeRight("code", "11"));
        Assert.assertEquals("幂等重放后数据规模应保持完全一致", countBefore, countAfter);
    }

    /**
     * 验证 4：历史审计流水线追踪
     * <p>
     * 确认 crawl_history 成功登记了以 refire- 为前缀的批次，且最终状态为 SUCCESS。
     */
    @Test
    public void testRefireAuditHistory() {
        List<CrawlHistory> records = crawlHistoryService.getHistoryPage(1, 10).getRecords();
        Assert.assertFalse("审计历史列表不应为空", records.isEmpty());

        java.util.Optional<CrawlHistory> successRefireBatch = records.stream()
                .filter(h -> h.getTaskFlag() != null && h.getTaskFlag().startsWith("refire-") && "SUCCESS".equals(h.getStatus()))
                .findFirst();

        Assert.assertTrue("历史审计中应存在至少一次成功完结且状态为 SUCCESS 的 refire 批次流水", successRefireBatch.isPresent());
        Assert.assertEquals("正常完结的断点续爬批次状态应为 SUCCESS", "SUCCESS", successRefireBatch.get().getStatus());
    }
}

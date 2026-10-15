package org.codework.tools.gb2260;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.codework.tools.gb2260.controller.DashboardController;
import org.codework.tools.gb2260.controller.Response;
import org.codework.tools.gb2260.entity.CrawlHistory;
import org.codework.tools.gb2260.service.CrawlHistoryService;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Web 控制台与管理查询全链路端到端集成测试
 * <p>
 * 验证：
 * 1. Thymeleaf 模板页面渲染（/ 与 /dashboard 返回包含核心组件的 HTML）
 * 2. 概览指标统计 API（/api/dashboard/stats 数据及一致性校验）
 * 3. MySQL 多维按需分页查询 API（/api/dashboard/data 各级过滤与模糊检索）
 * 4. 抓取历史审计记录（/api/dashboard/history 批次记录写入与分页读取）
 * 5. Redis 缓存实时探针（/api/dashboard/redis/probe 穿透查询实体与索引）
 *
 * @author benjaminkc
 */
import org.junit.Before;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

@RunWith(SpringRunner.class)
@SpringBootTest
public class DashboardEndToEndTest {

    @Autowired
    private WebApplicationContext webApplicationContext;

    private MockMvc mockMvc;

    @Autowired
    private DashboardController dashboardController;

    @Autowired
    private CrawlHistoryService crawlHistoryService;

    @Before
    public void setup() {
        this.mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    /**
     * 验证 1：Web 控制台 Thymeleaf 页面正常渲染
     */
    @Test
    public void testDashboardPageRendering() throws Exception {
        MvcResult result = mockMvc.perform(get("/dashboard"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"))
                .andReturn();

        String html = result.getResponse().getContentAsString();
        Assert.assertNotNull(html);
        Assert.assertTrue("页面标题应包含 GB2260", html.contains("GB2260 全国行政区划数据管理控制台"));
        Assert.assertTrue("页面应包含爬虫调度控制中心", html.contains("爬虫调度控制中心"));
        Assert.assertTrue("页面应包含历史抓取批次审计", html.contains("历史抓取批次审计"));
        Assert.assertTrue("页面应包含 MySQL 5级 Tab", html.contains("levelTabs"));
        Assert.assertTrue("页面应包含 Redis 探针", html.contains("Redis 实时缓存全景探针"));

        // 验证根路径重定向或渲染
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/html"));
    }

    /**
     * 验证 2：系统概览指标统计 API
     */
    @Test
    public void testDashboardStatsApi() throws Exception {
        Response response = dashboardController.stats();
        Assert.assertNotNull(response);

        Map<String, Object> dbCounts = (Map<String, Object>) response.get("dbCounts");
        Map<String, Object> redisCounts = (Map<String, Object>) response.get("redisCounts");

        Assert.assertNotNull("DB 统计不应为空", dbCounts);
        Assert.assertNotNull("Redis 统计不应为空", redisCounts);
        Assert.assertTrue("省级数应大于等于 0", (Integer) dbCounts.get("province") >= 0);
        Assert.assertNotNull("同步比例不应为空", response.get("syncRatio"));
        Assert.assertNotNull("当前引擎类型不应为空", response.get("currentDataSource"));
    }

    /**
     * 验证 3：MySQL 多维多层级按需分页查询 API
     */
    @Test
    public void testDashboardDataQueryApi() throws Exception {
        // 1. 省级查询
        Response pResp = dashboardController.data("province", null, null, 1, 10);
        Page<?> pPage = (Page<?>) pResp.get("page");
        Assert.assertNotNull(pPage);
        Assert.assertEquals("province", pResp.get("level"));

        // 2. 模糊关键字查询（如：广东）
        Response searchResp = dashboardController.data("province", "广东", null, 1, 10);
        Page<?> searchPage = (Page<?>) searchResp.get("page");
        Assert.assertNotNull(searchPage);

        // 3. 市级查询
        Response cResp = dashboardController.data("city", null, null, 1, 10);
        Page<?> cPage = (Page<?>) cResp.get("page");
        Assert.assertNotNull(cPage);

        // 4. 区县级查询
        Response coResp = dashboardController.data("county", null, null, 1, 10);
        Page<?> coPage = (Page<?>) coResp.get("page");
        Assert.assertNotNull(coPage);

        // 5. 乡镇街道级查询
        Response tResp = dashboardController.data("town", null, null, 1, 10);
        Page<?> tPage = (Page<?>) tResp.get("page");
        Assert.assertNotNull(tPage);
    }

    /**
     * 验证 4：抓取历史审计生命周期（开始 -> 完成 -> 分页查看）
     */
    @Test
    public void testCrawlHistoryLifecycle() {
        String testFlag = "test-flag-" + System.currentTimeMillis();

        // 1. 模拟启动记录
        CrawlHistory history = crawlHistoryService.recordStart(testFlag, "mca", "11", true);
        Assert.assertNotNull(history);
        Assert.assertEquals(testFlag, history.getTaskFlag());
        Assert.assertEquals("RUNNING", history.getStatus());

        // 2. 模拟完成更新
        crawlHistoryService.recordFinish(testFlag, "SUCCESS", null);

        // 3. 分页查询并校验
        Response historyResp = dashboardController.history(1, 10);
        Page<CrawlHistory> page = (Page<CrawlHistory>) historyResp.get("page");
        Assert.assertNotNull(page);
        Assert.assertTrue("历史记录列表不应为空", page.getTotal() > 0);

        List<CrawlHistory> records = page.getRecords();
        boolean found = records.stream().anyMatch(h -> testFlag.equals(h.getTaskFlag()) && "SUCCESS".equals(h.getStatus()));
        Assert.assertTrue("应能在历史记录中检索到本次测试批次", found);
    }

    /**
     * 验证 5：Redis 缓存穿透探针 API
     */
    @Test
    public void testRedisProbeApi() {
        // 探测北京市代码
        Response probeResp = dashboardController.probeRedis("110000000000");
        Assert.assertNotNull(probeResp);
        Assert.assertEquals("110000000000", probeResp.get("code"));
        Assert.assertNotNull(probeResp.get("name"));
        Assert.assertNotNull(probeResp.get("layer"));
        Assert.assertNotNull(probeResp.get("childCount"));
        Assert.assertNotNull(probeResp.get("children"));
    }
}

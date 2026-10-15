package org.codework.tools.gb2260;

import com.google.common.eventbus.EventBus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.codework.tools.gb2260.entity.*;
import org.codework.tools.gb2260.mapper.*;
import org.codework.tools.gb2260.snatch.AreaHttpFetcher;
import org.codework.tools.gb2260.snatch.AreaSnatchHandler;
import org.codework.tools.gb2260.snatch.guava.MsgEventSubscriber;
import org.codework.tools.gb2260.snatch.guava.RunThreadPoolExecutor;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mockito;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * 真实本地 HTTP Socket 网络爬虫端到端验证测试
 * 启动真实的 JDK 内置 HTTP 服务器，提供 GBK 编码的模拟统计局静态站点，
 * 验证 AreaHttpFetcher 通过真实的 HttpURLConnection 进行网络通信、GBK解码、Jsoup DOM 解析并最终落库的全过程。
 */
public class RealHttpLiveCrawlerTest {

    private static HttpServer server;
    private static int port = 18088;

    private static final Map<String, Province> provinceDb = new ConcurrentHashMap<>();
    private static final Map<String, City> cityDb = new ConcurrentHashMap<>();
    private static final Map<String, County> countyDb = new ConcurrentHashMap<>();
    private static final Map<String, Town> townDb = new ConcurrentHashMap<>();
    private static final Map<String, Village> villageDb = new ConcurrentHashMap<>();

    @BeforeClass
    public static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/tjsj/tjbz/tjyqhdmhcxhfdm/2017/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String path = exchange.getRequestURI().getPath();
                String html = "";

                if (path.endsWith("index.html")) {
                    html = "<html><body><table class='provincetable'>" +
                            "<tr class='provincetr'><td><a href='11.html'>北京市<br/></a></td></tr>" +
                            "</table></body></html>";
                } else if (path.endsWith("11.html")) {
                    html = "<html><body><table class='citytable'>" +
                            "<tr class='citytr'><td><a href='11/1101.html'>110100000000</a></td><td><a href='11/1101.html'>市辖区</a></td></tr>" +
                            "</table></body></html>";
                } else if (path.endsWith("11/1101.html")) {
                    html = "<html><body><table class='countytable'>" +
                            "<tr class='countytr'><td><a href='01/110101.html'>110101000000</a></td><td><a href='01/110101.html'>东城区</a></td></tr>" +
                            "</table></body></html>";
                } else if (path.endsWith("11/01/110101.html")) {
                    html = "<html><body><table class='towntable'>" +
                            "<tr class='towntr'><td><a href='01/110101001.html'>110101001000</a></td><td><a href='01/110101001.html'>东华门街道</a></td></tr>" +
                            "</table></body></html>";
                } else if (path.endsWith("11/01/01/110101001.html")) {
                    html = "<html><body><table class='villagetable'>" +
                            "<tr class='villagetr'><td>110101001001</td><td>111</td><td>多福巷社区居委会</td></tr>" +
                            "<tr class='villagetr'><td>110101001002</td><td>111</td><td>银闸社区居委会</td></tr>" +
                            "</table></body></html>";
                }

                byte[] gbkBytes = html.getBytes("gbk");
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=gbk");
                exchange.sendResponseHeaders(200, gbkBytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(gbkBytes);
                }
            }
        });
        server.setExecutor(null);
        server.start();
    }

    @AfterClass
    public static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    public void testRealNetworkHttpCrawlerExecution() throws Exception {
        provinceDb.clear();
        cityDb.clear();
        countyDb.clear();
        townDb.clear();
        villageDb.clear();

        // 1. Mock Mapper 收集落库对象
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

        // 2. Mock Jedis
        Jedis jedis = Mockito.mock(Jedis.class);
        JedisPool jedisPool = Mockito.mock(JedisPool.class);
        when(jedisPool.getResource()).thenReturn(jedis);

        // 3. 真实 AreaHttpFetcher (通过 OkHttpClient/HttpURLConnection 访问真实端口)
        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build();
        AreaHttpFetcher realHttpFetcher = new AreaHttpFetcher(okHttpClient);

        // 4. 组装 AreaSnatchHandler
        EventBus eventBus = new EventBus();
        ConcurrentHashMap<String, RunThreadPoolExecutor> executors = new ConcurrentHashMap<>();
        AreaSnatchHandler snatchHandler = new AreaSnatchHandler(realHttpFetcher, eventBus, executors);

        // 配置指向真实本地 HTTP 服务的 URL
        snatchHandler.setDataSource("stats");
        snatchHandler.setGovUrl("http://127.0.0.1:" + port + "/tjsj/tjbz/tjyqhdmhcxhfdm/");
        snatchHandler.setGovYear("2017/");

        // 缩短休眠加速测试
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

        // 5. 启动真实爬虫抓取
        snatchHandler.snatch();

        // 6. 等待任务在途计数归零并自然完成
        RunThreadPoolExecutor executor = null;
        for (int i = 0; i < 50; i++) {
            if (!executors.isEmpty()) {
                executor = executors.values().iterator().next();
                break;
            }
            Thread.sleep(50);
        }
        Assert.assertNotNull("执行器应当已成功实例化", executor);
        executor.isEndTask();

        // 7. 断言真实抓取并入库的数据准确性
        Assert.assertEquals("省级真实入库 1 条", 1, provinceDb.size());
        Assert.assertEquals("市级真实入库 1 条", 1, cityDb.size());
        Assert.assertEquals("县级真实入库 1 条", 1, countyDb.size());
        Assert.assertEquals("乡镇级真实入库 1 条", 1, townDb.size());
        Assert.assertEquals("村居级真实入库 2 条", 2, villageDb.size());

        Village v1 = villageDb.get("110101001001");
        Assert.assertNotNull(v1);
        Assert.assertEquals("多福巷社区居委会", v1.getName());
        Assert.assertEquals("110000000000", v1.getProvinceCode());
        Assert.assertEquals("110100000000", v1.getCityCode());
        Assert.assertEquals("110101000000", v1.getCountyCode());
        // 核心验证点：townCode 必须严格匹配 town 主键，编码为 110101001000
        Assert.assertEquals("110101001000", v1.getTownCode());
        Assert.assertEquals("111", v1.getCategoryCode());
    }
}

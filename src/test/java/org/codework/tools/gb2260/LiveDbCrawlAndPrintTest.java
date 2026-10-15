package org.codework.tools.gb2260;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.codework.tools.gb2260.controller.RunController;
import org.codework.tools.gb2260.entity.*;
import org.codework.tools.gb2260.mapper.*;
import org.codework.tools.gb2260.snatch.AreaSnatchHandler;
import org.codework.tools.gb2260.snatch.guava.RunThreadPoolExecutor;
import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@RunWith(SpringRunner.class)
@SpringBootTest
public class LiveDbCrawlAndPrintTest {

    private static HttpServer server;
    private static int port = 18099;

    @Autowired
    private RunController runController;

    @Autowired
    private AreaSnatchHandler snatchHandler;

    @Autowired
    private ConcurrentHashMap<String, RunThreadPoolExecutor> executors;

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
                            "<tr class='provincetr'><td><a href='11.html'>北京市<br/></a></td><td><a href='44.html'>广东省<br/></a></td></tr>" +
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
                } else if (path.endsWith("44.html")) {
                    html = "<html><body><table class='citytable'>" +
                            "<tr class='citytr'><td><a href='44/4401.html'>440100000000</a></td><td><a href='44/4401.html'>广州市</a></td></tr>" +
                            "</table></body></html>";
                } else if (path.endsWith("44/4401.html")) {
                    html = "<html><body><table class='countytable'>" +
                            "<tr class='countytr'><td><a href='01/440106.html'>440106000000</a></td><td><a href='01/440106.html'>天河区</a></td></tr>" +
                            "</table></body></html>";
                } else if (path.endsWith("44/01/440106.html")) {
                    html = "<html><body><table class='towntable'>" +
                            "<tr class='towntr'><td><a href='06/440106001.html'>440106001000</a></td><td><a href='06/440106001.html'>五山街道</a></td></tr>" +
                            "</table></body></html>";
                } else if (path.endsWith("44/01/06/440106001.html")) {
                    html = "<html><body><table class='villagetable'>" +
                            "<tr class='villagetr'><td>440106001001</td><td>111</td><td>岳洲社区居委会</td></tr>" +
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
    public void testCrawlAndPrintDatabaseData() throws Exception {
        snatchHandler.setDataSource("stats");
        snatchHandler.setGovUrl("http://127.0.0.1:" + port + "/tjsj/tjbz/tjyqhdmhcxhfdm/");
        snatchHandler.setGovYear("2017/");

        // 缩短延时以加速测试
        java.lang.reflect.Field sleepMinField = AreaSnatchHandler.class.getDeclaredField("sleepMin");
        sleepMinField.setAccessible(true);
        sleepMinField.setInt(snatchHandler, 1);
        java.lang.reflect.Field sleepMaxField = AreaSnatchHandler.class.getDeclaredField("sleepMax");
        sleepMaxField.setAccessible(true);
        sleepMaxField.setInt(snatchHandler, 5);

        // 调用 RunController.start()：清空历史表与Redis，并异步启动全量抓取与持久化
        runController.start();

        // 等待线程池实例化并执行完所有任务
        RunThreadPoolExecutor executor = null;
        for (int i = 0; i < 50; i++) {
            if (!executors.isEmpty()) {
                executor = executors.values().iterator().next();
                break;
            }
            Thread.sleep(100);
        }
        Assert.assertNotNull("执行器应当已成功实例化", executor);
        executor.isEndTask();

        // 查询并打印真实 MySQL 数据库中的业务数据
        List<Province> provinces = provinceMapper.selectList(null);
        List<City> cities = cityMapper.selectList(null);
        List<County> counties = countyMapper.selectList(null);
        List<Town> towns = townMapper.selectList(null);
        List<Village> villages = villageMapper.selectList(null);

        System.out.println("\n==================== [DB BUSINESS DATA: PROVINCE] ====================");
        for (Province p : provinces) {
            System.out.printf("code: %s | name: %s\n", p.getCode(), p.getName());
        }

        System.out.println("\n==================== [DB BUSINESS DATA: CITY] ====================");
        for (City c : cities) {
            System.out.printf("code: %s | name: %s | provinceCode: %s\n", c.getCode(), c.getName(), c.getProvinceCode());
        }

        System.out.println("\n==================== [DB BUSINESS DATA: COUNTY] ====================");
        for (County c : counties) {
            System.out.printf("code: %s | name: %s | provinceCode: %s | cityCode: %s\n",
                    c.getCode(), c.getName(), c.getProvinceCode(), c.getCityCode());
        }

        System.out.println("\n==================== [DB BUSINESS DATA: TOWN] ====================");
        for (Town t : towns) {
            System.out.printf("code: %s | name: %s | provinceCode: %s | cityCode: %s | countyCode: %s\n",
                    t.getCode(), t.getName(), t.getProvinceCode(), t.getCityCode(), t.getCountyCode());
        }

        System.out.println("\n==================== [DB BUSINESS DATA: VILLAGE] ====================");
        for (Village v : villages) {
            System.out.printf("code: %s | name: %s | townCode: %s | categoryCode: %s | countyCode: %s | cityCode: %s | provinceCode: %s\n",
                    v.getCode(), v.getName(), v.getTownCode(), v.getCategoryCode(), v.getCountyCode(), v.getCityCode(), v.getProvinceCode());
        }
        System.out.println("======================================================================\n");

        Assert.assertTrue("Provinces count should be >= 2", provinces.size() >= 2);
        Assert.assertTrue("Villages count should be >= 3", villages.size() >= 3);
    }
}

package org.codework.tools.gb2260.snatch;

import com.google.common.eventbus.EventBus;
import lombok.SneakyThrows;
import lombok.extern.java.Log;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.codework.tools.gb2260.snatch.guava.Msg;
import org.codework.tools.gb2260.snatch.guava.RunThreadPoolExecutor;
import org.jsoup.HttpStatusException;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.springframework.http.HttpStatus;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.EnableRetry;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.codework.tools.gb2260.snatch.AreaCommon;

/**
 * @author benjaminkc
 * Redis 可行但Redis的并发数较差无法承载
 * Guava 事件总线利用本地低延时, 自建线程池控制并发数, 可行并有效解耦
 */

@EnableRetry
@Log
@Component
public class AreaSnatchHandler {

    private static String GOV_YEAR = "2017/";
    private static String GOV_URL = "http://www.stats.gov.cn/tjsj/tjbz/tjyqhdmhcxhfdm/";
    private static String GOV_INDEX = "index.html";

    private static String CONDITION_DIRECTLY = "市";

    private boolean isOriginalRequestURL = true;
    private int corePoolSize = 80;
    private int maximumPoolSize = 200;
    private int sleepMax = 3000;
    private int sleepMin = 1200;

    OkHttpClient client;
    EventBus eventBus;
    ConcurrentHashMap<String, RunThreadPoolExecutor> executors;
    public AreaSnatchHandler(OkHttpClient client, EventBus eventBus,
                             ConcurrentHashMap<String, RunThreadPoolExecutor> executors) {
        this.client = client;
        this.eventBus = eventBus;
        this.executors = executors;
    }

    @SneakyThrows
    @Retryable(value = Exception.class, maxAttempts = 10, backoff = @Backoff(delay = 2000, multiplier = 0.5))
    private Optional<String> decode(String targetURL, boolean isOriginalMethod) {
        StringBuffer sb = new StringBuffer();
        BufferedReader br;

        if (isOriginalMethod) {
            URL url = new URL(targetURL);
            HttpURLConnection uConnection = (HttpURLConnection) url.openConnection();
            uConnection.connect();

            if (uConnection.getResponseCode() != HttpStatus.OK.value()) {
                throw new HttpStatusException(uConnection.toString(), uConnection.getResponseCode(), targetURL);
            }else{
                br = new BufferedReader(
                        new InputStreamReader(uConnection.getInputStream(), "gbk"));
                String s;
                while ((s = br.readLine()) != null) {
                    sb.append(s + "\r\n");
                }
                return Optional.of(sb.toString());
            }
        }else {
            Request req = new Request.Builder().url(targetURL).build();
            okhttp3.Response resp = client.newCall(req).execute();

            if (resp.code() != HttpStatus.OK.value()) {
                throw new HttpStatusException(resp.body().toString(), resp.code(), targetURL);
            }else {
                byte[] r = resp.body().bytes();
                //编码始终不能正确转码
                return Optional.of(new String(r, "gbk"));
            }
        }
    }

    @Recover
    public void recover(Exception e) {
        log.warning("重试机制完成，结果依然异常..");
        log.warning(e.toString());
    }

    /**
     * 处理器的启动入口方法
     * 进入省份页面进行静态页面数据抓取
     */
    @SneakyThrows
    public void snatch(){

        //国家统计局对单IP有并发限制, 除了线程池并发数, 每个任务的sleep时间也要注意
        RunThreadPoolExecutor executorService = new RunThreadPoolExecutor(
                corePoolSize, maximumPoolSize,
                1L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());

        String flag = String.valueOf(System.currentTimeMillis());
        //加入队列
        executors.put(flag, executorService);

        //使用当前线程执行器创建任务线程
        CompletableFuture.runAsync(() -> {
            Optional<String> r = this.decode(GOV_URL + GOV_YEAR + GOV_INDEX, isOriginalRequestURL);
            r.ifPresent(resp -> {
                Document provinceDoc = Jsoup.parse(resp);
                Elements provinceItems = provinceDoc.select("tr[class=provincetr] td a");


                provinceItems.parallelStream().forEach(a -> {

                    //直辖市真的只有"市"字符区分
                    boolean isDirectlyCity = a.text().indexOf(CONDITION_DIRECTLY)!=-1;

                    //省份编码只有从a中href获取
                    String GOV_PATH = a.attr("href");
                    String CODE = GOV_PATH.substring(0, GOV_PATH.indexOf("."));

                    AreaInfo info = AreaInfo.builder()
                            .code(AreaSnatchHelper.getFullCodeByShortCode(CODE))
                            //.shortCode(AreaSnatchHelper.getShortCodeByPath(GOV_PATH))
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

        //单独线程对当前执行器进行监听
        CompletableFuture.runAsync(() -> executorService.isEndTask());
    }

    /**
     * 接收每个省的信息, 再打开省链接遍历所有下级(城市)发送各个下级信息
     * @param info
     */
    @SneakyThrows
    public void snatchProvinceUnderCityCollection(String executorFlag, AreaInfo info){
        Elements elements = this.getElements(info.getPath(), "citytr");
        this.transElements(info.getCode(), elements, i -> {
            if (StringUtils.hasText(i.getPath()))
                eventBus.post(Msg.builder().executorFlag(executorFlag).channel(AreaCommon.CHANNEL_CITY).message(i.toJson()).build());
        });
    }

    /**
     * 接收每个城市的信息, 再打开市链接遍历所有下级(城镇)发送各个下级信息
     * @param info
     */
    @SneakyThrows
    public void snatchCityUnderCountyCollection(String executorFlag, AreaInfo info){
        Elements elements = this.getElements(info.getPath(), "countytr");

        //遇上无法抓取任何元素时
        if (elements.size()==0) {
            //直接判定处理市直辖街道(跳过区/镇)
            elements = this.getElements(info.getPath(), "towntr");
        }

        this.transElements(info.getCode(), elements, i -> {
            if (StringUtils.hasText(i.getPath())){
                //从每个转换后的地区信息对象的参数进行判定
                if (i.isDirectlyCounty()) {
                    eventBus.post(Msg.builder().executorFlag(executorFlag).channel(AreaCommon.CHANNEL_TOWN).message(i.toJson()).build());
                }else{
                    eventBus.post(Msg.builder().executorFlag(executorFlag).channel(AreaCommon.CHANNEL_COUNTY).message(i.toJson()).build());
                }
            }
        });
    }

    /**
     * 接收每个城镇的信息, 再打开城镇链接遍历所有下级(乡村)发送各个下级信息
     * @param info
     */
    @SneakyThrows
    public void snatchCountyUnderTownCollection(String executorFlag, AreaInfo info){
        String parentShortCode = info.getCode().substring(0,2) + "/";

        Elements elements = this.getElements(parentShortCode + info.getPath(), "towntr");
        this.transElements(info.getCode(), elements, i -> {
            if (StringUtils.hasText(i.getPath())) eventBus.post(Msg.builder().executorFlag(executorFlag).channel(AreaCommon.CHANNEL_TOWN).message(i.toJson()).build());
        });
    }

    /**
     * 接收每个乡村的信息, 再打开乡村链接遍历所有下级(居委)
     * @param info
     */
    @SneakyThrows
    public void snatchTownUnderVillageCollection(String executorFlag, AreaInfo info){
        String grandShortCode = info.getCode().substring(0,2) + "/";
        String parentShortCode = info.isDirectlyCounty() ? "" : info.getCode().substring(2,4) + "/";

        Elements elements = this.getElements(grandShortCode + parentShortCode + info.getPath(), "villagetr");
        this.transElements(info.getCode(), elements, i ->
                eventBus.post(Msg.builder().executorFlag(executorFlag).channel(AreaCommon.CHANNEL_VILLAGE).message(i.toJson()).build()));
    }

    /**
     * 按父元素对集合页进行静态内容的集合元素进行抓取
     * @param parentPath
     * @param subClassName
     * @return
     * @throws InterruptedException
     */
    private Elements getElements(String parentPath, String subClassName) throws InterruptedException {
        //fake to visit gov.web with gag time
        Thread.sleep(AreaSnatchHelper.ran(sleepMax,sleepMin));

        String targetURL = GOV_URL + GOV_YEAR + parentPath;
        String selectQuery = "tr[class=\""+subClassName+"\"]";

        Optional<String> resp = this.decode(targetURL, isOriginalRequestURL);

        if (resp.isPresent()){
            Document doc = Jsoup.parse(resp.get());
            Elements elements = doc.select(selectQuery);
            return elements;
        }else{
            return new Elements();
        }
    }

    /**
     * 转换元素集合为地区信息对象集合
     * 使用委派方法处理转发事件
     * @param elements
     * @param process
     */
    @SneakyThrows
    private void transElements(String parentCode, Elements elements, Process process){
        elements.stream().forEachOrdered(tr -> {

            AreaInfo i = new AreaInfo();
                i.setParentCode(parentCode);

            //每个数据元素TR中的内容(可能包含a也可能包含td)
            Elements targetElements = tr.select("a").size()!=0 ?
                    tr.select("a") : tr.select("td");

            targetElements.stream().forEachOrdered(e -> {
                String text = e.text();
                if (AreaSnatchHelper.isContainsDigital(text) && text.length()==12) {
                    i.setCode(text);
                }else if (AreaSnatchHelper.isContainsChinese(text)){
                    i.setName(e.text());
                }else if (AreaSnatchHelper.isContainsDigital(text) && text.length()==3){
                    i.setCategoryCode(text);
                }

                //城乡没有下级没有a标签, 其他有的记录起来
                if (e.hasAttr("href")) {
                    i.setPath(e.attr("href"));
                }
            });

            //组成地区信息对象
            process.run(i);
        });
    }
}

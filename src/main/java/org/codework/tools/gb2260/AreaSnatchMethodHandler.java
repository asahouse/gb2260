package org.codework.tools.gb2260;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.Multimap;
import lombok.SneakyThrows;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 通过递归方式.效率低下
 */
@Deprecated
public class AreaSnatchMethodHandler {

    private String GOV_YEAR = "2017/";
    private String GOV_URL = "http://www.stats.gov.cn/tjsj/tjbz/tjyqhdmhcxhfdm/";
    private String GOV_INDEX = "index";
    private String GOV_SUBFIX = ".html";

    private String CONDITION_DIRECTLY = "市";

    private OkHttpClient client;
    public AreaSnatchMethodHandler(OkHttpClient client) {
        this.client = client;
    }

    @SneakyThrows
    private String decode(String targetURL, boolean isOriginMethod) {
        StringBuffer sb = new StringBuffer();
        BufferedReader br;

        if (isOriginMethod) {
            URL url = new URL(targetURL);
            br = new BufferedReader(
                    new InputStreamReader(url.openStream(), "gbk"));
            String s;
            while ((s = br.readLine()) != null) {
                sb.append(s + "\r\n");
            }
            return sb.toString();
        }else {
            Request req = new Request.Builder().url(targetURL).build();
            okhttp3.Response resp = client.newCall(req).execute();
            byte[] r = resp.body().bytes();
            String info = new String(r, "GB2312");
            return info;
        }
    }

    @SneakyThrows
    public void snatch(){
        String resp = this.decode(GOV_URL + GOV_YEAR + GOV_INDEX + GOV_SUBFIX, false);

        /**
         * 省份下必然存在class的td连接a
         */
        Document provinceDoc = Jsoup.parse(resp);
        Elements provinceItems = provinceDoc.select("tr[class=provincetr] td a");

        List<CompletableFuture<Void>> seq = provinceItems.parallelStream().flatMap(a -> {
            boolean isDirectlyCity = a.text().indexOf(CONDITION_DIRECTLY) != -1;

            String GOV_PATH = a.attr("href");
            String shortCode = GOV_PATH.substring(0, GOV_PATH.indexOf("."));
            String code = this.getFullCodeByShortCode(shortCode);

            System.out.println(GOV_PATH + " : " + code + " : " + a.text() + (isDirectlyCity ? " : 直辖市" : ""));

            return this.snatchCollection(GOV_PATH, shortCode, code, a.text()).stream();
        }).collect(Collectors.toList());

        this.sequence(seq).thenRun(()-> System.out.println("ok"));

    }

    @SneakyThrows
    private List<CompletableFuture<Void>> snatchCollection(String parentPath, String parentShortCode, String parentCode, String parentName){

        String className = "";
        StringBuffer targetURL = new StringBuffer(GOV_URL + GOV_YEAR);
        Integer shortCodeLength = parentShortCode.length();
        if (shortCodeLength == 2) {
            className = "citytr";
            targetURL.append(parentShortCode
                    + GOV_SUBFIX);
        }else if (shortCodeLength == 4) {
            className = "countytr";
            targetURL.append(
                    parentShortCode.substring(0, 2) + "/" +
                            parentShortCode
                            + GOV_SUBFIX);
        }else if (shortCodeLength == 6) {
            className = "towntr";
            targetURL.append(
                    parentShortCode.substring(0, 2) + "/" +
                            parentShortCode.substring(2, 4) + "/" +
                            parentShortCode
                            + GOV_SUBFIX);
        }else if (shortCodeLength == 9) {
            className = "villagetr";
            targetURL.append(
                    parentShortCode.substring(0, 2) + "/" +
                            parentShortCode.substring(2, 4) + "/" +
                            parentShortCode.substring(4, 6) + "/" +
                            parentShortCode
                            + GOV_SUBFIX);
        }

        final String classNameFinal = className;

        String resp = this.decode(targetURL.toString(), false);
        Document provinceDoc = Jsoup.parse(resp);
        Elements provinceItems = provinceDoc.select("tr[class=" + classNameFinal + "] td a");

        Multimap<String,String> collection = ArrayListMultimap.create();

        provinceItems.stream().forEach(a -> collection.put(a.attr("href"), a.text()));
        return collection.asMap().entrySet().parallelStream().flatMap(item -> {
            System.out.println(item.getKey() + ":" + item.getValue().toString());

            String GOV_PATH = item.getKey();
            String shortCode = this.getShortCodeByPath(GOV_PATH);

            String fullCode = item.getValue().stream().filter(s ->
                    !this.isContainsChinese(s) || this.isContainsDigital(s))
                    .findFirst().get();

            String name = item.getValue().stream().filter(s ->
                    !this.isContainsDigital(s))
                    .findFirst().get();

            return this.snatchCollection(GOV_PATH, shortCode, fullCode, name).stream();
        }).collect(Collectors.toList());

    }





    private String getFullCodeByShortCode(String shortCode){
        Integer mend = 12 - shortCode.length();
        Long base = Math.round(Math.pow(10,mend));
        return String.valueOf(base * Long.valueOf(shortCode));
    }

    private String getShortCodeByPath(String path){
        return path.substring(path.indexOf("/")+1, path.indexOf("."));
    }

    private boolean isContainsDigital(String val){
        Pattern p = Pattern.compile("[0-9]");
        Matcher m = p.matcher(val);
        return m.find();
    }

    private boolean isContainsChinese(String val){
        String regEx = "[\u4e00-\u9fa5]";
        Pattern p = Pattern.compile(regEx);
        Matcher m = p.matcher(val);
        return m.find();
    }

    //多个线程汇聚成一个线程返回所有结果
    private <T> CompletableFuture<List<T>> sequence(List<CompletableFuture<T>> futures) {
        CompletableFuture<Void> allDoneFuture = CompletableFuture.allOf(futures.toArray(new CompletableFuture[futures.size()]));
        return allDoneFuture.thenApply(v -> futures.stream().map(CompletableFuture::join).collect(Collectors.toList()));
    }




    public static void main(String[] args) {
        OkHttpClient okHttpClient = new OkHttpClient().newBuilder()
                .connectTimeout(50, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .build();
        AreaSnatchMethodHandler handler = new AreaSnatchMethodHandler(okHttpClient);
        handler.snatch();
    }




}

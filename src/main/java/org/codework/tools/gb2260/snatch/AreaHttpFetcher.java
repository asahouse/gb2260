package org.codework.tools.gb2260.snatch;

import lombok.extern.java.Log;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.ResponseBody;
import org.jsoup.HttpStatusException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Recover;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Optional;

/**
 * HTTP 抓取与网络请求门面组件
 * <p>
 * 提供双重请求抓取策略：
 * 1. 国家统计局模式：支持原生 {@link HttpURLConnection} 与 OkHttpClient，配备 Spring Retry 指数退避重试（最大重试 5 次）。
 * 2. 民政部模式：支持离线优先（Offline-First）极速直读与线上 API 请求，并在遇到 CDN 502/连接超时等故障时自动无缝降级切换至本地权威快照。
 *
 * @author benjaminkc
 */
@Log
@Component
public class AreaHttpFetcher {

    private final OkHttpClient okHttpClient;
    private final McaSnapshotService mcaSnapshotService;

    /**
     * 是否开启离线快照优先读取模式
     */
    @Value("${gb2260.mca.offline-first:false}")
    private boolean isOfflineFirst = false;

    /**
     * Spring 自动装配构造函数
     *
     * @param okHttpClient       全局 OkHttpClient 实例
     * @param mcaSnapshotService 民政部本地权威数据快照服务
     */
    @Autowired
    public AreaHttpFetcher(OkHttpClient okHttpClient, McaSnapshotService mcaSnapshotService) {
        this.okHttpClient = okHttpClient;
        this.mcaSnapshotService = mcaSnapshotService;
    }

    /**
     * 兼容单测的重载构造函数
     *
     * @param okHttpClient 全局 OkHttpClient 实例
     */
    public AreaHttpFetcher(OkHttpClient okHttpClient) {
        this(okHttpClient, new McaSnapshotService());
    }

    /**
     * 设置是否开启离线快照优先模式
     *
     * @param offlineFirst true 优先直读快照，false 优先请求线上 API
     */
    public void setOfflineFirst(boolean offlineFirst) {
        this.isOfflineFirst = offlineFirst;
    }

    /**
     * 获取当前是否开启离线快照优先模式
     *
     * @return 当前离线优先布尔状态
     */
    public boolean isOfflineFirst() {
        return isOfflineFirst;
    }

    /**
     * 国家统计局 HTML 静态镜像页面抓取方法（支持 Spring Retry 重试）
     *
     * @param targetURL        目标请求 URL 地址
     * @param isOriginalMethod true 使用 HttpURLConnection 避免 GBK 乱码，false 使用 OkHttpClient
     * @return 页面 HTML 字符串（Optional 封装）
     * @throws Exception 网络或 HTTP 异常
     */
    @Retryable(value = Exception.class, maxAttempts = 5, backoff = @Backoff(delay = 2000, multiplier = 1.5))
    public Optional<String> fetch(String targetURL, boolean isOriginalMethod) throws Exception {
        if (isOriginalMethod) {
            URL url = new URL(targetURL);
            HttpURLConnection uConnection = (HttpURLConnection) url.openConnection();
            try {
                uConnection.setRequestMethod("GET");
                uConnection.setConnectTimeout(15000);
                uConnection.setReadTimeout(30000);
                uConnection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
                uConnection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
                uConnection.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
                uConnection.setInstanceFollowRedirects(true);
                uConnection.connect();

                int responseCode = uConnection.getResponseCode();
                if (responseCode != HttpStatus.OK.value()) {
                    throw new HttpStatusException("HTTP 响应错误: " + responseCode, responseCode, targetURL);
                }

                StringBuilder sb = new StringBuilder();
                try (InputStream is = uConnection.getInputStream();
                     InputStreamReader isr = new InputStreamReader(is, "gbk");
                     BufferedReader br = new BufferedReader(isr)) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line).append("\r\n");
                    }
                }
                return Optional.of(sb.toString());
            } finally {
                uConnection.disconnect();
            }
        } else {
            Request req = new Request.Builder()
                    .url(targetURL)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .build();

            try (okhttp3.Response resp = okHttpClient.newCall(req).execute()) {
                if (resp.code() != HttpStatus.OK.value()) {
                    throw new HttpStatusException("OkHttp 响应错误: " + resp.code(), resp.code(), targetURL);
                }
                ResponseBody body = resp.body();
                if (body != null) {
                    byte[] bytes = body.bytes();
                    return Optional.of(new String(bytes, "gbk"));
                }
                return Optional.empty();
            }
        }
    }

    /**
     * 重试超限兜底恢复方法
     *
     * @param e                最终捕获的异常
     * @param targetURL        请求目标 URL
     * @param isOriginalMethod 是否为原始请求模式
     * @return 返回 Optional.empty()
     */
    @Recover
    public Optional<String> recover(Exception e, String targetURL, boolean isOriginalMethod) {
        log.warning("重试机制完成，结果依然异常: " + targetURL + ", 原因: " + e.getMessage());
        return Optional.empty();
    }

    /**
     * 民政部官方地名信息库 API 请求方法（支持自动重试与快照熔断降级）
     *
     * @param targetURL 带有 code 与 maxLevel 参数的目标民政部 API 地址
     * @return 返回 JSON 结构字符串（Optional 封装）
     */
    public Optional<String> fetchMcaApi(String targetURL) {
        if (isOfflineFirst && mcaSnapshotService != null) {
            return mcaSnapshotService.query(targetURL);
        }

        int maxAttempts = 3;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Request req = new Request.Builder()
                    .url(targetURL)
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    .header("Referer", "https://dmfw.mca.gov.cn/")
                    .header("Accept", "application/json, text/plain, */*")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .build();

            try (okhttp3.Response resp = okHttpClient.newCall(req).execute()) {
                if (resp.isSuccessful()) {
                    ResponseBody body = resp.body();
                    if (body != null) {
                        String bodyStr = body.string().trim();
                        if (bodyStr.startsWith("{") && bodyStr.endsWith("}")) {
                            return Optional.of(bodyStr);
                        } else {
                            log.warning("MCA API 返回非 JSON 结构: " + (bodyStr.length() > 200 ? bodyStr.substring(0, 200) : bodyStr));
                        }
                    }
                } else {
                    log.warning("MCA API 响应 HTTP " + resp.code() + " 状态码: " + targetURL);
                }
            } catch (Exception e) {
                log.warning("MCA API 请求异常 (第 " + attempt + " 次): " + targetURL + ", 原因: " + e.getMessage());
            }

            if (attempt < maxAttempts) {
                try {
                    Thread.sleep(800L * attempt);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        // 线上 API 遇到 502/超时无法响应时，自动无缝平滑降级切换到本地权威快照
        log.warning("民政部线上 API 重试完成依然不可用 (HTTP 502/超时)，正在自动平滑切换至本地权威数据快照: " + targetURL);
        if (mcaSnapshotService != null) {
            return mcaSnapshotService.query(targetURL);
        }
        return Optional.empty();
    }
}

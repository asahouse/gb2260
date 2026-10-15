package org.codework.tools.gb2260.snatch;

import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * 行政区划抓取与代码转换辅助工具类
 * <p>
 * 提供短编码到12位标准码自动补齐、路径代码提取、正则中文/数字校验、以及多线程安全随机休眠区间生成等功能。
 *
 * @author benjaminkc
 */
public class AreaSnatchHelper {

    /**
     * 匹配包含数字字符的预编译正则表达式
     */
    private static final Pattern DIGITAL_PATTERN = Pattern.compile("[0-9]");

    /**
     * 匹配包含中文字符的预编译正则表达式
     */
    private static final Pattern CHINESE_PATTERN = Pattern.compile("[\u4e00-\u9fa5]");

    /**
     * 将短代码（如 2位省、4位市、6位县、9位乡镇）末尾补 '0' 补齐至 12 位标准行政代码
     *
     * @param shortCode 短编码字符串（如 "11"、"4401"）
     * @return 12 位标准编码字符串（如 "110000000000"、"440100000000"）
     */
    public static String getFullCodeByShortCode(String shortCode) {
        if (shortCode == null) {
            return "";
        }
        if (shortCode.length() >= 12) {
            return shortCode;
        }
        StringBuilder sb = new StringBuilder(shortCode);
        while (sb.length() < 12) {
            sb.append('0');
        }
        return sb.toString();
    }

    /**
     * 从 HTML href 路径中解析出短代码
     * 例如："11/1101.html" -> "1101"，"11.html" -> "11"
     *
     * @param path 网页相对链接路径
     * @return 提取出的数字代码字符串
     */
    public static String getShortCodeByPath(String path) {
        if (path == null) {
            return "";
        }
        return path.indexOf("/") != -1
                ? path.substring(path.indexOf("/") + 1, path.indexOf("."))
                : path.substring(0, path.indexOf("."));
    }

    /**
     * 校验字符串中是否包含数字字符
     *
     * @param val 待校验字符串
     * @return true 包含数字，false 不包含
     */
    public static boolean isContainsDigital(String val) {
        if (val == null) {
            return false;
        }
        return DIGITAL_PATTERN.matcher(val).find();
    }

    /**
     * 校验字符串中是否包含中文字符
     *
     * @param val 待校验字符串
     * @return true 包含中文，false 不包含
     */
    public static boolean isContainsChinese(String val) {
        if (val == null) {
            return false;
        }
        return CHINESE_PATTERN.matcher(val).find();
    }

    /**
     * 生成指定范围 [min, max] 内的高性能多线程安全随机休眠毫秒数
     * 用于爬虫请求防频控限制
     *
     * @param max 最大休眠毫秒数
     * @param min 最小休眠毫秒数
     * @return 随机毫秒数值
     */
    public static int ran(int max, int min) {
        if (min >= max) {
            return min;
        }
        return ThreadLocalRandom.current().nextInt(min, max + 1);
    }
}

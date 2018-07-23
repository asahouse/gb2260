package org.codework.tools.gb2260.snatch;

import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.util.regex.Pattern.compile;

/**
 * @author benjaminkc
 * 辅助工具类
 */
public class AreaSnatchHelper {

    public static String getFullCodeByShortCode(String shortCode){
        Integer mend = 12 - shortCode.length();
        Long base = Math.round(Math.pow(10,mend));
        return String.valueOf(base * Long.valueOf(shortCode));
    }

    public static String getShortCodeByPath(String path){
        return path.indexOf("/")!=-1
                ? path.substring(path.indexOf("/")+1, path.indexOf("."))
                : path.substring(0, path.indexOf("."));
    }

    public static boolean isContainsDigital(String val){
        Pattern p = compile("[0-9]");
        Matcher m = p.matcher(val);
        return m.find();
    }

    public static boolean isContainsChinese(String val){
        String regEx = "[\u4e00-\u9fa5]";
        Pattern p = compile(regEx);
        Matcher m = p.matcher(val);
        return m.find();
    }

    public static int ran(int max, int min){
        Random random = new Random();
        int s = random.nextInt(max)%(max-min+1) + min;
        return s;
    }

    public static void main(String[] args) {
        String tmp = "460400100000";
        String grandShortCode = tmp.substring(0,2) + "/";
        String parentShortCode = tmp.substring(2,4) + "/";
        System.out.println(grandShortCode);
        System.out.println(parentShortCode);

    }
}

package org.codework.tools.gb2260.snatch;

import com.alibaba.fastjson.JSONObject;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 行政区划抓取与传输核心领域模型（DTO）
 * <p>
 * 封装在 HTML 页面爬取或 MCA API 响应解析过程中获得的行政区划属性，
 * 并提供基于 12 位标准行政编码后置零位分析的层级推断逻辑。
 *
 * @author benjaminkc
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AreaInfo {

    /**
     * 相对链接路径（用于统计局 HTML 镜像逐级下钻，如 "11/1101.html"）
     */
    private String path;

    /**
     * 12位标准行政区划代码，例如："110101001000"
     */
    private String code;

    /**
     * 短编码（如省级2位、地级4位、县级6位）
     */
    private String shortCode;

    /**
     * 行政区划官方名称，例如："东华门街道"
     */
    private String name;

    /**
     * 父级行政区划代码，省级父级为 "0"
     */
    private String parentCode;

    /**
     * 对应省份代码
     */
    private String provinceCode;

    /**
     * 对应城市代码
     */
    private String cityCode;

    /**
     * 对应区县代码
     */
    private String countyCode;

    /**
     * 对应乡镇/街道代码
     */
    private String townCode;

    /**
     * 对应居委会/村委会代码
     */
    private String villageCode;

    /**
     * 统计用城乡划分代码（如 111 主城区、112 结合区、220 村庄）
     */
    private String categoryCode;

    /**
     * 是否为直辖市（北京、天津、上海、重庆）
     */
    private boolean isDirectlyCity;

    /**
     * 是否为不设区的直筒子市直辖街道（如东莞 4419、中山 4420、儋州 4604、嘉峪关 6202）
     */
    private boolean isDirectlyCounty;

    /**
     * 抓取时间戳或标识
     */
    private String time;

    /**
     * 计算所属省级 12 位标准代码
     *
     * @return 12 位省级编码（前2位有效，后补10个0），非省级返回 "0"
     */
    public String getProvinceCode() {
        if (this.code != null && this.code.length() >= 2 && this.getCurrentLayer().toValue() >= AreaLayer.Province.toValue()) {
            return AreaSnatchHelper.getFullCodeByShortCode(this.code.substring(0, 2));
        } else {
            return "0";
        }
    }

    /**
     * 计算所属地级市 12 位标准代码
     *
     * @return 12 位市级编码（前4位有效，后补8个0），非市级及以上返回 "0"
     */
    public String getCityCode() {
        if (this.code != null && this.code.length() >= 4 && this.getCurrentLayer().toValue() >= AreaLayer.City.toValue()) {
            return AreaSnatchHelper.getFullCodeByShortCode(this.code.substring(0, 4));
        } else {
            return "0";
        }
    }

    /**
     * 计算所属区县级 12 位标准代码
     *
     * @return 12 位县级编码（前6位有效，后补6个0），非县级及以上返回 "0"
     */
    public String getCountyCode() {
        if (this.code != null && this.code.length() >= 6 && this.getCurrentLayer().toValue() >= AreaLayer.County.toValue()) {
            return AreaSnatchHelper.getFullCodeByShortCode(this.code.substring(0, 6));
        } else {
            return "0";
        }
    }

    /**
     * 计算所属乡镇/街道级 12 位标准代码
     *
     * @return 12 位乡镇编码（前9位有效，后补3个0），非乡镇级及以上返回 "0"
     */
    public String getTownCode() {
        if (this.code != null && this.code.length() >= 9 && this.getCurrentLayer().toValue() >= AreaLayer.Town.toValue()) {
            return AreaSnatchHelper.getFullCodeByShortCode(this.code.substring(0, 9));
        } else {
            return "0";
        }
    }

    /**
     * 计算所属村级代码
     *
     * @return 12 位村级代码
     */
    public String getVillageCode() {
        if (this.code != null && this.getCurrentLayer().toValue() >= AreaLayer.Village.toValue()) {
            return this.code;
        } else {
            return "0";
        }
    }

    /**
     * 从编码特征判断是否为市直辖街道（不设区的市跳过县级直达街道，第5-6位为00且第7-9位大于0）
     *
     * @return true 为市直辖街道，false 为常规区县
     */
    public boolean isDirectlyCounty() {
        if (this.code == null || this.code.length() < 9) {
            return false;
        }
        try {
            return Integer.parseInt(this.code.substring(4, 6)) == 0
                    && Integer.parseInt(this.code.substring(6, 9)) > 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * 根据 12 位区划代码末尾连续数字 0 的位数自动判定当前实体所属的法定层级
     * 规则对照：
     * - 末尾 0 位数在 [0, 2]：第 5 级（村委会/居委会）
     * - 末尾 0 位数在 [3, 5]：第 4 级（乡镇/街道办事处）
     * - 末尾 0 位数在 [6, 7]：第 3 级（市辖区/县/县级市）
     * - 末尾 0 位数在 [8, 9]：第 2 级（地级市/盟/州）
     * - 末尾 0 位数 >= 10：第 1 级（省/自治区/直辖市）
     *
     * @return {@link AreaLayer} 层级枚举
     */
    public AreaLayer getCurrentLayer() {
        if (this.code == null || this.code.trim().isEmpty()) {
            return AreaLayer.None;
        }
        Integer zeroBit = this.zeroBit(this.code);
        if (zeroBit >= 0 && zeroBit < 3) {
            return AreaLayer.Village;
        } else if (zeroBit >= 3 && zeroBit < 6) {
            return AreaLayer.Town;
        } else if (zeroBit >= 6 && zeroBit < 8) {
            return AreaLayer.County;
        } else if (zeroBit >= 8 && zeroBit < 10) {
            return AreaLayer.City;
        } else if (zeroBit >= 10) {
            return AreaLayer.Province;
        } else {
            return AreaLayer.None;
        }
    }

    /**
     * 将对象序列化为 JSON 字符串，便于 EventBus 消息传递
     *
     * @return JSON 文本
     */
    public String toJson() {
        return JSONObject.toJSON(this).toString();
    }

    /**
     * 统计字符串末尾连续出现的数字 '0' 的数量
     *
     * @param intVal 目标区划数字字符串
     * @return 末尾 '0' 的个数
     */
    private Integer zeroBit(String intVal) {
        if (intVal == null || intVal.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int i = intVal.length() - 1; i >= 0; i--) {
            if (intVal.charAt(i) == '0') {
                count++;
            } else {
                break;
            }
        }
        return count;
    }
}

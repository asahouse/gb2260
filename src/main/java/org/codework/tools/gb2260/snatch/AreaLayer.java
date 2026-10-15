package org.codework.tools.gb2260.snatch;

import java.util.Optional;

/**
 * 国家标准 GB/T 2260 行政区划层级枚举
 * <p>
 * 对应我国五级行政区划体系：
 * 0: 无 / 未知
 * 1: 省、自治区、直辖市、特别行政区
 * 2: 地级市、地区、自治州、盟
 * 3: 市辖区、县级市、县、自治县、旗
 * 4: 街道办事处、镇、乡、民族乡
 * 5: 社区居民委员会、村民委员会
 *
 * @author benjaminkc
 */
public enum AreaLayer {

    /**
     * 未定义层级
     */
    None(0),

    /**
     * 第1级：省级
     */
    Province(1),

    /**
     * 第2级：地级市
     */
    City(2),

    /**
     * 第3级：区县级
     */
    County(3),

    /**
     * 第4级：乡镇/街道级
     */
    Town(4),

    /**
     * 第5级：居委会/村委会级
     */
    Village(5);

    private final Integer value;

    AreaLayer(Integer value) {
        this.value = value;
    }

    /**
     * 根据数值转换层级枚举
     *
     * @param value 整数值 (0-5)
     * @return 对应的 AreaLayer 枚举，若无匹配返回 null
     */
    public static AreaLayer fromValue(Integer value) {
        if (Optional.ofNullable(value).isPresent()) {
            for (AreaLayer layer : values()) {
                if (layer.value.equals(value)) {
                    return layer;
                }
            }
        }
        return null;
    }

    /**
     * 获取层级代表的整数数值
     *
     * @return 整数数值 (0-5)
     */
    public Integer toValue() {
        return value;
    }
}

package org.codework.tools.gb2260.snatch;

/**
 * 行政区划抓取与缓存全局常量类
 * <p>
 * 定义 Guava EventBus 各级分发频道名称以及 Redis 缓存键前缀
 *
 * @author benjaminkc
 */
public class AreaCommon {

    /**
     * 省级数据事件分发频道
     */
    public static final String CHANNEL_PROVINCE = "CHANNEL_PROVINCE";

    /**
     * 地级市数据事件分发频道
     */
    public static final String CHANNEL_CITY = "CHANNEL_CITY";

    /**
     * 区县级数据事件分发频道
     */
    public static final String CHANNEL_COUNTY = "CHANNEL_COUNTY";

    /**
     * 乡镇/街道级数据事件分发频道
     */
    public static final String CHANNEL_TOWN = "CHANNEL_TOWN";

    /**
     * 居委会/村委会级数据事件分发频道
     */
    public static final String CHANNEL_VILLAGE = "CHANNEL_VILLAGE";

    /**
     * Redis 根节点数据 Hash 表前缀，例如：gb2260:area:province
     */
    public static final String CACHE_SYS_ROOT_PREFIX = "gb2260:area:";

    /**
     * Redis 下级关联索引 Set 表前缀，例如：gb2260:area:index:city:110000000000
     */
    public static final String CACHE_SYS_INDEX_PREFIX = CACHE_SYS_ROOT_PREFIX + "index:";
}

package org.codework.tools.gb2260;

import org.codework.tools.gb2260.snatch.AreaInfo;
import org.codework.tools.gb2260.snatch.AreaLayer;
import org.junit.Assert;
import org.junit.Test;

public class AreaInfoTest {

    @Test
    public void testStandardFiveLevelCodes() {
        // 广东省广州市天河区五山街道岳洲社区居委会: 440106001001
        AreaInfo villageInfo = AreaInfo.builder()
                .code("440106001001")
                .name("岳洲社区居委会")
                .build();

        Assert.assertEquals(AreaLayer.Village, villageInfo.getCurrentLayer());
        Assert.assertEquals("440000000000", villageInfo.getProvinceCode());
        Assert.assertEquals("440100000000", villageInfo.getCityCode());
        Assert.assertEquals("440106000000", villageInfo.getCountyCode());
        // 关键断言: 乡级代码必须截取前9位并补齐3个0，与 town 表的实际主键对应！
        Assert.assertEquals("440106001000", villageInfo.getTownCode());
        Assert.assertEquals("440106001001", villageInfo.getVillageCode());
    }

    @Test
    public void testTownLevelCode() {
        // 五山街道: 440106001000 (末尾3个0)
        AreaInfo townInfo = AreaInfo.builder()
                .code("440106001000")
                .name("五山街道")
                .build();

        Assert.assertEquals(AreaLayer.Town, townInfo.getCurrentLayer());
        Assert.assertEquals("440000000000", townInfo.getProvinceCode());
        Assert.assertEquals("440100000000", townInfo.getCityCode());
        Assert.assertEquals("440106000000", townInfo.getCountyCode());
        Assert.assertEquals("440106001000", townInfo.getTownCode());
    }

    @Test
    public void testDirectlyCountyDongguan() {
        // 广东省东莞市中堂镇: 441900003000 (市直管镇)
        AreaInfo dgTown = AreaInfo.builder()
                .code("441900003000")
                .name("中堂镇")
                .build();

        Assert.assertTrue("东莞直辖镇判定应为true", dgTown.isDirectlyCounty());
        Assert.assertEquals(AreaLayer.Town, dgTown.getCurrentLayer());
        Assert.assertEquals("440000000000", dgTown.getProvinceCode());
        Assert.assertEquals("441900000000", dgTown.getCityCode());
        Assert.assertEquals("441900003000", dgTown.getTownCode());
    }

    @Test
    public void testZeroBitBoundaryNoInfiniteLoop() {
        // 边界条件: 全0或异常情况严禁死循环
        AreaInfo zeroInfo = AreaInfo.builder().code("0").build();
        Assert.assertNotNull(zeroInfo.getCurrentLayer());

        AreaInfo allZeros = AreaInfo.builder().code("000000000000").build();
        Assert.assertNotNull(allZeros.getCurrentLayer());

        AreaInfo nullInfo = AreaInfo.builder().code(null).build();
        Assert.assertEquals(AreaLayer.None, nullInfo.getCurrentLayer());
    }
}

package org.codework.tools.gb2260;

import org.codework.tools.gb2260.snatch.AreaSnatchHelper;
import org.junit.Assert;
import org.junit.Test;

public class AreaSnatchHelperTest {

    @Test
    public void testGetFullCodeByShortCode() {
        Assert.assertEquals("110000000000", AreaSnatchHelper.getFullCodeByShortCode("11"));
        Assert.assertEquals("440100000000", AreaSnatchHelper.getFullCodeByShortCode("4401"));
        Assert.assertEquals("440106000000", AreaSnatchHelper.getFullCodeByShortCode("440106"));
        Assert.assertEquals("440106001000", AreaSnatchHelper.getFullCodeByShortCode("440106001"));
        Assert.assertEquals("440106001001", AreaSnatchHelper.getFullCodeByShortCode("440106001001"));
        Assert.assertEquals("010000000000", AreaSnatchHelper.getFullCodeByShortCode("01")); // 保留前导零
        Assert.assertEquals("", AreaSnatchHelper.getFullCodeByShortCode(null));
    }

    @Test
    public void testRegexPatterns() {
        Assert.assertTrue(AreaSnatchHelper.isContainsDigital("123456789012"));
        Assert.assertFalse(AreaSnatchHelper.isContainsDigital("北京市"));
        Assert.assertTrue(AreaSnatchHelper.isContainsChinese("北京市"));
        Assert.assertFalse(AreaSnatchHelper.isContainsChinese("123456"));
    }

    @Test
    public void testRan() {
        for (int i = 0; i < 100; i++) {
            int val = AreaSnatchHelper.ran(3000, 1200);
            Assert.assertTrue(val >= 1200 && val <= 3000);
        }
    }
}

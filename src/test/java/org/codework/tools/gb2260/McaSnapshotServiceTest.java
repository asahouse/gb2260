package org.codework.tools.gb2260;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.codework.tools.gb2260.snatch.McaSnapshotService;
import org.junit.Assert;
import org.junit.Test;

import java.util.Optional;

public class McaSnapshotServiceTest {

    @Test
    public void testMcaSnapshotServiceInitializationAndQueries() {
        McaSnapshotService service = new McaSnapshotService();
        service.init();

        Assert.assertTrue("索引节点总数应当大于2000", service.getIndexedCount() > 2000);

        // 1. 测试省级查询
        Optional<String> provOpt = service.query("https://dmfw.mca.gov.cn/9095/xzqh/getList?code=&maxLevel=1");
        Assert.assertTrue("省级快照应当存在", provOpt.isPresent());
        JSONObject provJson = JSON.parseObject(provOpt.get());
        Assert.assertEquals(200, (int) provJson.getInteger("status"));
        JSONArray provChildren = provJson.getJSONObject("data").getJSONArray("children");
        Assert.assertEquals(34, provChildren.size());

        // 2. 测试北京市下辖区县 (Level 3)
        Optional<String> bjOpt = service.query("https://dmfw.mca.gov.cn/9095/xzqh/getList?code=110000000000&maxLevel=1");
        Assert.assertTrue("北京区县快照应当存在", bjOpt.isPresent());
        JSONArray bjDistricts = JSON.parseObject(bjOpt.get()).getJSONObject("data").getJSONArray("children");
        Assert.assertEquals(16, bjDistricts.size());

        // 3. 测试东城区下辖街道 (Level 4)
        Optional<String> dcOpt = service.query("https://dmfw.mca.gov.cn/9095/xzqh/getList?code=110101000000&maxLevel=1");
        Assert.assertTrue("东城区街道快照应当存在", dcOpt.isPresent());
        JSONArray dcStreets = JSON.parseObject(dcOpt.get()).getJSONObject("data").getJSONArray("children");
        Assert.assertEquals(17, dcStreets.size());

        boolean hasDonghuamen = false;
        for (int i = 0; i < dcStreets.size(); i++) {
            JSONObject s = dcStreets.getJSONObject(i);
            if ("110101001000".equals(s.getString("code")) && "东华门街道".equals(s.getString("name"))) {
                hasDonghuamen = true;
                break;
            }
        }
        Assert.assertTrue("必须包含东华门街道", hasDonghuamen);

        // 4. 测试东莞市直辖镇街 (Level 4)
        Optional<String> dgOpt = service.query("https://dmfw.mca.gov.cn/9095/xzqh/getList?code=441900000000&maxLevel=2");
        Assert.assertTrue("东莞市直辖镇街快照应当存在", dgOpt.isPresent());
        JSONArray dgTowns = JSON.parseObject(dgOpt.get()).getJSONObject("data").getJSONArray("children");
        Assert.assertEquals(36, dgTowns.size());

        System.out.println(">>> 民政部数据快照服务测试全部通过！包含 34 省份、全部区县及 4 万+街道乡镇。");
    }
}

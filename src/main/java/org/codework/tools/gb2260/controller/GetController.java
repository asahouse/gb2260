package org.codework.tools.gb2260.controller;

import com.google.common.collect.Lists;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import lombok.extern.java.Log;
import org.codework.tools.gb2260.snatch.AreaCommon;
import org.codework.tools.gb2260.snatch.AreaLayer;
import org.codework.tools.gb2260.snatch.AreaSnatchHelper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 行政区划前端级联查询控制器
 * <p>
 * 面向 Web 前端级联选择器（如省/市/区/街道下拉框），直接从 Redis 缓存中毫秒级读取数据。
 * 支持短码与 12 位标准编码自动对齐，并按行政区划代码有序输出。
 *
 * @author benjaminkc
 */
@Api(tags = "行政区划缓存查询接口")
@Log
@RestController
@RequestMapping("/api/get")
public class GetController {

    private final JedisPool jedisPool;

    /**
     * 构造函数注入 JedisPool 依赖
     *
     * @param jedisPool Redis 连接池
     */
    public GetController(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    /**
     * 获取全国所有省级行政区划列表
     *
     * @return 包含全国 34 个省、自治区、直辖市及特别行政区的代码与名称键值对
     */
    @ApiOperation(value = "获取全国所有省份列表", notes = "从 Redis 哈希表中返回全量省级行政区（代码 -> 名称）")
    @GetMapping("province")
    public Response province() {
        try (Jedis jedis = jedisPool.getResource()) {
            return Response.ok("body", jedis.hgetAll(AreaCommon.CACHE_SYS_ROOT_PREFIX + AreaLayer.Province.name().toLowerCase()));
        }
    }

    /**
     * 根据省级代码获取其下辖的所有地级市/盟/州列表
     *
     * @param province 省级行政区代码（支持2位前缀如 11 或 12位全码如 110000000000）
     * @return 下辖城市列表
     */
    @ApiOperation(value = "获取指定省份下属城市列表", notes = "支持 2 位短码或 12 位长码查询下辖地级市")
    @GetMapping("{province}/city")
    public Response provinceUnderCity(
            @ApiParam(value = "省级行政区代码，例如: 44 或 440000000000", required = true)
            @PathVariable String province) {
        List<RespInfo> result = this.archive(AreaLayer.City, province);
        return Response.ok("body", result);
    }

    /**
     * 根据城市代码获取其下辖的所有区/县/县级市列表
     *
     * @param city 城市行政区代码（支持4位前缀如 4401 或 12位全码如 440100000000）
     * @return 下辖区县列表
     */
    @ApiOperation(value = "获取指定城市下属区县列表", notes = "支持 4 位短码或 12 位长码查询下辖区县")
    @GetMapping("{city}/county")
    public Response cityUnderCounty(
            @ApiParam(value = "地级市代码，例如: 4401 或 440100000000", required = true)
            @PathVariable String city) {
        List<RespInfo> result = this.archive(AreaLayer.County, city);
        return Response.ok("body", result);
    }

    /**
     * 根据区县代码获取其下辖的所有乡镇/街道办事处列表
     *
     * @param county 区县行政区代码（支持6位前缀如 440106 或 12位全码如 440106000000）
     * @return 下辖乡镇/街道列表
     */
    @ApiOperation(value = "获取指定区县下属街道/乡镇列表", notes = "支持 6 位短码或 12 位长码查询下辖街道乡镇")
    @GetMapping("{county}/town")
    public Response countyUndertown(
            @ApiParam(value = "区县代码，例如: 440106 或 440106000000", required = true)
            @PathVariable String county) {
        List<RespInfo> result = this.archive(AreaLayer.Town, county);
        return Response.ok("body", result);
    }

    /**
     * 根据乡镇/街道代码获取其下辖的所有居委会/村委会列表
     *
     * @param town 乡镇/街道行政区代码（支持9位前缀如 440106001 或 12位全码如 440106001000）
     * @return 下辖村委会/居委会列表（民政部源不含第5级，统计局源支持）
     */
    @ApiOperation(value = "获取指定街道下属居委会/村委会列表", notes = "支持 9 位短码或 12 位长码查询下辖村委会/居委会（仅限统计局数据源模式）")
    @GetMapping("{town}/village")
    public Response townUnderVillage(
            @ApiParam(value = "乡镇/街道代码，例如: 440106001 或 440106001000", required = true)
            @PathVariable String town) {
        List<RespInfo> result = this.archive(AreaLayer.Village, town);
        return Response.ok("body", result);
    }

    /**
     * 从 Redis 索引 Set 与数据 Hash 表中读取层级下辖节点列表
     *
     * @param layer 目标查询的下级层级枚举
     * @param code  父级行政区划代码
     * @return 包含 code 和 name 的精简列表，按行政代码升序排列
     */
    private List<RespInfo> archive(AreaLayer layer, String code) {
        if (code == null || code.trim().isEmpty()) {
            return Lists.newArrayList();
        }
        String fullCode = code.length() < 12 ? AreaSnatchHelper.getFullCodeByShortCode(code) : code;

        try (Jedis jedis = jedisPool.getResource()) {
            String indexKey = AreaCommon.CACHE_SYS_INDEX_PREFIX + layer.name().toLowerCase() + ":" + fullCode;
            Set<String> members = jedis.smembers(indexKey);
            if ((members == null || members.isEmpty()) && !fullCode.equals(code)) {
                // 兼容短码索引查询
                members = jedis.smembers(AreaCommon.CACHE_SYS_INDEX_PREFIX + layer.name().toLowerCase() + ":" + code);
            }

            if (members == null || members.isEmpty()) {
                return Lists.newArrayList();
            }

            List<String> indexs = Lists.newArrayList(members);
            List<String> names = jedis.hmget(
                    AreaCommon.CACHE_SYS_ROOT_PREFIX + layer.name().toLowerCase(),
                    indexs.toArray(new String[0]));

            return IntStream.rangeClosed(0, names.size() - 1).boxed()
                    .map(i -> RespInfo.builder()
                            .name(names.get(i))
                            .code(indexs.get(i))
                            .build())
                    .sorted(Comparator.comparing(RespInfo::getCode))
                    .collect(Collectors.toList());
        } catch (Exception ex) {
            log.warning("Redis 级联查询异常 [" + layer + ":" + code + "]: " + ex.getMessage());
            return Lists.newArrayList();
        }
    }
}

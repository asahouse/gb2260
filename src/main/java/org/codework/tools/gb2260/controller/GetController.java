package org.codework.tools.gb2260.controller;

import com.google.common.collect.Lists;
import lombok.extern.java.Log;
import org.codework.tools.gb2260.snatch.AreaCommon;
import org.codework.tools.gb2260.snatch.AreaLayer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.exceptions.JedisDataException;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * 此控制器查询 Redis缓存, 面向Web界面通过级联查询
 *
 * DB内的数据是带关系, 可面向程序内部通过关系查询
 */

@Log
@RestController
@RequestMapping("/api/get")
public class GetController {

    JedisPool jedisPool;

    public GetController(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    @GetMapping("province")
    public Response province(){
        try(Jedis jedis = jedisPool.getResource()){
            return Response.ok("body", jedis.hgetAll(AreaCommon.CACHE_SYS_ROOT_PREFIX + AreaLayer.Province.name().toLowerCase()));
        }
    }

    @GetMapping("{province}/city")
    public Response provinceUnderCity(@PathVariable String province){
        List<RespInfo> result = this.archive(AreaLayer.City, province);
        return Response.ok("body", result);
    }

    @GetMapping("{city}/county")
    public Response cityUnderCounty(@PathVariable String city){
        List<RespInfo> result = this.archive(AreaLayer.County, city);
        return Response.ok("body", result);
    }

    @GetMapping("{county}/town")
    public Response countyUndertown(@PathVariable String county){
        List<RespInfo> result = this.archive(AreaLayer.Town, county);
        return Response.ok("body", result);
    }

    @GetMapping("{town}/village")
    public Response townUnderVillage(@PathVariable String town){
        List<RespInfo> result = this.archive(AreaLayer.Village, town);
        return Response.ok("body", result);
    }

    private List<RespInfo> archive(AreaLayer layer, String code){
        try(Jedis jedis = jedisPool.getResource()) {
            ArrayList<? extends String> indexs = Lists.newArrayList(
                    (Iterable<? extends String>) jedis.smembers(
                            AreaCommon.CACHE_SYS_INDEX_PREFIX + layer.name().toLowerCase() + ":" + code));
            List<String> names = jedis.hmget(
                    AreaCommon.CACHE_SYS_ROOT_PREFIX + layer.name().toLowerCase(),
                    indexs.toArray(new String[]{}));
            List<RespInfo> result = IntStream.rangeClosed(0, names.size() - 1).boxed()
                    .map(i -> RespInfo.builder()
                            .name(names.get(i))
                            .code(indexs.get(i))
                            .build())
                    .collect(Collectors.toList());
            return result;
        }catch (JedisDataException ex){
            log.warning(ex.getMessage());
            return Lists.newArrayList();
        }
    }

}

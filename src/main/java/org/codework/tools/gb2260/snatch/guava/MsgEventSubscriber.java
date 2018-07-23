package org.codework.tools.gb2260.snatch.guava;

import com.alibaba.fastjson.JSONObject;
import com.google.common.eventbus.Subscribe;
import lombok.SneakyThrows;
import lombok.extern.java.Log;
import org.codework.tools.gb2260.entity.*;
import org.codework.tools.gb2260.mapper.*;
import org.codework.tools.gb2260.snatch.AreaCommon;
import org.codework.tools.gb2260.snatch.AreaInfo;
import org.codework.tools.gb2260.snatch.AreaLayer;
import org.codework.tools.gb2260.snatch.AreaSnatchHandler;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author benjaminkc
 */
@Log
@Component
public class MsgEventSubscriber {

    ProvinceMapper provinceMapper;
    CityMapper cityMapper;
    CountyMapper countyMapper;
    TownMapper townMapper;
    VillageMapper villageMapper;

    JedisPool jedisPool;

    AreaSnatchHandler handler;
    ConcurrentHashMap<String, RunThreadPoolExecutor> executors;
    public MsgEventSubscriber(AreaSnatchHandler handler,
                              ConcurrentHashMap<String, RunThreadPoolExecutor> executors,
                              JedisPool jedisPool,
                              ProvinceMapper provinceMapper,
                              CityMapper cityMapper,
                              CountyMapper countyMapper,
                              TownMapper townMapper,
                              VillageMapper villageMapper
                              ) {
        this.handler = handler;
        this.executors = executors;
        this.jedisPool = jedisPool;

        this.provinceMapper = provinceMapper;
        this.cityMapper = cityMapper;
        this.countyMapper = countyMapper;
        this.townMapper = townMapper;
        this.villageMapper = villageMapper;

    }

    @SneakyThrows
    @Subscribe
    public void listener(Msg event) {

        //获取队列中executor,若空则等待
        RunThreadPoolExecutor executor = executors.get(event.getExecutorFlag());

        String channel = event.getChannel();
        String message = event.getMessage();

        AreaInfo info = JSONObject.parseObject(message, AreaInfo.class);

        //TODO DAO操作, 判断layer, 写入各级信息
        CompletableFuture.runAsync(()->{
            try{
                if (info.getCurrentLayer().equals(AreaLayer.Province))
                    provinceMapper.insert(Province.builder()
                            .code(info.getCode()).name(info.getName())
                            .build());
                else if(info.getCurrentLayer().equals(AreaLayer.City))
                    cityMapper.insert(City.builder()
                            .code(info.getCode()).name(info.getName())
                            .provinceCode(info.getProvinceCode())
                            .build());
                else if(info.getCurrentLayer().equals(AreaLayer.County))
                    countyMapper.insert(County.builder()
                            .code(info.getCode()).name(info.getName())
                            .provinceCode(info.getProvinceCode())
                            .cityCode(info.getCityCode())
                            .build());
                else if(info.getCurrentLayer().equals(AreaLayer.Town))
                    townMapper.insert(Town.builder()
                            .code(info.getCode()).name(info.getName())
                            .provinceCode(info.getProvinceCode())
                            .cityCode(info.getCityCode())
                            .countyCode(info.getCountyCode())
                            .build());
                else if(info.getCurrentLayer().equals(AreaLayer.Village))
                    villageMapper.insert(Village.builder()
                            .code(info.getCode()).name(info.getName())
                            .provinceCode(info.getProvinceCode())
                            .cityCode(info.getCityCode())
                            .countyCode(info.getCountyCode())
                            .townCode(info.getTownCode())
                            .categoryCode(info.getCategoryCode())
                            .build());
                else log.warning("Can not Insert Complete! -> "+ info.getCode());
            }catch (Exception ex){
                log.warning(ex.getMessage());
            }
        }, executor).thenRunAsync(() ->{
            try(Jedis jedis = jedisPool.getResource()){
                jedis.hset(AreaCommon.CACHE_SYS_ROOT_PREFIX + info.getCurrentLayer().name().toLowerCase(),
                        info.getCode(), info.getName());
                jedis.sadd(AreaCommon.CACHE_SYS_INDEX_PREFIX + info.getCurrentLayer().name().toLowerCase()
                        + ":" + info.getParentCode(), info.getCode());
            }
        });


        if (AreaCommon.CHANNEL_PROVINCE.equalsIgnoreCase(channel)) {
            //省
            CompletableFuture.runAsync(() ->
                    handler.snatchProvinceUnderCityCollection(event.getExecutorFlag(), info), executor);
        }else if (AreaCommon.CHANNEL_CITY.equalsIgnoreCase(channel)) {
            //市
            CompletableFuture.runAsync(() ->
                    handler.snatchCityUnderCountyCollection(event.getExecutorFlag(), info), executor);
        }else if (AreaCommon.CHANNEL_COUNTY.equalsIgnoreCase(channel)){
            //区/乡镇
            CompletableFuture.runAsync(() ->
                    handler.snatchCountyUnderTownCollection(event.getExecutorFlag(), info), executor);
        }else if (AreaCommon.CHANNEL_TOWN.equalsIgnoreCase(channel)){
            //街道
            CompletableFuture.runAsync(() ->
                    handler.snatchTownUnderVillageCollection(event.getExecutorFlag(), info), executor);
        }else if (AreaCommon.CHANNEL_VILLAGE.equalsIgnoreCase(channel)){
            //居委
            //最后节点不发送, 只接收
        }


    }
}

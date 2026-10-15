package org.codework.tools.gb2260.snatch.guava;

import com.alibaba.fastjson.JSONObject;
import com.google.common.eventbus.Subscribe;
import lombok.SneakyThrows;
import lombok.extern.java.Log;
import org.codework.tools.gb2260.entity.City;
import org.codework.tools.gb2260.entity.County;
import org.codework.tools.gb2260.entity.Province;
import org.codework.tools.gb2260.entity.Town;
import org.codework.tools.gb2260.entity.Village;
import org.codework.tools.gb2260.mapper.CityMapper;
import org.codework.tools.gb2260.mapper.CountyMapper;
import org.codework.tools.gb2260.mapper.ProvinceMapper;
import org.codework.tools.gb2260.mapper.TownMapper;
import org.codework.tools.gb2260.mapper.VillageMapper;
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
 * Guava EventBus 事件消费者与持久化订阅者
 * <p>
 * 核心职责：
 * 1. 订阅各级行政区划消息事件（{@link Msg}），从当前任务执行器中调度线程异步执行。
 * 2. 双重持久化：
 *    - 关系型持久化：根据行政区划层级写入 MySQL 对应表（province, city, county, town, village）。
 *    - 高性能缓存：写入 Redis Hash 结构（全量信息）与 Set 结构（父子层级关系索引）。
 * 3. 驱动下一级递归下钻：
 *    - 接收省级后触发城市抓取；接收市级后触发区县抓取；接收区县后触发乡镇街道抓取。
 *
 * @author benjaminkc
 */
@Log
@Component
public class MsgEventSubscriber {

    private final ProvinceMapper provinceMapper;
    private final CityMapper cityMapper;
    private final CountyMapper countyMapper;
    private final TownMapper townMapper;
    private final VillageMapper villageMapper;

    private final JedisPool jedisPool;
    private final AreaSnatchHandler handler;
    private final ConcurrentHashMap<String, RunThreadPoolExecutor> executors;

    /**
     * 构造函数注入所有 Mapper、JedisPool、Handler 及线程池执行器映射
     *
     * @param handler        抓取主处理器
     * @param executors      执行器集中映射表
     * @param jedisPool      Redis 连接池
     * @param provinceMapper 省级 Mapper
     * @param cityMapper     市级 Mapper
     * @param countyMapper   区县级 Mapper
     * @param townMapper     乡镇/街道级 Mapper
     * @param villageMapper  村/居委会级 Mapper
     */
    public MsgEventSubscriber(AreaSnatchHandler handler,
                              ConcurrentHashMap<String, RunThreadPoolExecutor> executors,
                              JedisPool jedisPool,
                              ProvinceMapper provinceMapper,
                              CityMapper cityMapper,
                              CountyMapper countyMapper,
                              TownMapper townMapper,
                              VillageMapper villageMapper) {
        this.handler = handler;
        this.executors = executors;
        this.jedisPool = jedisPool;

        this.provinceMapper = provinceMapper;
        this.cityMapper = cityMapper;
        this.countyMapper = countyMapper;
        this.townMapper = townMapper;
        this.villageMapper = villageMapper;
    }

    /**
     * 事件监听订阅入口方法（被 EventSubscribeBeanPostProcessor 自动注册）
     *
     * @param event 接收到的行政区划事件消息
     */
    @SneakyThrows
    @Subscribe
    public void listener(Msg event) {

        // 获取对应任务的 executor 实例，若无效或已停止则安全退出
        RunThreadPoolExecutor executor = executors.get(event.getExecutorFlag());
        if (executor == null || executor.isShutdown()) {
            log.warning("当前任务执行器不可用: " + event.getExecutorFlag());
            return;
        }

        String channel = event.getChannel();
        String message = event.getMessage();

        AreaInfo info = JSONObject.parseObject(message, AreaInfo.class);

        // 1. 异步写入 MySQL 数据库与 Redis 缓存，由统一的 executor 调度追踪计数
        CompletableFuture.runAsync(() -> {
            try {
                if (info.getCurrentLayer().equals(AreaLayer.Province)) {
                    Province entity = Province.builder()
                            .code(info.getCode()).name(info.getName())
                            .build();
                    if (provinceMapper.selectById(info.getCode()) == null) {
                        provinceMapper.insert(entity);
                    } else {
                        provinceMapper.updateById(entity);
                    }
                } else if (info.getCurrentLayer().equals(AreaLayer.City)) {
                    City entity = City.builder()
                            .code(info.getCode()).name(info.getName())
                            .provinceCode(info.getProvinceCode())
                            .build();
                    if (cityMapper.selectById(info.getCode()) == null) {
                        cityMapper.insert(entity);
                    } else {
                        cityMapper.updateById(entity);
                    }
                } else if (info.getCurrentLayer().equals(AreaLayer.County)) {
                    County entity = County.builder()
                            .code(info.getCode()).name(info.getName())
                            .provinceCode(info.getProvinceCode())
                            .cityCode(info.getCityCode())
                            .build();
                    if (countyMapper.selectById(info.getCode()) == null) {
                        countyMapper.insert(entity);
                    } else {
                        countyMapper.updateById(entity);
                    }
                } else if (info.getCurrentLayer().equals(AreaLayer.Town)) {
                    Town entity = Town.builder()
                            .code(info.getCode()).name(info.getName())
                            .provinceCode(info.getProvinceCode())
                            .cityCode(info.getCityCode())
                            .countyCode(info.getCountyCode())
                            .build();
                    if (townMapper.selectById(info.getCode()) == null) {
                        townMapper.insert(entity);
                    } else {
                        townMapper.updateById(entity);
                    }
                } else if (info.getCurrentLayer().equals(AreaLayer.Village)) {
                    Village entity = Village.builder()
                            .code(info.getCode()).name(info.getName())
                            .provinceCode(info.getProvinceCode())
                            .cityCode(info.getCityCode())
                            .countyCode(info.getCountyCode())
                            .townCode(info.getTownCode())
                            .categoryCode(info.getCategoryCode())
                            .build();
                    if (villageMapper.selectById(info.getCode()) == null) {
                        villageMapper.insert(entity);
                    } else {
                        villageMapper.updateById(entity);
                    }
                } else {
                    log.warning("无法识别层级，跳过插入: " + info.getCode());
                }
            } catch (Exception ex) {
                log.warning("MySQL 入库失败 [" + info.getCode() + "]: " + ex.getMessage());
            }

            try (Jedis jedis = jedisPool.getResource()) {
                // 存储实体数据 Hash：Key: gb2260:area:{layer}, Field: code, Value: name
                jedis.hset(AreaCommon.CACHE_SYS_ROOT_PREFIX + info.getCurrentLayer().name().toLowerCase(),
                        info.getCode(), info.getName());
                // 存储父子层级关系索引 Set：Key: gb2260:area:index:{layer}:{parentCode}, Member: code
                jedis.sadd(AreaCommon.CACHE_SYS_INDEX_PREFIX + info.getCurrentLayer().name().toLowerCase()
                        + ":" + info.getParentCode(), info.getCode());
            } catch (Exception ex) {
                log.warning("Redis 缓存失败 [" + info.getCode() + "]: " + ex.getMessage());
            }
        }, executor);

        // 2. 根据事件所属层级，异步调度下一级采集任务
        if (AreaCommon.CHANNEL_PROVINCE.equalsIgnoreCase(channel)) {
            // 省级完成 -> 下钻抓取市级
            CompletableFuture.runAsync(() ->
                    handler.snatchProvinceUnderCityCollection(event.getExecutorFlag(), info), executor);
        } else if (AreaCommon.CHANNEL_CITY.equalsIgnoreCase(channel)) {
            // 市级完成 -> 下钻抓取区县（或直筒子市直辖街道）
            CompletableFuture.runAsync(() ->
                    handler.snatchCityUnderCountyCollection(event.getExecutorFlag(), info), executor);
        } else if (AreaCommon.CHANNEL_COUNTY.equalsIgnoreCase(channel)) {
            // 区县完成 -> 下钻抓取街道/乡镇
            CompletableFuture.runAsync(() ->
                    handler.snatchCountyUnderTownCollection(event.getExecutorFlag(), info), executor);
        } else if (AreaCommon.CHANNEL_TOWN.equalsIgnoreCase(channel)) {
            // 街道完成 -> 下钻抓取村级居委会（统计局模式）
            CompletableFuture.runAsync(() ->
                    handler.snatchTownUnderVillageCollection(event.getExecutorFlag(), info), executor);
        } else if (AreaCommon.CHANNEL_VILLAGE.equalsIgnoreCase(channel)) {
            // 居委为终点叶子节点，仅做入库不继续扩散
        }
    }
}

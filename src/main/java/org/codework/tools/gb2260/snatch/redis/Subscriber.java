package org.codework.tools.gb2260.snatch.redis;

import lombok.extern.java.Log;
import org.codework.tools.gb2260.snatch.AreaSnatchHandler;
import redis.clients.jedis.JedisPubSub;

@Deprecated
@Log
public class Subscriber extends JedisPubSub {

    AreaSnatchHandler handler;
    public Subscriber(AreaSnatchHandler handler) {
        this.handler = handler;
    }

    @Override
    public void onMessage(String channel, String message) {

//        AreaInfo info = JSONObject.parseObject(message, AreaInfo.class);
//
//        if (AreaSnatchHandler.CHANNEL_PROVINCE.equalsIgnoreCase(channel)) {
//            CompletableFuture.runAsync(() -> handler.snatchProvinceUnderCityCollection(info));
//        }else if (AreaSnatchHandler.CHANNEL_CITY.equalsIgnoreCase(channel)) {
//            CompletableFuture.runAsync(() -> handler.snatchCityUnderCountyCollection(info));
//        }else if (AreaSnatchHandler.CHANNEL_COUNTY.equalsIgnoreCase(channel)){
//            CompletableFuture.runAsync(() -> handler.snatchCountyUnderTownCollection(info));
//        }else if (AreaSnatchHandler.CHANNEL_TOWN.equalsIgnoreCase(channel)){
//            CompletableFuture.runAsync(() -> handler.snatchTownUnderVillageCollection(info));
//        }else if (AreaSnatchHandler.CHANNEL_VILLAGE.equalsIgnoreCase(channel)){
//            //TODO 最后节点不发送, 只接收
//        }
    }
}

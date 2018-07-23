package org.codework.tools.gb2260.config.redis;

import lombok.extern.java.Log;
import org.codework.tools.gb2260.snatch.AreaCommon;
import org.codework.tools.gb2260.snatch.AreaSnatchHandler;
import org.codework.tools.gb2260.snatch.redis.Subscriber;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

@Deprecated
@Log
public class RegisterListener implements ApplicationListener<ApplicationReadyEvent> {

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        ConfigurableApplicationContext applicationContext = event.getApplicationContext();

        AreaSnatchHandler handler = applicationContext.getBean(AreaSnatchHandler.class);
        JedisPool jedisPool = applicationContext.getBean(JedisPool.class);

        try(Jedis jedis = jedisPool.getResource()){
            Subscriber subscriber = new Subscriber(handler);
            jedis.subscribe(subscriber, AreaCommon.CHANNEL_PROVINCE);
            jedis.subscribe(subscriber, AreaCommon.CHANNEL_CITY);
            jedis.subscribe(subscriber, AreaCommon.CHANNEL_COUNTY);
            jedis.subscribe(subscriber, AreaCommon.CHANNEL_TOWN);
            jedis.subscribe(subscriber, AreaCommon.CHANNEL_VILLAGE);
        }

        log.info("register ok!");
    }
}

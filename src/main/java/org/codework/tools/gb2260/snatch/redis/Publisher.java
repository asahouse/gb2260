package org.codework.tools.gb2260.snatch.redis;

import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

/**
 * @author benjaminkc
 */
@Deprecated
@Component
public class Publisher {

    JedisPool jedisPool;
    public Publisher(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    public void post(String channel, String value){
        try(Jedis jedis = jedisPool.getResource()){
            jedis.publish(channel, value);
        }
    }
}

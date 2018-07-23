package org.codework.tools.gb2260.config.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Created by benjaminkc on 17/7/18.
 */
@Slf4j
@Configuration
public class JedisConfig {

    @Value("${spring.redis.host}")
    private String host;

    @Value("${spring.redis.port}")
    private int port;

    @Value("${spring.redis.password}")
    private String password;

    @Value("${spring.redis.database}")
    private int database;

    @Bean
    public JedisPool redisPoolFactory() {
        log.info("JedisPool注入成功！！");
        log.info("redis地址：" + host + ":" + port);
        JedisPoolConfig jedisPoolConfig = new JedisPoolConfig();

        JedisPool jedisPool;
//        if (StringUtils.isEmpty(password)) {
//            jedisPool = new JedisPool(jedisPoolConfig, host, port, 100000);
//        }else {
//            jedisPool = new JedisPool(jedisPoolConfig, host, port, 100000, password, database);
//        }
        jedisPool = new JedisPool(jedisPoolConfig, host, port, 100000, password, database);
        return jedisPool;
    }

}

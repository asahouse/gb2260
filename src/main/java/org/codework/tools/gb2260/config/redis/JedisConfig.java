package org.codework.tools.gb2260.config.redis;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Redis Jedis 连接池装配配置类
 * <p>
 * 从 application.yml 读取主机、端口、密码与数据库编号配置，
 * 构建高并发参数调优的 {@link JedisPool}，并支持免密自适应回退。
 *
 * @author benjaminkc
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

    /**
     * 构建并注入 JedisPool 实例
     * 配置 MaxTotal=200, MaxIdle=50, MinIdle=10 等连接池参数以支撑全量并发落库
     *
     * @return JedisPool 连接池实例
     */
    @Bean
    public JedisPool redisPoolFactory() {
        log.info("JedisPool 正在初始化...");
        log.info("Redis 目标地址: " + host + ":" + port + ", 数据库: " + database);

        JedisPoolConfig jedisPoolConfig = new JedisPoolConfig();
        jedisPoolConfig.setMaxTotal(200);
        jedisPoolConfig.setMaxIdle(50);
        jedisPoolConfig.setMinIdle(10);
        jedisPoolConfig.setMaxWaitMillis(10000);
        jedisPoolConfig.setTestOnBorrow(true);

        JedisPool jedisPool;
        if (StringUtils.isEmpty(password)) {
            jedisPool = new JedisPool(jedisPoolConfig, host, port, 10000, null, database);
        } else {
            try {
                jedisPool = new JedisPool(jedisPoolConfig, host, port, 10000, password, database);
                try (Jedis j = jedisPool.getResource()) {
                    j.ping();
                }
            } catch (Exception e) {
                // 自动识别服务端未开启密码保护的场景，降级为无密直连
                boolean isNoPassword = false;
                Throwable cur = e;
                while (cur != null) {
                    if (cur.getMessage() != null && cur.getMessage().contains("without any password")) {
                        isNoPassword = true;
                        break;
                    }
                    cur = cur.getCause();
                }
                if (isNoPassword) {
                    log.warn("检测到 Redis 服务未设置密码，自动回退为免密连接模式");
                    jedisPool = new JedisPool(jedisPoolConfig, host, port, 10000, null, database);
                } else {
                    throw e;
                }
            }
        }
        log.info("JedisPool 注入成功！");
        return jedisPool;
    }
}

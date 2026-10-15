package org.codework.tools.gb2260.config.guava;

import com.google.common.eventbus.EventBus;
import org.codework.tools.gb2260.snatch.guava.RunThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Guava EventBus 事件总线与并发执行器集中管理配置类
 * <p>
 * 提供组件解耦的发布-订阅（Pub/Sub）机制，以及全局爬虫执行器线程池实例的安全存储映射
 *
 * @author benjaminkc
 */
@Configuration
public class EventBusConfig {

    /**
     * 构建 Guava EventBus 单机事件总线实例
     * 用于在省、市、区县、街道各级行政区抓取任务间进行异步解耦通信
     *
     * @return EventBus 实例
     */
    @Bean
    public EventBus eventBus() {
        return new EventBus();
    }

    /**
     * 创建全局线程池执行器安全存储映射表
     * Key 为以时间戳命名的任务标识 flag，Value 为任务专属的 {@link RunThreadPoolExecutor}
     *
     * @return 线程安全的 ConcurrentHashMap 实例
     */
    @Bean
    public ConcurrentHashMap<String, RunThreadPoolExecutor> executors() {
        return new ConcurrentHashMap<>(100);
    }
}

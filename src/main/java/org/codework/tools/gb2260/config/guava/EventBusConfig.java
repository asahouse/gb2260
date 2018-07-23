package org.codework.tools.gb2260.config.guava;

import com.google.common.eventbus.EventBus;
import org.codework.tools.gb2260.snatch.guava.RunThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ConcurrentHashMap;

@Configuration
public class EventBusConfig {

    @Bean
    public EventBus eventBus() {
        return new EventBus();
    }

    /**
     * 创建全局线程池执行器存储队列
     * @return
     */
    @Bean
    public ConcurrentHashMap<String, RunThreadPoolExecutor> executors(){
        return new ConcurrentHashMap<>(100);
    }
}


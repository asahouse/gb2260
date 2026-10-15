package org.codework.tools.gb2260.config.guava;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

/**
 * Guava EventBus 自动事件订阅后置处理器
 * <p>
 * 在 Spring 容器初始化 Bean 完成后，自动扫描带有 Guava {@link Subscribe} 注解的方法，
 * 并将对应的 Bean 实例自动注册到全局 {@link EventBus} 中，避免手动注册或依赖顺序引起的循环引用。
 *
 * @author benjaminkc
 */
@Component
public class EventSubscribeBeanPostProcessor implements BeanPostProcessor {

    @Lazy
    @Autowired
    private EventBus eventBus;

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName)
            throws BeansException {
        return bean;
    }

    /**
     * 对于每个容器中执行了初始化的 Spring Bean：
     * 检查目标类的方法是否声明了 @Subscribe 注解，若存在则自动注册到事件总线中
     *
     * @param bean     当前实例化的 Bean
     * @param beanName Bean 名称
     * @return 原 Bean 实例
     * @throws BeansException 异常
     */
    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName)
            throws BeansException {
        Class<?> targetClass = AopUtils.getTargetClass(bean);
        Method[] methods = targetClass.getMethods();
        for (Method method : methods) {
            if (AnnotationUtils.findAnnotation(method, Subscribe.class) != null) {
                eventBus.register(bean);
                return bean;
            }
        }

        return bean;
    }
}

package org.codework.tools.gb2260.config;

import com.baomidou.mybatisplus.extension.plugins.PaginationInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 核心配置类
 * <p>
 * 配置 Mapper 接口扫描路径及通用分页拦截器组件
 *
 * @author benjaminkc
 */
@MapperScan("org.codework.tools.gb2260.mapper")
@Configuration
public class MybatisPlusConfig {

    /**
     * 配置 MyBatis-Plus 分页插件拦截器
     *
     * @return PaginationInterceptor 实例
     */
    @Bean
    public PaginationInterceptor paginationInterceptor() {
        PaginationInterceptor page = new PaginationInterceptor();
        // 设置数据库方言为 MySQL
        page.setDialectType("mysql");
        // 开启 PageHelper 风格本地分页支持
        page.setLocalPage(true);
        return page;
    }
}

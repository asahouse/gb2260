package org.codework.tools.gb2260.config.swagger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import springfox.documentation.builders.ApiInfoBuilder;
import springfox.documentation.builders.PathSelectors;
import springfox.documentation.builders.RequestHandlerSelectors;
import springfox.documentation.service.ApiInfo;
import springfox.documentation.service.Contact;
import springfox.documentation.spi.DocumentationType;
import springfox.documentation.spring.web.plugins.Docket;
import springfox.documentation.swagger2.annotations.EnableSwagger2;

/**
 * Swagger 2 在线 API 接口文档配置类
 * <p>
 * 提供 RESTful API 在线文档交互界面与调试控制台：
 * 访问地址：http://localhost:8080/swagger-ui.html
 *
 * @author benjaminkc
 */
@Configuration
@EnableSwagger2
public class SwaggerConfig {

    /**
     * 构建 Docket 实例，指定扫描的 Controller 包路径并载入 API 基础描述元数据
     *
     * @return Docket 实例
     */
    @Bean
    public Docket createRestApi() {
        return new Docket(DocumentationType.SWAGGER_2)
                .apiInfo(apiInfo())
                .select()
                .apis(RequestHandlerSelectors.basePackage("org.codework.tools.gb2260.controller"))
                .paths(PathSelectors.any())
                .build();
    }

    /**
     * 配置 API 整体概要信息（标题、版本、作者、描述）
     *
     * @return ApiInfo 实例
     */
    private ApiInfo apiInfo() {
        return new ApiInfoBuilder()
                .title("GB2260 全国行政区划数据爬虫与查询服务 API")
                .description("基于国家统计局（stats）与民政部国家地名信息库（mca）双引擎的行政区划采集、持久化（MySQL/Redis）与层级联动查询 REST API 文档")
                .termsOfServiceUrl("https://github.com/benjaminkc/gb2260")
                .contact(new Contact("benjaminkc", "https://github.com/benjaminkc", ""))
                .version("1.0.0")
                .build();
    }
}

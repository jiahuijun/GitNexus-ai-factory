package com.factory.ai;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * AI Factory 应用的启动入口类。
 *
 * <p>该类是整个 Spring Boot 应用的 bootstrap 入口，通过 {@link SpringApplication#run}
 * 启动内嵌 Web 容器并初始化所有 Bean。作为任务分解流水线（task decomposition pipeline）
 * 的 REST API 服务端，承担任务分解、领取（claim）与完成（complete）的对外 HTTP 端点。
 *
 * <p>{@link SpringBootApplication} 注解组合了 {@code @EnableAutoConfiguration}、
 * {@code @ComponentScan} 与 {@code @Configuration}，会自动扫描 {@code com.factory.ai}
 * 及其子包下的所有组件（Controller / Service / Mapper 等），并按 classpath 依赖自动装配配置。
 *
 * <p>{@link MapperScan} 指示 MyBatis-Plus 扫描 {@code com.factory.ai.task.mapper} 包下的
 * Mapper 接口并注册为 Bean。
 */
@SpringBootApplication
@MapperScan("com.factory.ai.task.mapper")
public class FactoryApplication {

    /**
     * 应用主入口方法。
     *
     * <p>将命令行参数委托给 {@link SpringApplication#run} 以启动 Spring 上下文与内嵌容器，
     * 该调用会阻塞直至容器关闭。
     *
     * @param args 命令行启动参数，可包含 Spring 配置项覆盖（如 {@code --server.port=8081}）
     */
    public static void main(String[] args) {
        // SpringApplication.run 负责创建 ApplicationContext、启动内嵌 Tomcat 并注册所有 Bean
        SpringApplication.run(FactoryApplication.class, args);
    }
}

package com.factory.ai.config;

import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置类。
 *
 * <p>注册 {@link OptimisticLockerInnerInterceptor} 拦截器，使 {@link com.baomidou.mybatisplus.annotation.Version}
 * 注解生效：在 UPDATE 时自动追加 version 条件并递增版本号，防止丢失更新。
 *
 * <p>用于 {@link com.factory.ai.task.domain.TaskStep#getVersion()} 字段的乐观锁控制。
 */
@Configuration
public class MyBatisPlusConfig {

    /**
     * 创建 MyBatis-Plus 拦截器链，注册乐观锁拦截器。
     *
     * @return 配置好的 MybatisPlusInterceptor
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor());
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        return interceptor;
    }
}

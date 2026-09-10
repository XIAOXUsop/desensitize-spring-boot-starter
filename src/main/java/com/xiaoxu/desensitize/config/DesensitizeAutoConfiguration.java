package com.xiaoxu.desensitize.config;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.xiaoxu.desensitize.resolver.SensitiveSerializerModifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * 自动配置：注册 Jackson 脱敏模块
 * 关闭方式：xiaoxu.desensitize.enabled=false
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "xiaoxu.desensitize", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DesensitizeAutoConfiguration {

    @Bean
    public SimpleModule sensitiveJacksonModule() {
        SimpleModule module = new SimpleModule("sensitive-desensitize");
        module.setSerializerModifier(new SensitiveSerializerModifier());
        return module;
    }
}

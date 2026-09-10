package com.xiaoxu.desensitize.config;

import com.fasterxml.jackson.databind.module.SimpleModule;
import com.xiaoxu.desensitize.resolver.SensitiveSerializerModifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 自动配置：注册 Jackson 脱敏模块。
 *
 * <p>关闭方式：{@code xiaoxu.desensitize.enabled=false}（此时本配置类整体不生效，
 * 连 {@link DesensitizeProperties} 也不会注册）。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "xiaoxu.desensitize", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(DesensitizeProperties.class)
public class DesensitizeAutoConfiguration {

    @Bean
    public SimpleModule sensitiveJacksonModule(DesensitizeProperties properties) {
        SimpleModule module = new SimpleModule("sensitive-desensitize");
        module.setSerializerModifier(new SensitiveSerializerModifier(properties.getMaskChar()));
        return module;
    }
}

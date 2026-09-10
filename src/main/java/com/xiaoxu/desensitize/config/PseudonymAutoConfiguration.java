package com.xiaoxu.desensitize.config;

import com.xiaoxu.desensitize.pseudonym.InMemoryTokenVault;
import com.xiaoxu.desensitize.pseudonym.PromptRedactor;
import com.xiaoxu.desensitize.pseudonym.Pseudonymizer;
import com.xiaoxu.desensitize.pseudonym.TokenVault;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 可逆假名化的自动配置。
 *
 * <p>与文本脱敏不同，本能力<b>默认不启用</b>（{@code xiaoxu.desensitize.pseudonym.enabled=true} 才生效）：
 * 它必须配置密钥才成立，无密钥时应显式失败而不是悄悄产出一串"谁都能算出来"的假令牌。
 *
 * <p>{@link TokenVault} 默认给内存实现；生产应提供加密落库的 Bean——
 * 由于标注了 {@link ConditionalOnMissingBean}，业务侧自定义实现会自动覆盖。
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "xiaoxu.desensitize.pseudonym", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(PseudonymProperties.class)
public class PseudonymAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public TokenVault tokenVault() {
        return new InMemoryTokenVault();
    }

    @Bean
    @ConditionalOnMissingBean
    public PromptRedactor promptRedactor(PseudonymProperties properties, TokenVault tokenVault) {
        // Pseudonymizer 在密钥为空时会抛异常，使配置错误在启动期暴露，而不是等到第一次发请求
        return new PromptRedactor(new Pseudonymizer(properties.getSecret()), tokenVault, properties.getTypes());
    }
}

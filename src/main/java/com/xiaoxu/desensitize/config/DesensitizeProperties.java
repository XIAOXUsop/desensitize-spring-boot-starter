package com.xiaoxu.desensitize.config;

import com.xiaoxu.desensitize.core.DesensitizeCore;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 脱敏全局配置，前缀 {@code xiaoxu.desensitize}。
 *
 * <p>由 {@link DesensitizeAutoConfiguration} 通过 {@code @EnableConfigurationProperties} 注册；
 * 注意 {@code enabled=false} 时整个自动配置不生效（见 {@code @ConditionalOnProperty}）。
 */
@ConfigurationProperties(prefix = "xiaoxu.desensitize")
public class DesensitizeProperties {

    /** 全局开关，默认 true */
    private boolean enabled = true;

    /** 脱敏占位字符，默认 '*' */
    private char maskChar = DesensitizeCore.DEFAULT_MASK_CHAR;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public char getMaskChar() {
        return maskChar;
    }

    public void setMaskChar(char maskChar) {
        this.maskChar = maskChar;
    }
}

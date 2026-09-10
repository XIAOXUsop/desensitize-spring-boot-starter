package com.xiaoxu.desensitize.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 脱敏全局开关配置
 */
@Configuration
@ConfigurationProperties(prefix = "xiaoxu.desensitize")
public class DesensitizeProperties {

    /** 全局开关，默认 true */
    private boolean enabled = true;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }
}

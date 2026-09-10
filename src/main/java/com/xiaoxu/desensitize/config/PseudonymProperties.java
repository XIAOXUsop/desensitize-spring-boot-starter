package com.xiaoxu.desensitize.config;

import com.xiaoxu.desensitize.annotation.SensitiveType;
import com.xiaoxu.desensitize.pseudonym.PromptRedactor;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Set;

/**
 * 可逆假名化配置，前缀 {@code xiaoxu.desensitize.pseudonym}。
 *
 * <p><b>默认关闭</b>：该能力必须配置密钥才有意义，无密钥时不应静默降级为"弱令牌"。
 */
@ConfigurationProperties(prefix = "xiaoxu.desensitize.pseudonym")
public class PseudonymProperties {

    /** 是否启用；默认 false，需显式开启 */
    private boolean enabled = false;

    /**
     * 令牌密钥。<b>必须</b>通过环境变量或密钥管理服务注入，不要写进配置文件提交到仓库。
     * 换密钥会导致所有令牌改变。
     */
    private String secret;

    /** 参与识别与还原的类型，默认 ID_CARD / BANK_CARD / PHONE / EMAIL */
    private Set<SensitiveType> types = PromptRedactor.DEFAULT_TYPES;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public Set<SensitiveType> getTypes() {
        return types;
    }

    public void setTypes(Set<SensitiveType> types) {
        this.types = types;
    }
}

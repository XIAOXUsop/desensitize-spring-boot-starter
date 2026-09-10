package com.xiaoxu.desensitize.config;

import com.xiaoxu.desensitize.pseudonym.InMemoryTokenVault;
import com.xiaoxu.desensitize.pseudonym.PromptRedactor;
import com.xiaoxu.desensitize.pseudonym.TokenVault;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 可逆假名化自动配置测试：默认关闭、开启需密钥、缺密钥快速失败、TokenVault 可被覆盖。
 */
class PseudonymAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PseudonymAutoConfiguration.class);

    @Test
    void disabledByDefault() {
        runner.run(context -> {
            assertTrue(context.getBeansOfType(PromptRedactor.class).isEmpty(), "默认不应装配假名化能力");
            assertTrue(context.getBeansOfType(TokenVault.class).isEmpty());
        });
    }

    @Test
    void enabledWithSecretWiresRedactorAndVault() {
        runner.withPropertyValues("xiaoxu.desensitize.pseudonym.enabled=true",
                        "xiaoxu.desensitize.pseudonym.secret=s3cr3t")
                .run(context -> {
                    assertNotNull(context.getBean(PromptRedactor.class));
                    assertNotNull(context.getBean(TokenVault.class));
                });
    }

    @Test
    void enabledWithoutSecretFailsFastInsteadOfProducingWeakTokens() {
        runner.withPropertyValues("xiaoxu.desensitize.pseudonym.enabled=true")
                .run(context -> assertNotNull(context.getStartupFailure(),
                        "缺少密钥应在启动期失败，而不是静默产出谁都能算出来的令牌"));
    }

    @Test
    void customVaultBeanOverridesDefault() {
        runner.withPropertyValues("xiaoxu.desensitize.pseudonym.enabled=true",
                        "xiaoxu.desensitize.pseudonym.secret=s3cr3t")
                .withBean(TokenVault.class, InMemoryTokenVault::new)
                .run(context -> assertNotNull(context.getBean(TokenVault.class)));
    }
}

package com.xiaoxu.desensitize.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.xiaoxu.desensitize.annotation.Sensitive;
import com.xiaoxu.desensitize.pseudonym.PromptRedactor;
import com.xiaoxu.desensitize.annotation.SensitiveType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自动配置集成测试：验证 starter 在真实 Spring 上下文中确实装配了 Jackson 脱敏模块，
 * 且开关与配置项按预期生效。
 */
class DesensitizeAutoConfigurationTest {

    static class Sample {
        @Sensitive(type = SensitiveType.ID_CARD)
        public String idCard = "110101199901011234";
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DesensitizeAutoConfiguration.class);

    @Test
    void registersJacksonModuleAndPropertiesByDefault() {
        runner.run(context -> {
            assertFalse(context.getStartupFailure() != null, String.valueOf(context.getStartupFailure()));
            assertNotNull(context.getBean(DesensitizeProperties.class));
            assertTrue(context.getBeansOfType(SimpleModule.class).containsKey("sensitiveJacksonModule"));
        });
    }

    @Test
    void disabledByProperty() {
        runner.withPropertyValues("xiaoxu.desensitize.enabled=false")
                .run(context -> assertTrue(context.getBeansOfType(SimpleModule.class).isEmpty()));
    }

    /**
     * **`xiaoxu.desensitize.enabled=false` 只关掉注解式脱敏，不会关掉假名化。**
     *
     * <p>两者是**并列的两个能力**，各有开关：`PseudonymAutoConfiguration` 只看
     * `xiaoxu.desensitize.pseudonym.enabled`。README 里那行原本写着
     * 「全局开关……设为 false 时整个自动配置不生效」——**那是错的**。
     *
     * <p>为什么值得单独一条：假名化缺密钥会**在启动期失败**（见上面的注释）。
     * 所以"把 starter 整个关掉"如果只写前者，容器照样会起不来，
     * 而报错看起来和"我明明关了"完全矛盾。
     */
    @Test
    void theGlobalSwitchDoesNotDisablePseudonymization() {
        new ApplicationContextRunner()
                .withUserConfiguration(DesensitizeAutoConfiguration.class, PseudonymAutoConfiguration.class)
                .withPropertyValues(
                        "xiaoxu.desensitize.enabled=false",
                        "xiaoxu.desensitize.pseudonym.enabled=true",
                        "xiaoxu.desensitize.pseudonym.secret=unit-test-secret")
                .run(context -> {
                    assertFalse(context.getStartupFailure() != null, String.valueOf(context.getStartupFailure()));
                    assertTrue(context.getBeansOfType(SimpleModule.class).isEmpty(),
                            "enabled=false 应当关掉注解式脱敏那一半");
                    assertNotNull(context.getBean(PromptRedactor.class),
                            "enabled=false **不该**影响假名化——它有自己的开关");
                });
    }

    @Test
    void customMaskCharFlowsIntoSerializer() {
        runner.withPropertyValues("xiaoxu.desensitize.mask-char=#")
                .run(context -> {
                    SimpleModule module = context.getBean(SimpleModule.class);
                    ObjectMapper mapper = new ObjectMapper();
                    mapper.registerModule(module);

                    String json = mapper.writeValueAsString(new Sample());
                    assertTrue(json.contains("110101###1234"), json);
                });
    }
}

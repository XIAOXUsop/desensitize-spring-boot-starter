package com.xiaoxu.desensitize.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.xiaoxu.desensitize.annotation.Sensitive;
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

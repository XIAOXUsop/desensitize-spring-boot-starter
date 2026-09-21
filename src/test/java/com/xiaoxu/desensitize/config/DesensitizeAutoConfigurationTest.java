package com.xiaoxu.desensitize.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.xiaoxu.desensitize.annotation.Sensitive;
import com.xiaoxu.desensitize.pseudonym.PromptRedactor;
import com.xiaoxu.desensitize.annotation.SensitiveType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

    /**
     * **应用自己定义一个 `ObjectMapper` Bean 时，脱敏必须依然生效。**
     *
     * <p>这是本仓库丢失过的一条能力，而且丢得**没有任何症状**。
     * Boot 的 `JacksonAutoConfiguration` 只会把 `Module` Bean 注册进
     * **它自己创建**的那个 `ObjectMapper`，而那个 Bean 挂着
     * `@ConditionalOnMissingBean(ObjectMapper.class)`——应用一旦自建 mapper
     * （加 `JavaTimeModule`、改命名策略、配 `FAIL_ON_UNKNOWN_PROPERTIES`……工程里很常见），
     * Boot 就整个让位，module Bean 留在容器里再也没人用。
     *
     * <p>实测（2026-09-22）：修之前，这条用例拿到的是
     * `{"idCard":"110101199901011234"}`——**明文**；启动成功、无告警、日志无异常。
     *
     * <p>注意：本测试上下文里没有 `spring-web`，所以 `Jackson2ObjectMapperBuilder`
     * 不存在、Boot 也不会自己建 mapper——这里量到的正是"用户自建"那条路径。
     */
    @Configuration
    static class ApplicationDefinesItsOwnMapper {
        @Bean
        ObjectMapper myOwnMapper() {
            return new ObjectMapper();
        }
    }

    @Test
    void desensitizationStillAppliesWhenTheApplicationDefinesItsOwnObjectMapper() {
        runner.withUserConfiguration(ApplicationDefinesItsOwnMapper.class)
                .run(context -> {
                    assertFalse(context.getStartupFailure() != null, String.valueOf(context.getStartupFailure()));

                    ObjectMapper mapper = context.getBean(ObjectMapper.class);
                    String json = mapper.writeValueAsString(new Sample());

                    assertTrue(json.contains("110101***1234"),
                            "应用自建的 ObjectMapper 也必须被装上脱敏模块，实际：" + json);
                });
    }

    /** 开关关掉时，用户自己的 mapper 也不该被动。 */
    @Test
    void whenDisabledTheApplicationsOwnMapperIsLeftAlone() {
        runner.withUserConfiguration(ApplicationDefinesItsOwnMapper.class)
                .withPropertyValues("xiaoxu.desensitize.enabled=false")
                .run(context -> {
                    ObjectMapper mapper = context.getBean(ObjectMapper.class);
                    String json = mapper.writeValueAsString(new Sample());

                    assertFalse(json.contains("110101***1234"),
                            "enabled=false 时不应动应用自己的 mapper，实际：" + json);
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

package com.xiaoxu.desensitize.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.xiaoxu.desensitize.resolver.SensitiveSerializerModifier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
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

    /**
     * 把上面那个模块**装进容器里每一个 {@code ObjectMapper}**，而不只是 Boot 自动配的那个。
     *
     * <p>── 为什么光有一个 Module Bean 不够 ──────────────────────────────
     *
     * <p>Boot 的 {@code JacksonAutoConfiguration} 会收集容器里的 {@code Module} Bean，
     * 注册进它**自己创建**的那个 {@code ObjectMapper}。但那个 Bean 挂着
     * {@code @ConditionalOnMissingBean(ObjectMapper.class)}——**只要应用自己定义了
     * 一个 {@code ObjectMapper} Bean（加 {@code JavaTimeModule}、改命名策略、
     * 配 {@code FAIL_ON_UNKNOWN_PROPERTIES}……工程里很常见），Boot 就整个让位**，
     * 那个 module Bean 于是留在容器里、再也没人把它注册进任何 mapper。
     *
     * <p>后果是**启动成功、日志干净、任何地方都不报错**，而
     * 所有 {@code @Sensitive} 字段明文出网。实测（2026-09-22，{@code ApplicationContextRunner}）：
     * 应用自建 mapper 时序列化结果是 {@code {"idCard":"110101199901011234"}}，
     * 而用 Boot 自建 mapper 时是 {@code {"idCard":"110101***1234"}}。
     * 这种"没有任何症状的失效"正是本仓库最想防的一类。
     *
     * <p>── 为什么用 BeanPostProcessor ──────────────────────────────────
     *
     * <p>它拿得到**容器里所有** {@code ObjectMapper} Bean，包括用户自己 new 的那个；
     * {@code Jackson2ObjectMapperBuilderCustomizer} 只管 Boot 的那条路，覆盖不到这里。
     * 注册是幂等的：先看 {@code getRegisteredModuleIds()}，已经在位就不重复注册
     * （Boot 那条路上模块已经装好了，这里会直接跳过）。
     *
     * <p>声明成 {@code static} 是为了不让本配置类被迫提前实例化
     * （BeanPostProcessor 本身必须在其他 Bean 之前就绪）。
     */
    @Bean
    static BeanPostProcessor sensitiveObjectMapperInstaller(ObjectProvider<SimpleModule> moduleProvider) {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                if (bean instanceof ObjectMapper mapper) {
                    SimpleModule module = moduleProvider.getIfAvailable();
                    if (module != null && !mapper.getRegisteredModuleIds().contains(module.getTypeId())) {
                        mapper.registerModule(module);
                    }
                }
                return bean;
            }
        };
    }
}

package com.xiaoxu.desensitize.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注需要脱敏的字段（基于 Jackson 序列化层，展示态脱敏）
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Sensitive {

    /** 脱敏类型 */
    SensitiveType type() default SensitiveType.CUSTOM;

    /** 仅 SensitiveType.CUSTOM 时生效：保留前几位 */
    int keepFirst() default 1;

    /** 仅 SensitiveType.CUSTOM 时生效：保留后几位 */
    int keepLast() default 0;
}

package com.xiaoxu.desensitize.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注需要脱敏的属性（基于 Jackson 序列化层，展示态脱敏）。
 *
 * <p>可标注在 <b>字段</b> 或 <b>getter 方法</b> 上；两者都写时以字段为准。
 * 数据库/内存中仍是原值，只在序列化输出的那一刻掩码，因此不影响业务逻辑读取真实数据。
 */
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface Sensitive {

    /** 脱敏类型 */
    SensitiveType type() default SensitiveType.CUSTOM;

    /** 仅 {@link SensitiveType#CUSTOM} 时生效：保留前几位 */
    int keepFirst() default 1;

    /** 仅 {@link SensitiveType#CUSTOM} 时生效：保留后几位 */
    int keepLast() default 0;
}

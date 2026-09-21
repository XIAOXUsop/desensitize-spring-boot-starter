package com.xiaoxu.desensitize.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注需要脱敏的属性（基于 Jackson 序列化层，展示态脱敏）。
 *
 * <p>可标注在 <b>字段</b> 或 <b>getter 方法</b> 上。
 *
 * <p><b>两处都写时，生效的是 Jackson 为这个属性挑中的那个「主成员」上的注解，
 * 其次才是同名字段，最后是 getter。</b>（这条描述的是实际行为。
 * 此处原先写的是「以字段为准」——**那是错的**：实测（2026-09-22）
 * 一个类里字段标 {@code ID_CARD}、getter 标 {@code CUSTOM(keepFirst=17, keepLast=1)}，
 * 两个方向都是 getter 的规则生效。）
 *
 * <p>由于哪一方胜出取决于 Jackson 选中了谁，**别把同一条属性标注在两处**——
 * 想表达哪种脱敏就在一处写清楚，歧义就没有了。
 *
 * <p><b>只对标量生效</b>（{@code String}、{@code CharSequence}、数值、字符、UUID）。
 * 标在集合 / Map / 数组上不会有任何效果——那类值不是"一个敏感标量"，
 * 把整个结构转成文本会把数组拍平、丢结构。要脱敏集合元素，
 * 请在元素的类型上标注（那个类型的字段会被各自处理）。
 * 动态载体（{@code Map<String,Object>}、{@code ObjectNode}）没有可标之处，
 * 不在本注解的覆盖范围内。
 *
 * <p>数据库/内存中仍是原值，只在序列化输出的那一刻掩码，因此不影响业务逻辑读取真实数据。
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

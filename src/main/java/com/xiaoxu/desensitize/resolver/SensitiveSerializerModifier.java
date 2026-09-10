package com.xiaoxu.desensitize.resolver;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.xiaoxu.desensitize.annotation.Sensitive;
import com.xiaoxu.desensitize.annotation.SensitiveType;
import com.xiaoxu.desensitize.core.DesensitizeCore;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Jackson 序列化期间拦截 {@link Sensitive} 属性并脱敏。
 *
 * <p>注解可写在字段或 getter 上；查找顺序为 属性成员 → 同名字段 → getter 方法，
 * 以兼容 Jackson 在不同版本/场景下选择了不同「主成员」的情况。
 *
 * <p>嵌套对象与集合元素无需特殊处理：Jackson 会为每个实际类型各自调用本拦截器。
 */
public class SensitiveSerializerModifier extends BeanSerializerModifier {

    private static final String[] GETTER_PREFIXES = {"get", "is"};

    private final char maskChar;

    public SensitiveSerializerModifier() {
        this(DesensitizeCore.DEFAULT_MASK_CHAR);
    }

    public SensitiveSerializerModifier(char maskChar) {
        this.maskChar = maskChar;
    }

    @Override
    public List<BeanPropertyWriter> changeProperties(SerializationConfig config,
                                                     BeanDescription beanDesc,
                                                     List<BeanPropertyWriter> beanProperties) {
        beanProperties.replaceAll(writer -> {
            Sensitive annotation = findSensitive(writer);
            return annotation == null ? writer : new SensitivePropertyWriter(writer, annotation, maskChar);
        });
        return beanProperties;
    }

    private Sensitive findSensitive(BeanPropertyWriter writer) {
        Sensitive onMember = writer.getAnnotation(Sensitive.class);
        if (onMember != null) {
            return onMember;
        }

        AnnotatedMember member = writer.getMember();
        String name = writer.getName();
        if (member == null || name.isEmpty()) {
            return null;
        }
        Class<?> declaringClass = member.getDeclaringClass();

        Sensitive onField = findOnField(declaringClass, name);
        return onField != null ? onField : findOnGetter(declaringClass, name);
    }

    private Sensitive findOnField(Class<?> type, String name) {
        try {
            Field field = type.getDeclaredField(name);
            return field.getAnnotation(Sensitive.class);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    private Sensitive findOnGetter(Class<?> type, String name) {
        String capitalized = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (String prefix : GETTER_PREFIXES) {
            try {
                Method getter = type.getDeclaredMethod(prefix + capitalized);
                Sensitive annotation = getter.getAnnotation(Sensitive.class);
                if (annotation != null) {
                    return annotation;
                }
            } catch (NoSuchMethodException ignored) {
                // 该前缀没有对应 getter，继续尝试下一个
            }
        }
        return null;
    }

    /** 包装 writer：写出前脱敏 */
    static class SensitivePropertyWriter extends BeanPropertyWriter {

        private final Sensitive annotation;
        private final char maskChar;

        SensitivePropertyWriter(BeanPropertyWriter base, Sensitive annotation, char maskChar) {
            super(base);
            this.annotation = annotation;
            this.maskChar = maskChar;
        }

        @Override
        public void serializeAsField(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
            Object value = get(bean);
            if (value instanceof String text) {
                SensitiveType type = annotation.type();
                String masked = DesensitizeCore.mask(
                        type, text, annotation.keepFirst(), annotation.keepLast(), maskChar);
                gen.writeFieldName(_name);
                gen.writeString(masked);
                return;
            }
            super.serializeAsField(bean, gen, prov);
        }
    }
}

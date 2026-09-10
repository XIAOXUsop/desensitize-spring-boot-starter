package com.xiaoxu.desensitize.resolver;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.xiaoxu.desensitize.annotation.Sensitive;
import com.xiaoxu.desensitize.annotation.SensitiveType;
import com.xiaoxu.desensitize.core.DesensitizeCore;

import java.io.IOException;
import java.util.List;

/**
 * Jackson 序列化期间拦截 @Sensitive 字段并脱敏
 */
public class SensitiveSerializerModifier extends BeanSerializerModifier {

    @Override
    public java.util.List<com.fasterxml.jackson.databind.ser.BeanPropertyWriter> changeProperties(
            com.fasterxml.jackson.databind.SerializationConfig config,
            com.fasterxml.jackson.databind.BeanDescription beanDesc,
            java.util.List<com.fasterxml.jackson.databind.ser.BeanPropertyWriter> beanProperties) {

        beanProperties.replaceAll(writer -> {
            Sensitive ann = findSensitive(writer);
            if (ann == null) {
                return writer;
            }
            return new SensitivePropertyWriter(writer, ann);
        });
        return beanProperties;
    }

    private Sensitive findSensitive(BeanPropertyWriter writer) {
        Sensitive ann = writer.getAnnotation(Sensitive.class);
        if (ann != null) {
            return ann;
        }
        // 兼容字段上注解未暴露到 property 的场景
        Class<?> cls = writer.getMember().getDeclaringClass();
        try {
            return cls.getDeclaredField(writer.getName()).getAnnotation(Sensitive.class);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    /** 包装 writer：写出前脱敏 */
    static class SensitivePropertyWriter extends BeanPropertyWriter {
        private final Sensitive ann;

        protected SensitivePropertyWriter(BeanPropertyWriter base, Sensitive ann) {
            super(base);
            this.ann = ann;
        }

        @Override
        public void serializeAsField(Object bean, JsonGenerator gen, SerializerProvider prov) throws Exception {
            Object value = get(bean);
            if (value instanceof String s) {
                SensitiveType t = ann.type();
                String masked = DesensitizeCore.mask(t, s, ann.keepFirst(), ann.keepLast());
                gen.writeFieldName(_name);
                gen.writeString(masked);
                return;
            }
            super.serializeAsField(bean, gen, prov);
        }
    }
}

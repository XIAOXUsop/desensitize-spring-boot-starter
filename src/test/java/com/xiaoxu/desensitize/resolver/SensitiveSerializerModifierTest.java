package com.xiaoxu.desensitize.resolver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.xiaoxu.desensitize.annotation.Sensitive;
import com.xiaoxu.desensitize.annotation.SensitiveType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveSerializerModifierTest {

    /** 注解写在字段上 */
    static class Customer {
        @Sensitive(type = SensitiveType.ID_CARD)
        public String idCard = "110101199901011234";

        @Sensitive(type = SensitiveType.PHONE)
        public String phone = "13800000000";

        @Sensitive(type = SensitiveType.NAME)
        public String name = "张三丰";

        public String plain = "not-sensitive";
    }

    /** 注解写在 getter 上 */
    static class GetterAnnotated {
        private final String email;

        GetterAnnotated(String email) {
            this.email = email;
        }

        @Sensitive(type = SensitiveType.EMAIL)
        public String getEmail() {
            return email;
        }
    }

    /** 未标注的类不受影响 */
    static class Plain {
        public String value = "110101199901011234";
    }

    /** 值为 null 不应抛异常 */
    static class Nullable {
        @Sensitive(type = SensitiveType.PHONE)
        public String phone = null;
    }

    /** 嵌套对象与集合元素 */
    static class Order {
        public String orderNo = "A-1";
        public Customer customer = new Customer();
        public List<Customer> customers = List.of(new Customer());
    }

    private static ObjectMapper mapperWith(char maskChar) {
        ObjectMapper mapper = new ObjectMapper();
        SimpleModule module = new SimpleModule("test");
        module.setSerializerModifier(new SensitiveSerializerModifier(maskChar));
        mapper.registerModule(module);
        return mapper;
    }

    @Test
    void masksFieldsAnnotatedOnField() throws Exception {
        String json = mapperWith('*').writeValueAsString(new Customer());

        assertTrue(json.contains("110101***1234"), json);
        assertTrue(json.contains("138***0000"), json);
        assertTrue(json.contains("张***"), json);
        assertTrue(json.contains("not-sensitive"), json);
        assertFalse(json.contains("张三丰"), json);
        assertFalse(json.contains("110101199901011234"), json);
    }

    @Test
    void masksGettersAnnotatedOnMethod() throws Exception {
        String json = mapperWith('*').writeValueAsString(new GetterAnnotated("zhangsan@example.com"));

        assertTrue(json.contains("z***@example.com"), json);
        assertFalse(json.contains("zhangsan@example.com"), json);
    }

    @Test
    void masksNestedObjectsAndCollectionElements() throws Exception {
        String json = mapperWith('*').writeValueAsString(new Order());

        assertTrue(json.contains("A-1"), json);
        assertFalse(json.contains("110101199901011234"), json);
        assertFalse(json.contains("13800000000"), json);
        assertFalse(json.contains("张三丰"), json);
    }

    @Test
    void unannotatedClassIsUntouched() throws Exception {
        String json = mapperWith('*').writeValueAsString(new Plain());
        assertTrue(json.contains("110101199901011234"), json);
    }

    @Test
    void nullValueDoesNotFail() throws Exception {
        String json = mapperWith('*').writeValueAsString(new Nullable());
        assertTrue(json.contains("\"phone\""), json);
    }

    @Test
    void customMaskCharIsApplied() throws Exception {
        String json = mapperWith('#').writeValueAsString(new Customer());

        assertTrue(json.contains("110101###1234"), json);
        assertTrue(json.contains("138###0000"), json);
    }
}

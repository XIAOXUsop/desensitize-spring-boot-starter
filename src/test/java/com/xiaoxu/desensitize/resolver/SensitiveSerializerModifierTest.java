package com.xiaoxu.desensitize.resolver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xiaoxu.desensitize.annotation.Sensitive;
import com.xiaoxu.desensitize.annotation.SensitiveType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SensitiveSerializerModifierTest {

    static class Customer {
        @Sensitive(type = SensitiveType.ID_CARD)
        public String idCard = "110101199901011234";

        @Sensitive(type = SensitiveType.PHONE)
        public String phone = "13800000000";

        @Sensitive(type = SensitiveType.NAME)
        public String name = "张三丰";

        public String plain = "not-sensitive";
    }

    @Test
    void serializesMasked() throws Exception {
        ObjectMapper om = new ObjectMapper();
        om.registerModule(new com.fasterxml.jackson.databind.module.SimpleModule() {{
            setSerializerModifier(new SensitiveSerializerModifier());
        }});
        String json = om.writeValueAsString(new Customer());
        System.out.println(json);
        assertTrue(json.contains("110101***1234"), "idCard 应被脱敏");
        assertTrue(json.contains("138***0000"), "phone 应被脱敏");
        assertTrue(json.contains("张***"), "姓名应被脱敏");
        assertTrue(json.contains("not-sensitive"), "普通字段不应受影响");
        assertTrue(!json.contains("张三丰"), "不会泄露原名");
    }
}

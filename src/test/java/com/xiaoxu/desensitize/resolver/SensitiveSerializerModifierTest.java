package com.xiaoxu.desensitize.resolver;

import com.fasterxml.jackson.annotation.JsonProperty;
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

    /** 非 String 的标量：`Long phone` 这类在国内金融 DTO 里很常见 */
    static class NumericFields {
        @Sensitive(type = SensitiveType.PHONE)
        public Long phone = 13800000000L;

        @Sensitive(type = SensitiveType.ID_CARD)
        public String idCard = "110101199901011234";
    }

    /** 字段与 getter 都标注 */
    static class BothAnnotated {
        @Sensitive(type = SensitiveType.ID_CARD)
        private String idCard = "110101199901011234";

        @Sensitive(type = SensitiveType.CUSTOM, keepFirst = 17, keepLast = 1)
        public String getIdCard() {
            return idCard;
        }
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

    /**
     * **非 String 的标量也必须脱敏。**
     *
     * <p>拦截器里原先写的是 `value instanceof String text`，其余类型直接
     * `super.serializeAsField` 落下去。实测（2026-09-22）：下面这个类里
     * `phone` 输出的是 `13800000000` 明文，而同一行的 `idCard`（String）正常掩码——
     * 同一个类、同一种注解，一个生效一个不生效。
     */
    @Test
    void masksNonStringScalarsToo() throws Exception {
        String json = mapperWith('*').writeValueAsString(new NumericFields());

        assertFalse(json.contains("13800000000"), "Long 字段也必须脱敏：" + json);
        assertTrue(json.contains("138***0000"), json);
        assertFalse(json.contains("110101199901011234"), json);
    }

    /**
     * 字段与 getter 都标注时，**实际生效的是 getter**。
     *
     * <p>`Sensitive` 的 javadoc 原先写的是"两者都写时以字段为准"，
     * 与这里的行为相反。这次改的是文档而不是实现：两种顺序都不能说更"安全"
     * （字段那条更严时字段胜出更好，反过来则 getter 胜出更好），
     * 擅自翻转会让现有工程的脱敏强度悄悄变化。所以把实测行为钉住，
     * 并在 javadoc 里写清"别把同一条属性标注在两处"。
     */
    @Test
    void whenBothFieldAndGetterAreAnnotatedTheGettersRuleWins() throws Exception {
        String json = mapperWith('*').writeValueAsString(new BothAnnotated());

        // `1***` 是 getter 上 CUSTOM(keepFirst=17, keepLast=1) 的结果；
        // 若是字段上的 ID_CARD 胜出，这里会是 `110101***1234`。
        assertTrue(json.contains("1***"), json);
        assertFalse(json.contains("110101***1234"), json);
    }

    /**
     * 注解标在**私有字段**上、而 Jackson 挑中的主成员是另一个方法时，
     * `findOnField` 那段回退查找必须生效。
     *
     * <p>这条此前没有覆盖：既有 fixture 只有「public 字段」与「直接标在 getter 上」两种，
     * 而 public 字段时 Jackson 的主成员就是那个字段，`writer.getAnnotation()` 一步就命中，
     * 整段回退逻辑（约 40 行）**删掉之后 98 条用例全绿**。
     *
     * <p>下面这个是真实形态：字段私有、另起一个 `@JsonProperty` 方法当出口——
     * 属性名与字段名一致，而 Jackson 看到的主成员是那个方法。
     */
    static class AnnotatedFieldBehindARenamedGetter {
        @Sensitive(type = SensitiveType.ID_CARD)
        private String idCard = "110101199901011234";

        @JsonProperty("idCard")
        public String fetchCard() {
            return idCard;
        }
    }

    @Test
    void annotationOnAPrivateFieldIsFoundWhenJacksonPicksAnotherMember() throws Exception {
        String json = mapperWith('*').writeValueAsString(new AnnotatedFieldBehindARenamedGetter());

        assertFalse(json.contains("110101199901011234"),
                "主成员上没有注解时，要回退去找同名字段上的注解，否则这里会明文出网：" + json);
        assertTrue(json.contains("110101***1234"), json);
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

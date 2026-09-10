package com.xiaoxu.desensitize;

import com.xiaoxu.desensitize.annotation.Sensitive;
import com.xiaoxu.desensitize.annotation.SensitiveType;
import com.xiaoxu.desensitize.core.DesensitizeCore;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DesensitizeCoreTest {

    @Test
    void idCard() {
        assertEquals("110101***1234", DesensitizeCore.mask(SensitiveType.ID_CARD, "110101199901011234", 0, 0));
    }

    @Test
    void phone() {
        assertEquals("138***0000", DesensitizeCore.mask(SensitiveType.PHONE, "13800000000", 0, 0));
    }

    @Test
    void name() {
        assertEquals("张***", DesensitizeCore.mask(SensitiveType.NAME, "张三丰", 0, 0));
    }

    @Test
    void email() {
        assertEquals("z***@example.com", DesensitizeCore.mask(SensitiveType.EMAIL, "zhangsan@example.com", 0, 0));
    }

    @Test
    void bankCard() {
        assertEquals("***4612", DesensitizeCore.mask(SensitiveType.BANK_CARD, "622202020011234612", 0, 0));
    }

    @Test
    void address() {
        assertEquals("北京市朝阳区***", DesensitizeCore.mask(SensitiveType.ADDRESS, "北京市朝阳区幸福大街12号", 0, 0));
    }

    @Test
    void ip() {
        assertEquals("192.168.*.*", DesensitizeCore.mask(SensitiveType.IP, "192.168.1.23", 0, 0));
    }

    @Test
    void shortValueProtected() {
        // 长度不足时不会越界，整体打码保留首字符
        assertEquals("1***", DesensitizeCore.mask(SensitiveType.CUSTOM, "12", 3, 4));
    }

    @Test
    void nullAndEmpty() {
        assertEquals(null, DesensitizeCore.mask(SensitiveType.CUSTOM, null, 1, 1));
        assertEquals("", DesensitizeCore.mask(SensitiveType.CUSTOM, "", 1, 1));
    }

    /** 注解默认值定义与 SensitiveType 有效性检查 */
    @Test
    void annotationDefaults() throws Exception {
        class Holder {
            @Sensitive
            String field;
        }
        Sensitive s = Holder.class.getDeclaredField("field").getAnnotation(Sensitive.class);
        assertEquals(SensitiveType.CUSTOM, s.type());
        assertEquals(1, s.keepFirst());
        assertEquals(0, s.keepLast());
    }
}

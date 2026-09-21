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

    /**
     * **配置写错不该把接口打成 500。**
     *
     * <p>`keepFirst` / `keepLast` 是使用者写在注解里的，写负数不是不可能。
     * 实测（2026-09-22）：`keepFirst = -1, keepLast = -1` 会走到
     * `raw.substring(0, -1)` 抛 `StringIndexOutOfBoundsException`；
     * `Integer.MAX_VALUE` 则因为 `keepFirst + keepLast` 溢出成负数、
     * 绕过下面那个 `>= len` 判断，同样抛。README 承诺的"不会越界"
     * 当时只覆盖了"值不够长"，没覆盖"参数本身越界"。
     */
    @Test
    void outOfRangeKeepCountsAreClampedInsteadOfThrowing() {
        // 负数与"不保留"同义，钳到 0：结果是只留下掩码字符，不抛异常
        assertEquals("***", DesensitizeCore.mask(SensitiveType.CUSTOM, "1234567890", -1, -1));
        assertEquals("***", DesensitizeCore.mask(SensitiveType.CUSTOM, "1234567890",
                Integer.MIN_VALUE, Integer.MIN_VALUE));
        // 极大值曾经因为 keepFirst + keepLast 溢出成负数而绕过下面的长度判断
        assertEquals("1***", DesensitizeCore.mask(SensitiveType.CUSTOM, "1234567890",
                Integer.MAX_VALUE, Integer.MAX_VALUE));
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

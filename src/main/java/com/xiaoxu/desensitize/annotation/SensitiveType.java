package com.xiaoxu.desensitize.annotation;

/**
 * 内置脱敏类型（8 种常用掩码策略）
 */
public enum SensitiveType {
    /** 自定义：按 keepFirst/keepLast 保留 */
    CUSTOM,
    /** 身份证：110101********1234（前 6 后 4） */
    ID_CARD,
    /** 手机号：138****0000（前 3 后 4） */
    PHONE,
    /** 姓名：张**（默认保留 1 位） */
    NAME,
    /** 邮箱：z****@example.com（保留首字符与 @后缀） */
    EMAIL,
    /** 银行卡：6222 **** **** 1234（后 4 位） */
    BANK_CARD,
    /** 地址：北京市朝阳区*******（保留前 6 位） */
    ADDRESS,
    /** IPv4：192.168.*.* (保留前两段) */
    IP
}

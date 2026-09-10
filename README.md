# desensitize-spring-boot-starter

> 注解式敏感数据脱敏 Spring Boot Starter —— 一个 `@Sensitive` 注解搞定接口返回值的脱敏。

<div align="center">

![Java](https://img.shields.io/badge/Java-21-orange?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot_3.5-6DB33F?logo=spring-boot&logoColor=white)
![Jackson](https://img.shields.io/badge/Jackson-databind-2C5E2E)
![License](https://img.shields.io/badge/License-MIT-blue)

</div>

## 解决什么问题

金融、医疗等业务系统里，接口返回的客户数据常常包含身份证号、手机号、姓名等敏感字段。手工在每个 DTO 的 getter 或 Service 里写脱敏代码，重复且容易漏；一旦漏掉一处，真实客户数据就直接吐给了前端。

本项目把脱敏**下沉到 Jackson 序列化层**：给字段加一个注解，序列化时自动掩码，业务代码零侵入。

## 快速开始

### 1. 引入依赖

尚未发布到 Maven Central，可先 clone 后本地安装：

```bash
mvn install         # 安装到本地仓库
```

```xml
<dependency>
    <groupId>com.xiaoxu</groupId>
    <artifactId>desensitize-spring-boot-starter</artifactId>
    <version>0.2.0</version>
</dependency>
```

### 2. 给字段或 getter 加注解

注解可以写在**字段**上，也可以写在 **getter** 上：

```java
public class Customer {
    @Sensitive(type = SensitiveType.ID_CARD)             // 110101***1234
    private String idCard;

    @Sensitive(type = SensitiveType.PHONE)               // 138***0000
    private String phone;

    @Sensitive(type = SensitiveType.NAME, keepFirst = 1) // 张***
    private String name;

    private String city;                                 // 未标注 → 不脱敏

    private String email;

    @Sensitive(type = SensitiveType.EMAIL)               // z***@example.com
    public String getEmail() {
        return email;
    }
}
```

**嵌套对象与集合自动生效**：`Order` 里嵌 `Customer`、或 `List<Customer>`，只要元素类上标了注解就会脱敏，无需额外配置。

### 3. 正常返回即可

```java
@GetMapping("/customers/{id}")
public Customer detail(@PathVariable Long id) {
    return customerService.get(id);   // 序列化成 JSON 时自动脱敏
}
```

无需任何额外配置，Spring Boot 3 自动装配生效。

## 内置脱敏类型

| `SensitiveType` | 效果 | 示例 |
|---|---|---|
| `ID_CARD` | 保留前 6 后 4 | `110101199901011234` → `110101***1234` |
| `PHONE` | 保留前 3 后 4 | `13800000000` → `138***0000` |
| `NAME` | 默认保留 1 位 | `张三丰` → `张***` |
| `EMAIL` | 保留首字符与 `@` 后缀 | `zhangsan@example.com` → `z***@example.com` |
| `BANK_CARD` | 保留后 4 位 | `622202020011234612` → `***4612` |
| `ADDRESS` | 保留前 6 位 | `北京市朝阳区幸福大街12号` → `北京市朝阳区***` |
| `IP` | 保留前两段 | `192.168.1.23` → `192.168.*.*` |
| `CUSTOM` | 按 `keepFirst` / `keepLast` 自定义 | `@Sensitive(keepFirst = 2, keepLast = 2)` |

> 长度不足时不会越界，整体打码并保留首字符。

## 配置

```yaml
xiaoxu:
  desensitize:
    enabled: true      # 全局开关，默认 true；设为 false 时整个自动配置不生效
    mask-char: '*'     # 占位字符，默认 '*'
```

配置项已提供 IDE 元数据（`additional-spring-configuration-metadata.json`），在 `application.yml` 中有补全提示。

## 工作原理

```
REST 响应对象
   │
   ▼
Jackson 序列化
   │
   ▼  注册 BeanSerializerModifier
SensitiveSerializerModifier
   │  扫描带 @Sensitive 的字段 / getter
   ▼
DesensitizeCore.mask(type, raw, keepFirst, keepLast, maskChar)
   │
   ▼
脱敏后的 JSON
```

关键是切入点选在**序列化层（展示态）**，而不是持久层（存储态）：
数据库里仍然是密文/原文，只在向外输出的那一刻掩码，因此不侵入 MyBatis / JPA，也不影响内部业务逻辑对真实数据的读取。

## 测试

```bash
mvn test
```

覆盖：8 种脱敏类型的算法与边界、字段注解 / getter 注解、嵌套对象与集合、
null 值、自定义占位字符，以及 **自动配置集成测试**（`ApplicationContextRunner`
验证 starter 在真实 Spring 上下文中装配成功、开关与配置项生效）。

## Roadmap

- [ ] 发布到 Maven Central
- [ ] `@Sensitive` 支持类级默认策略
- [ ] 可选的 MyBatis 查询日志脱敏
- [ ] 与 `amlagent` 联动：尽调报告导出时的统一脱敏出口

## 相关项目

本 Starter 脱胎于开源项目 [amlagent](https://github.com/XIAOXUsop/amlagent)（商业银行智能反洗钱尽调平台）的工程需求 —— 客户身份证号、姓名、交易数据在对外输出前必须脱敏。

## License

[MIT](LICENSE) © 2026 XIAOXUsop

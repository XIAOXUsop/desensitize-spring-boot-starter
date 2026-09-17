# desensitize-spring-boot-starter

> 敏感数据防护 Spring Boot Starter，两层能力：
> **① 接口返回值脱敏**（`@Sensitive` 注解，不可逆掩码）
> **② 大模型输入输出脱敏**（可逆假名化，让模型看得见上下文、看不见真实身份）

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

尚未发布到 Maven Central，两种取用方式：

**方式一：直接下载 jar 安装到本地仓库**（无需 clone、无需构建）

```bash
curl -LO https://github.com/XIAOXUsop/desensitize-spring-boot-starter/releases/latest/download/desensitize-spring-boot-starter-0.4.0.jar
mvn install:install-file \
  -Dfile=desensitize-spring-boot-starter-0.4.0.jar \
  -DgroupId=com.xiaoxu \
  -DartifactId=desensitize-spring-boot-starter \
  -Dversion=0.4.0 -Dpackaging=jar
```

**方式二：从源码构建**

```bash
mvn install         # 安装到本地仓库
```

```xml
<dependency>
    <groupId>com.xiaoxu</groupId>
    <artifactId>desensitize-spring-boot-starter</artifactId>
    <version>0.4.0</version>
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

## ② 大模型输入输出脱敏（可逆假名化）

掩码解决不了大模型场景：它保留了部分原文（仍是个人信息），且模型无法凭掩码在整段对话里
认出"是同一个人"。这里用**确定性令牌**替代：

```
用户：帮我看下客户 110101199003078531 的交易是否可疑
  ↓ redact()  出站脱敏
发给模型：帮我看下客户 ID_CARD_v2_9f2c4a1b7e3d5086c1a4f0b2d9e73618 的交易是否可疑
  ↓ 模型回复
模型回复：客户 ID_CARD_v2_9f2c4a1b7e3d5086c1a4f0b2d9e73618 近 3 月有 14 笔等额存取，建议转人工
  ↓ restore() 入站还原
展示给柜员：客户 110101199003078531 近 3 月有 14 笔等额存取，建议转人工
```

### 为什么用令牌而不是掩码

| | 掩码 | 确定性令牌 |
|---|---|---|
| 含原文 | 保留部分（仍是个人信息） | **完全不保留** |
| 同一人跨轮次可辨识 | 否 | **是**（同一输入恒得同一令牌） |
| 可逆 | 否 | 是（需令牌保险库） |
| 可否伪造 | — | 否（依赖 HMAC 密钥） |

令牌由 `HMAC-SHA256(密钥, 类型|版本|原文)` 截断而来，**换密钥则全部令牌改变**；
没有密钥既不能反推原文，也不能伪造令牌。类型并入摘要输入，
使同一串数字在"身份证"与"银行卡"语境下得到不同令牌。

### 令牌格式与碰撞

```
TYPE_v{版本}_{十六进制摘要}      例：ID_CARD_v2_9f2c4a1b7e3d5086c1a4f0b2d9e73618
```

| 版本 | 摘要 | 状态 |
|---|---|---|
| `v2` | 32 位十六进制 = **128 bit** | 当前生成格式 |
| `v1` | 10 位十六进制 = 40 bit | 已停止生成，但仍可识别并还原历史令牌 |

早期 v1 只保留 40 bit。按生日界估算，**约 100 万个不同明文时就有约 50% 概率**出现
两个明文得到同一令牌——对银行客户量级而言这不是理论风险。v2 提到 128 bit 后，
同一量级下的碰撞概率可忽略。

位数解决的是概率，**碰撞一旦真的发生，保险库必须失败而不是将就**：

```java
vault.remember(token, ID_CARD, "110101199003078531");
vault.remember(token, ID_CARD, "110101199003078532");
// throws TokenCollisionException —— 不覆盖、不忽略、不静默
```

如果沿用"先写入的那条"（`putIfAbsent`），模型回复里的令牌会被还原成**另一个人的真实身份**；
如果覆盖，此前引用该令牌的上下文会集体指向错误的人。两种做法都没有任何外部症状，
因此这里选择拒绝写入并抛出异常，让调用方看见。异常信息只含令牌与类型，**不含任何原文**。

### 开启方式

默认关闭——该能力必须配置密钥才成立，无密钥时**启动期即失败**，而不是悄悄产出一串
"谁都能算出来"的假令牌。

```yaml
xiaoxu:
  desensitize:
    pseudonym:
      enabled: true
      secret: ${AML_PSEUDONYM_SECRET}   # 必须由环境变量/密钥管理服务注入
      types: [ID_CARD, BANK_CARD, PHONE, EMAIL]   # 可选，默认即这四种
```

```java
String outbound = promptRedactor.redact(userPrompt);   // 发给模型前
String inbound  = promptRedactor.restore(modelReply);  // 收到回复后
```

### 生产实现要点

`TokenVault` 默认给的是内存实现（进程重启即丢），生产应替换为加密落库的实现：

- 映射表加密存储，且与令牌密钥**分开管理**
- 每次还原落审计日志（谁、何时、还原了哪类数据）
- 支持按时间/客户维度删除（被遗忘权）
- **同令牌不同原文必须失败**（抛 `TokenCollisionException`），不得覆盖也不得静默保留
- 标注了 `@ConditionalOnMissingBean`，业务侧自定义实现会自动覆盖默认

> **边界说明**：脱敏只保护"传输与推理"环节。真实值最终仍会回到应用侧，
> 因此应用侧自身的权限与审计不能被这一层替代。

## 测试

```bash
mvn test
```

65 项测试覆盖：8 种脱敏类型的算法与边界、字段注解 / getter 注解、嵌套对象与集合、
null 值、自定义占位字符；**令牌位数与版本解析（v1/v2）、跨密钥不可伪造 / 无原文残留 /
多轮一致 / 还原往返 / 未知令牌与伪造令牌不猜测**；**保险库碰撞失败**（同令牌同原文幂等、
同令牌异原文抛异常且保留原映射、异常不泄漏原文）；以及两组 **自动配置集成测试**
（`ApplicationContextRunner` 验证真实 Spring 上下文中的装配、开关、缺密钥快速失败）。

## Roadmap

- [ ] 发布到 Maven Central
- [ ] 令牌保险库的加密落库实现 + 还原审计
- [ ] 提供 `ChatModel` 装饰器，把 redact/restore 自动套在 LangChain4j 调用链上
- [ ] `@Sensitive` 支持类级默认策略
- [ ] 可选的 MyBatis 查询日志脱敏

## 相关项目

本 Starter 脱胎于开源项目 [amlagent](https://github.com/XIAOXUsop/amlagent)（商业银行智能反洗钱尽调平台）的工程需求 —— 客户身份证号、姓名、交易数据在对外输出前必须脱敏。

## License

[MIT](LICENSE) © 2026 XIAOXUsop

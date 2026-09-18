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
curl -LO https://github.com/XIAOXUsop/desensitize-spring-boot-starter/releases/latest/download/desensitize-spring-boot-starter-0.6.1.jar
mvn install:install-file \
  -Dfile=desensitize-spring-boot-starter-0.6.1.jar \
  -DgroupId=com.xiaoxu \
  -DartifactId=desensitize-spring-boot-starter \
  -Dversion=0.6.1 -Dpackaging=jar
```

> 安装用的是 **jar 内嵌的 POM**，它带着依赖声明——实测在只声明 starter、
> 不写任何 Spring / Jackson 的空工程里，`spring-boot-autoconfigure:3.5.13` 与
> `jackson-databind:2.21.2` 会被自动带入，宿主**不需要**手动补。
>
> （本文档此前写的是"自动生成的 POM 不含依赖声明、宿主需自带"——**那是错的**。
> `mvn install:install-file` 在 jar 内有 `META-INF/maven/**/pom.xml` 时用的就是它，
> 而不是"自动生成一份最小 POM"。2026-09-18 照本文档亲手做了一遍才发现。）
>
> 这是"还没上 Maven Central"的临时办法，不是推荐用法；正式做法见 Roadmap。

### 为什么还没上 Maven Central——以及还差什么

发布配置已经就位，缺的只有凭据：

| 项 | 状态 |
|---|---|
| groupId `com.xiaoxu` | 与 GitHub 账号对应；用 `io.github.xiaoxusop` 也可，两者都能过 namespace 校验 |
| POM 元数据 | 已补 `licenses` / `scm` / `developers`；`mvn -Prelease -Dgpg.skip=true package` 产出 `-sources.jar`（20 个源文件）与 `-javadoc.jar`（67 个页面） |
| 签名 | `maven-gpg-plugin` 挂在 `release` profile 的 `verify` 阶段 |
| 上传 | `central-publishing-maven-plugin`，`autoPublish=false`（停在 Portal 供人工确认） |
| 工作流 | `.github/workflows/publish-central.yml`，**只能手动触发**且要求手打确认词 |
| `scm.tag` | ⚠️ 内嵌 POM 里是未解析的 `v${project.version}`——Maven 不解析该位置的属性。scm 的其余字段正常，Central 校验也不涉及 tag 内容，但页面上会显示成字面量 |

差的是四个 secrets：`MAVEN_CENTRAL_USERNAME`、`MAVEN_CENTRAL_PASSWORD`（Sonatype 用户令牌）
与 `GPG_PRIVATE_KEY`、`MAVEN_GPG_PASSPHRASE`。

> 本仓库没有 Maven Wrapper，所以 `release` profile 里的 javadoc 插件刻意选了 3.5.0
> 并显式写 `<source>21</source>`——3.6+ 要求 Maven ≥ 3.6.3，而使用者用的是自己装的 Maven。
> ctxpress / amlagent 有 wrapper，不受这个限制。

**方式二：从源码构建**

```bash
mvn install         # 安装到本地仓库
```

```xml
<dependency>
    <groupId>com.xiaoxu</groupId>
    <artifactId>desensitize-spring-boot-starter</artifactId>
    <version>0.6.1</version>
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

### 把两步套在一次调用外面

手写上面两行容易漏——尤其是异常路径。`PseudonymizingChat` 把「出站脱敏 → 调用 → 入站还原
→ 异常消毒」收成一个方法，**需要显式构造**（不自动包装应用里的模型 Bean，
否则升级一个依赖就会悄悄改写别人的模型调用）：

```java
PromptRedactor redactor = PromptRedactor.scoped(pseudonymizer, vault, VaultScope.of(sessionId), types);
PseudonymizingChat chat = PseudonymizingChat.scoped(redactor, prompt -> chatModel.chat(prompt));

String reply = chat.chat("客户 110101199003078531 的交易是否可疑");   // 拿到的回复里是真实身份
```

多轮对话复用同一个实例即可：同一客户在整段会话里始终是同一个令牌。

**关于异常**：模型 SDK 常把请求内容带进异常 message。所以调用失败时这一层会
给 message 消毒（原文变成 `[REDACTED:类型]`），并且——**本次请求确实含敏感内容时**——
不保留底层堆栈。堆栈是最常被打印的东西，挂上去等于把原文放进日志。
代价是丢失底层堆栈，这是刻意付的；底层异常的类型名仍然保留，
足够区分超时、鉴权失败与别的。请求本身不含敏感内容时不做这个取舍，堆栈照常保留。

**不支持流式**：流式的令牌可能被切成两半（`ID_CARD_v2_9f2c` 与 `4a1b…`），
逐块还原要么漏、要么把半个令牌当正文吐出去。与其做一个"大部分时候对"的版本，
不如明确说不支持——需要流式就自己在完整分片边界上还原。

> 不依赖 LangChain4j：`ChatInvoker` 就是一个 `String -> String` 的函数式接口，
> 用 LangChain4j 时接一行 `prompt -> chatModel.chat(prompt)` 即可。
> 核心模块的运行时依赖仍然只有 Jackson。

### 生产实现要点

`TokenVault` 默认给的是内存实现（进程重启即丢），生产应替换为加密落库的实现：

- 映射表加密存储，且与令牌密钥**分开管理**
- 每次还原落审计日志（谁、何时、还原了哪类数据）
- 支持按时间/客户维度删除（被遗忘权）
- **同令牌不同原文必须失败**（抛 `TokenCollisionException`），不得覆盖也不得静默保留
- **按会话/租户隔离**（见下），而不是所有会话共用一张全局表
- 标注了 `@ConditionalOnMissingBean`，业务侧自定义实现会自动覆盖默认

### 会话级保险库

不指定作用域时整张「令牌 → 原文」表是**全局**的，三个后果都很实际：
会话结束想清掉自己那份映射只能清掉整张表、两个会话的令牌空间混在一起互相影响、
也没法回答"这个令牌属于哪次会话"。

```java
VaultScope session = VaultScope.of(sessionId);            // 会话结束时用它整体撤销

PromptRedactor redactor = PromptRedactor.scoped(pseudonymizer, scopedVault, session, types);
String outbound = redactor.redact(userPrompt);

scopedVault.forget(session);                              // 只清这一个会话，其他会话不受影响
```

| 能力 | 说明 |
|---|---|
| 隔离 | 不同作用域的映射表互不可见；一个会话的登记、计数、清理都不影响另一个 |
| 碰撞检测 | 按作用域独立——同一令牌在两个会话里指向不同原文是允许的 |
| 整体撤销 | `ScopedTokenVault.forget(scope)` 撤销一个会话；撤销后它的令牌无法再还原 |
| 过期 | `new InMemoryTokenVault(Duration.ofHours(2))` 让条目自然失效，令牌变成死串 |
| 审计 | `auditWith((scope, type) -> …)` 在每次成功还原时回调，**只给作用域与类型，不给原文** |

> **作用域不改变什么，别误以为它改变了。** 令牌本身仍然是**确定性**的：同一段原文
> 在不同会话里依然是同一个令牌串。这是跨轮次一致性的来源，不是缺陷。
> 作用域管的是映射表的隔离，不是让令牌变成会话内唯一。真要做到"同一客户跨会话不可关联"，
> 那要改的是密钥轮换策略。

> 内存实现终究靠进程重启兜底，TTL 只是让"忘了清"有个上限。生产实现同样应支持作用域——
> 接口已经定好了，落地时把 key 从 token 换成 scope+token 即可。

> **边界说明**：脱敏只保护"传输与推理"环节。真实值最终仍会回到应用侧，
> 因此应用侧自身的权限与审计不能被这一层替代。

## 测试

```bash
mvn test
```

95 项测试覆盖：8 种脱敏类型的算法与边界、字段注解 / getter 注解、嵌套对象与集合、
null 值、自定义占位字符；**令牌位数与版本解析（v1/v2）、跨密钥不可伪造 / 无原文残留 /
多轮一致 / 还原往返 / 未知令牌与伪造令牌不猜测**；**保险库碰撞失败**（同令牌同原文幂等、
同令牌异原文抛异常且保留原映射、异常不泄漏原文）；**会话级隔离**（作用域互不可见、
整体撤销不影响其他会话、作用域名无法被构造成撞键、过期后不可还原、并发读写一致、
审计回调不含原文）；**模型调用装饰器**（多轮令牌一致、未知令牌不猜测、异常信息消毒、
含敏感内容时不保留堆栈、不含敏感内容时保留堆栈、日志消毒不登记映射）；
以及两组 **自动配置集成测试**
（`ApplicationContextRunner` 验证真实 Spring 上下文中的装配、开关、缺密钥快速失败）。

## Roadmap

- [ ] 发布到 Maven Central（配置已就绪，缺 Sonatype 令牌与 GPG 私钥）
- [x] 还原审计回调（`RestoreAudit`，只给作用域与类型，不含原文）
- [ ] 令牌保险库的**加密落库**实现（内存实现已有作用域/过期/撤销，落库版待做）
- [x] 模型调用装饰器（`PseudonymizingChat`，零依赖，接 LangChain4j 只需一行 lambda）
- [ ] 独立的 LangChain4j 适配模块（把 `ChatInvoker` 直接实现成 `ChatModel` 包装，需另起一个 Maven 模块）
- [ ] `@Sensitive` 支持类级默认策略
- [ ] 可选的 MyBatis 查询日志脱敏

## 相关项目

本 Starter 脱胎于开源项目 [amlagent](https://github.com/XIAOXUsop/amlagent)（商业银行智能反洗钱尽调平台）的工程需求 —— 客户身份证号、姓名、交易数据在对外输出前必须脱敏。

## License

[MIT](LICENSE) © 2026 XIAOXUsop

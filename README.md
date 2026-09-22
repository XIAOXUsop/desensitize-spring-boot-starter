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
curl -LO https://github.com/XIAOXUsop/desensitize-spring-boot-starter/releases/latest/download/desensitize-spring-boot-starter.jar
mvn install:install-file \
  -Dfile=desensitize-spring-boot-starter.jar \
  -DgroupId=com.xiaoxu \
  -DartifactId=desensitize-spring-boot-starter \
  -Dversion=0.6.3 -Dpackaging=jar
```

> ✅ **从 v0.6.2 起，文件名不再带版本号**，所以上面这条 URL 不用跟着发版改了。
>
> 在此之前（v0.6.1 及更早）产物叫 `desensitize-spring-boot-starter-0.6.1.jar`，
> 而 `releases/latest/download/<文件名>` 指的是**最新那个 Release** 里的同名产物——
> 所以那种带版本号的名字会在新版本发布后**直接 404**。
> v0.6.2 起 `release.yml` 会额外挂一份不带版本号的副本，`latest` 于是稳定可用。
>
> 只有 `-Dversion=` 那一行还需要跟版本走（那是你本地仓库里的坐标，可以随便起名）。
> 对照：ctxpress 与 mcp-sentinel 的产物名本来就不带版本，所以它们没有这个问题。

> ✅ **v0.6.3 修掉了 v0.6.2 的三处问题**，下载最新版即可：
>
> | v0.6.2 的问题 | v0.6.3 |
> |---|---|
> | **常见的身份证 / 手机号 / 银行卡写法，`redact()` 一条都认不出来**：15 位老身份证、`+8613812345678`、`138-1234-5678`、`6222 0202 0011 2347`（卡面写法）全部原样发给了外部模型、原样进了日志与异常堆栈——而 `redact()` 没命中时是"原样返回"，**调用方看不到任何症状** | 三条正则都接受常见书写形态；补 15 位老身份证；`isValidLuhn` 先剥分隔符再算校验位（`fe642b1`） |
> | **应用自己定义 `ObjectMapper` Bean 时，全部脱敏静默失效**：Boot 的 `JacksonAutoConfiguration` 挂着 `@ConditionalOnMissingBean(ObjectMapper.class)`，应用一旦自建 mapper 就让它整个让位，那个 `Module` Bean 留在容器里再没人用。实测输出 `{"idCard":"110101199901011234"}`——**明文，而启动成功、无告警、日志无异常** | 新增 `BeanPostProcessor`，容器里每个 `ObjectMapper` 都会被装上模块（幂等）（`fe642b1`） |
> | `@Sensitive` 只对 `String` 生效：`Long phone` 明文落地（`{"phone":13800000000,"idCard":"110101***1234"}`——同一个类里一行生效一行不生效）；`keepFirst`/`keepLast` 给负数或极大值抛 `StringIndexOutOfBoundsException`（接口 500） | 标量（`CharSequence`/数值/字符/UUID）都脱敏；越界参数钳住（`fe642b1`） |
>
> 还包括一条**没有进任何 release 的会话隔离修复**：`5f9d90e` 之前，
> 名叫 `"session-a\u0000evil"` 的作用域会被 `forget("session-a")` 连带删掉——
> 而 v0.6.2 的 `VaultScope` 只拦 null 与 blank。那条修复此前**只存在于 master**。
>
> v0.6.2 当时修的是内嵌 POM 的 `jackson-bom`（2.21.2 → 2.21.5，清掉 5 条公告），
> 那条在 0.6.3 里没有回退。发版前实测：`mvn -o -B test` → 110 项，0 失败。

> 安装用的是 **jar 内嵌的 POM**，它带着依赖声明——实测在只声明 starter、
> 不写任何 Spring / Jackson 的空工程里，`spring-boot-autoconfigure` 与
> `jackson-databind` 会被自动带入，宿主**不需要**手动补。
>
> （本文档此前写的是"自动生成的 POM 不含依赖声明、宿主需自带"——**那是错的**。
> `mvn install:install-file` 在 jar 内有 `META-INF/maven/**/pom.xml` 时用的就是它，
> 而不是"自动生成一份最小 POM"。2026-09-18 照本文档亲手做了一遍才发现。）

> **被自动带入的那个 Jackson 是哪个版本？取决于你装的是哪一版：**
>
> | 你装的 | 解析到的 `jackson-databind` | 命中公告 |
> |---|---|---|
> | **v0.6.2 及以后** | **2.21.5** | **0 条** |
> | v0.6.1 及更早 | 2.21.2 | 下表 5 条**全部** |
>
> v0.6.1 那 5 条（2 HIGH + 3 MEDIUM，含 `PolymorphicTypeValidator` 绕过与
> `@JsonIgnore` 绕过）：
>
> | 公告 | 级别 | 修复版本 |
> |---|---|---|
> | CVE-2026-54512 / GHSA-j3rv-43j4-c7qm | HIGH | 2.21.4 |
> | CVE-2026-54513 / GHSA-rmj7-2vxq-3g9f | HIGH | 2.21.4 |
> | CVE-2026-54514 / GHSA-hgj6-7826-r7m5 | MEDIUM | 2.21.4 |
> | CVE-2026-59888 / GHSA-3pjw-73gf-8qr5 | MEDIUM | 2.21.4 |
> | CVE-2026-54515 / GHSA-5jmj-h7xm-6q6v | MEDIUM | **2.21.5** |
>
> **v0.6.2 是怎么修掉的**：内嵌 POM 里显式 import 了 `jackson-bom:2.21.5`，
> 且**排在 `spring-boot-dependencies` 前面**（多个 import 管同一坐标时先声明的赢）。
> 发版前实测过：空工程 `dependency:tree` 解析到 `jackson-databind:2.21.5`。
>
> **只升 Spring Boot 是不够的**：3.5.13 的 BOM 给 2.21.2，目前最新的 3.5.16 给 2.21.4
> —— 仍命中 CVE-2026-54515。没有任何一个 3.5.x 的 BOM 会给到 2.21.5，只能显式钉。
>
> 这个仓库自己的 Dependabot 告警是**空的**，而它并不能反驳上面这段：内嵌 POM 里
> `jackson-databind` 不写版本号，Maven 的依赖图因此记不到这个坐标，
> Dependabot 的 Maven 覆盖是**静态图**，看不到 BOM 才决定出来的版本。缺口在这里。
> ——也就是说，**上面那个 2.21.2 从来不会出现在告警里**，只能自己装一遍才看得见。
>
> 这是"还没上 Maven Central"的临时办法，不是推荐用法；正式做法见 Roadmap。

### 为什么还没上 Maven Central——以及还差什么

发布配置已经就位，缺的只有凭据：

| 项 | 状态 |
|---|---|
| groupId `com.xiaoxu` | 与 GitHub 账号对应；用 `io.github.xiaoxusop` 也可，两者都能过 namespace 校验 |
| POM 元数据 | 已补 `licenses` / `scm` / `developers`；`./mvnw -Prelease -Dgpg.skip=true package` 产出 `-sources.jar`（20 个源文件）与 `-javadoc.jar`（67 个页面） |
| 签名 | `maven-gpg-plugin` 挂在 `release` profile 的 `verify` 阶段 |
| 上传 | `central-publishing-maven-plugin`，`autoPublish=false`（停在 Portal 供人工确认） |
| 工作流 | `.github/workflows/publish-central.yml`，**只能手动触发**且要求手打确认词 |
| `scm.tag` | ⚠️ 内嵌 POM 里是未解析的 `v${project.version}`——Maven 不解析该位置的属性。scm 的其余字段正常，Central 校验也不涉及 tag 内容，但页面上会显示成字面量 |

差的是四个 secrets：`MAVEN_CENTRAL_USERNAME`、`MAVEN_CENTRAL_PASSWORD`（Sonatype 用户令牌）
与 `GPG_PRIVATE_KEY`、`MAVEN_GPG_PASSPHRASE`。

**发版前的两道闸**（`.github/workflows/release.yml`，打 tag 时跑）

打 tag 会触发构建，但**不是打了就一定发**——`scripts/release_preflight.py` 要先过两道：

| 闸 | 查什么 |
|---|---|
| 一 | 仓库里还有 `high`/`critical` 的开放依赖告警 → 拒绝发布 |
| 二 | 这个 tag 落后默认分支，且落后的提交动过 `pom.xml`/`mvnw`/`gradle` 配置 → 拒绝发布 |

对本仓库尤其实际：**Release 是使用者唯一的"下载即用"入口**（还没上 Central），
所以"发出去的那份落后于 master"的代价比别处更高。实测这个 tag（v0.6.1）落后 8 个提交，
落后的那部分动了 `pom.xml`、`mvnw`、`.mvn/wrapper/*`——也就是说那个产物和 CI 在 main 上
验过的不是同一份。

另一条同样重要的约束：**查不动不等于通过**。HTTP 401/403/404、响应不是数组、
级别字段不认识、git 历史取不到——一律拒绝发布。这些情况在日志里和"没有告警"
长得一模一样。本仓库的告警数恰好是 0，正好是那种"看起来没问题"要格外小心读数的地方。

> ⚠️ **而这条约束曾经让闸一变成"永远拒绝"**——因为它建在一个 Actions 的
> `GITHUB_TOKEN` **够不到**的接口上。2026-09-20 在 ctxpress 上实测：
> `GITHUB_TOKEN` + `security-events: read` → 403 `Resource not accessible by integration`；
> 匿名 → 401；本地 PAT → 200。**Dependabot 告警接口只认 PAT / GitHub App token**
> （`security-events` 管的是 code scanning，不是 Dependabot）。
> 一道永远查不动、于是永远拒绝发布的闸，和没有闸是一回事——它只会被绕过。
>
> 所以 workflow 现在优先用 `secrets.PREFLIGHT_TOKEN`（PAT）；没配时给闸一加
> `--alerts-optional`，降级为**大声告警后放行**。降级**只覆盖"平台不让这个 token 查"**
> （401/403/404）——脚本自己的故障（状态码文件没写出来、响应不是数组、5xx）
> **一律照样拒绝**，**读到高危告警也照样拒绝**。否则这个开关就成了"把红变绿"的后门。

> **本仓库有 Maven Wrapper**（`./mvnw`，钉住 Maven 3.9.9）。它和 `project.build.outputTimestamp`
> 一起保证发布产物可复现——CI 与本地跑的是同一套构建工具（`b6c6342` 引入）。
>
> 本文档此前写的是"本仓库没有 Maven Wrapper，所以 javadoc 插件选了 3.5.0"——**前半句是错的**。
> wrapper 是 `b6c6342` 加进来的，而那次提交没动 README，这句话就那样留下来了。
>
> 后半句的结论仍然保留，但理由要改：文档里也给了 `mvn` 的写法，而**用自己装的 Maven 的人
> 可能低于 3.6.3**（javadoc 插件 3.6+ 要求 Maven ≥ 3.6.3），所以 `release` profile 里
> 仍刻意选 3.5.0 并显式写 `<source>21</source>`。走 `./mvnw` 的话不受这个限制——
> 这个钉法是保守，不是必需。

**方式二：从源码构建**

```bash
./mvnw install       # 用 wrapper：Maven 版本与 CI、与发布时一致（推荐）
# 或
mvn install          # 用你自己装的 Maven
```

```xml
<dependency>
    <groupId>com.xiaoxu</groupId>
    <artifactId>desensitize-spring-boot-starter</artifactId>
    <version>0.6.3</version>
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
>
> **参数本身越界（负数、`Integer.MAX_VALUE`）也不会抛异常**——超出范围会被钳住。
> 这条是 2026-09-22 补的：当时只覆盖了"值不够长"，而
> `@Sensitive(type = CUSTOM, keepFirst = -1, keepLast = -1)` 会走到
> `substring(0, -1)` 抛 `StringIndexOutOfBoundsException`（接口 500），
> `Integer.MAX_VALUE` 则因为 `keepFirst + keepLast` 溢出成负数、绕过长度判断，同样抛。
>
> **注解只对标量生效**（`String` / 数值 / 字符 / UUID）。标在集合或 Map 上没有效果，
> 也不会报错——那类值不是"一个敏感标量"，要脱敏请标在元素类型上。
> `Map<String,Object>`、`ObjectNode` 这类动态载体没有可标之处，不在覆盖范围内。

## 配置

```yaml
xiaoxu:
  desensitize:
    enabled: true      # 注解式脱敏（接口返回值掩码）的开关，默认 true
    mask-char: '*'     # 占位字符，默认 '*'
    pseudonym:
      enabled: false   # 可逆假名化是**另一个开关**，默认关闭，见下
```

> ⚠️ **`xiaoxu.desensitize.enabled=false` 只关掉注解式脱敏那一半，不会关掉假名化。**
> 这一行原先注释写的是「全局开关……整个自动配置不生效」——**那是错的**：
> `PseudonymAutoConfiguration` 只看 `xiaoxu.desensitize.pseudonym.enabled`。
> 两者是并列的两个能力，各自有开关（属性元数据里一直写得很准，是 README 这里说过头了）。
>
> 这一点有实际后果：假名化缺密钥会**在启动期失败**。
> 所以「把 starter 整个关掉」如果只写前者，容器照样会因为缺密钥起不来。

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

110 项测试覆盖：8 种脱敏类型的算法与边界（含 `keepFirst` / `keepLast` 越界参数的钳制）、
字段注解 / getter 注解 / **非 String 标量** / 两处都标时的实际优先级、嵌套对象与集合、
null 值、自定义占位字符；**令牌位数与版本解析（v1/v2）、跨密钥不可伪造 / 无原文残留 /
多轮一致 / 还原往返 / 未知令牌与伪造令牌不猜测**；**保险库碰撞失败**（同令牌同原文幂等、
同令牌异原文抛异常且保留原映射、异常不泄漏原文）；**会话级隔离**（作用域互不可见、
整体撤销不影响其他会话、**作用域名无法被构造成撞键**、过期后不可还原、并发读写一致、
审计回调不含原文）；**模型调用装饰器**（多轮令牌一致、未知令牌不猜测、异常信息消毒、
含敏感内容时不保留堆栈、不含敏感内容时保留堆栈、日志消毒不登记映射）；
**提示词脱敏对常见书写形态的覆盖**（`+86` 国家码、空格/连字符分隔、4 位一组的卡面写法、
15 位老身份证）。
> **「作用域名无法被构造成撞键」这句话曾经是假的。** 保险库用「作用域名 + 控制字符
> 分隔符 + 令牌」拼内部键，而 `size` / `forget` 按前缀匹配——它原来的注释写着
> 「用不可能出现在作用域名里的分隔符」，但**那句话从来没被强制过**：`VaultScope`
> 只拦 null 与 blank。实测（2026-09-22）：名叫 `"session-a\u0000evil"` 的作用域
> 会被 `forget("session-a")` 连带删掉，`size("session-a")` 也会把它算进去。
> 影响是**跨会话的映射销毁与计数错乱**（读不到别人的原文——`original()` 是精确查键），
> 不是原文泄漏。现在 `VaultScope` 拒绝一切控制字符，把那条假设真正强制上了。
> 更值得记的是**测试也叫同一个名字**，却只覆盖了下划线歧义——真正的撞键向量
> 从没被构造过。

以及**自动配置集成测试**（`ApplicationContextRunner`）：
真实 Spring 上下文里的 bean 装配、两个开关各自的生效范围、自定义占位字符流进序列化器、
缺密钥在**启动期**失败。

> **它验到哪一步、没验到哪一步，写清楚免得被读成更多：**
> 这些用例确认的是「本 starter 的自动配置类在上下文里装出了哪些 bean、开关怎么作用」、
> 「拿到那个 `Module` 之后它确实会脱敏」，以及
> 「**应用自己定义了 `ObjectMapper` Bean 时，脱敏依然生效**」。
> 最后这条是 2026-09-22 补的，补之前它是个真窟窿，见下。
> 本项目的测试上下文里没有 `spring-web`，所以 `ObjectMapper` 一律是自己建的——
> Boot 自己那条路（`JacksonAutoConfiguration` 按类型收集 `Module` bean）仍然没有断言在跑，
> 但那条路是 Boot 的标准行为，且现在即使它让位也有兜底。
>
> **那个"悬而未决的点"结案了，结论和当时的猜测不一样。**
> 当时在最小 `ApplicationContextRunner` 里加上 `JacksonAutoConfiguration` 后拿不到
> `ObjectMapper` bean，疑心是框架问题——**不是**：`Jackson2ObjectMapperBuilder`
> 住在 `spring-web` 里，最小上下文没带它，条件自然不满足。把 `spring-web` 放上
> classpath 后 mapper 正常出现，`Module` 也确实被收了进去。
>
> **真正的窟窿在另一条路上**：Boot 那个 `ObjectMapper` 挂着
> `@ConditionalOnMissingBean(ObjectMapper.class)`——应用只要自建一个 mapper
> （加 `JavaTimeModule`、改命名策略、配 `FAIL_ON_UNKNOWN_PROPERTIES`……工程里很常见），
> Boot 就整个让位，`Module` bean 留在容器里再也没人把它注册进任何 mapper。
> 实测：修之前，自带 mapper 的应用序列化出的是 `{"idCard":"110101199901011234"}`——**明文**，
> 而**启动成功、无告警、日志无异常**。现在由 `DesensitizeAutoConfiguration` 里的
> `BeanPostProcessor` 兜住：容器里每个 `ObjectMapper` 都会被装上那个模块（幂等，已在位则跳过）。
> 回归用例是 `desensitizationStillAppliesWhenTheApplicationDefinesItsOwnObjectMapper`。

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

# NotifyGuard

ColorOS 的**通知拦截** LSPosed 模块：在通知**入队前**判定，命中即不让它入队。

<p>
  <img src="https://img.shields.io/badge/Platform-Android-green">
  <img src="https://img.shields.io/badge/Framework-LSPosed-blue">
  <img src="https://img.shields.io/badge/Scope-system__server-purple">
  <img src="https://img.shields.io/badge/License-AGPL--3.0-orange">
</p>

> ⚠️ **当前状态：开发中，尚无正式版** —— M0～M3 已完工，等真机验收。
> 想试的话去 [CI 滚动页](https://github.com/Vstory/NotifyGuard/releases/tag/ci) 拿测试包（每次 push 自动出包）。

## 它和「监听式过滤」的区别

手机上的通知过滤应用（含本仓库作者旧项目）走的是 `NotificationListenerService`：通知**已经**入队、响铃震动、出现在通知栏之后，监听器才拿到它并撤回。撤回是第二次操作，前一帧的提示音与横幅仍然发生过。

本模块的入口在 **system_server**（LSPosed 作用域 `system`），挂的是系统自己的入队漏斗：

- **主路径**：ColorOS 扩展槽 `interceptEnqueueNotificationInternal` —— 返回 `true` 就是系统自己的「不入队」语义
- **兜底路径**：`NotificationManagerService#enqueueNotificationInternal` —— 判定为拦就不调用原方法
- **ROM 自己的拦截优先**：主路径先调原方法，ROM 判拦（隐藏应用 / 企业定制 / 通知中心黑名单 / 分身 / 预加载等）就不管；本模块只处理 ROM 会放行的通知
- 运行时**只装一个**判定点，启动日志写明实际选中的槽

代价是**只做这件事**：不做 NLS 监听、不做保活、不做双路判定。目标 ROM 为 ColorOS，真机是唯一验证环境，不承诺 AOSP 兼容。

## 判定链

任一步「放行」即短路。顺序即优先级：

| # | 条件 | 结果 |
|---|---|---|
| 1 | 快照或包名为空 | 放行 `bad_args` |
| 2 | 总开关关闭 | 放行 `disabled` |
| 3 | 通知来自本模块自己 | 放行 `self_pkg` |
| 4 | 组摘要通知 | 放行 `group_summary` |
| 5 | 无文本 | 放行 `empty_text` |
| 6 | **保护类型**命中（通话 / 闹钟 / 导航 / 媒体 / 前台服务 / 会话） | 放行 `protect_*` |
| 7 | **用户规则**命中（关键词 / 正则） | **拦截** `rule:<id>` |
| 8 | 规范化后不足 4 字 | 放行 `text_too_short` |
| 9 | 命中**硬保护词** | 放行 `hard_word` |
| 10 | 白名单应用 | 放行 `whitelisted` |
| 11 | AI 开关关闭 | 放行 `ai_off` |
| 12 | 无可用模型 | 放行 `no_model` |
| 13 | 打分异常 | 放行 `ai_error` |
| 14 | **分数 ≥ 阈值** | **拦截** `ai:<分数>` |
| 15 | 否则 | 放行 `below_threshold:<分数>` |

两处顺序是刻意的：保护类型（第 6 步）排在规则之前 —— 通话 / 闹钟 / 导航被误拦的代价远大于漏一条广告；硬保护词（第 9 步）排在规则之后 —— 用户把「验证码」显式写进规则关键词时，那是明确要求拦它。

判定不设「默认拦」。每一步拿不到确定结论都落回放行：异常穿透在 system_server 里的代价是通知直接发不出来。

### 观察模式

总开关默认打开、**观察模式默认打开** —— 判定照跑、原因照出（记录里能看到每一步的 reason 与 AI 分数），只有真正拦截被压掉。先拿到真机上的分数分布与误判率，再决定是否开拦截。

## 端侧 AI

字符 1～3-gram 哈希特征 + 逻辑回归，**纯 Kotlin 实现**（无 native、无运行时依赖）：

- 归一化：小写 → 去空白、去十进制数字、去字母 `x` → 按 **UTF-16 码元**切 1～3-gram → FNV-1a 32 哈希到 2^18 桶
- 模型：`NSPM` 格式，28 字节头 + 2^18 个 int8 权重 = **262 172 字节**，随 APK 内置；CRC32 指纹
- 阈值默认 **0.8**；文本不足 4 字不进 AI 段
- 判定热路径**零 IO**：模型同步加载一次并缓存（失败也缓存），正则与特征表在配置解析阶段就绪
- 带分数的 reason（`ai:0.93` / `below_threshold:0.21`）是标定阈值的依据 —— 观察模式下没有第二个地方能看到分数分布。学习屏展开一条后给出的分数更细（记录里的 reason 只有两位小数）

## 端侧微调

对任意通知标注「标垃圾 / 标正常 / 撤销」，模型在你的设备上按你的推送环境微调，标注可精确回滚：

- 增量是**稀疏 delta**（`NSGD` 格式），叠在不可变的内置 base 之上（不链式叠加），头带 base 指纹 + 桶数
- 拟合是**确定性 SGD**（样本按 key 排序、固定轮数、输出按桶下标升序）⇒ 同样的标注必然得到同样的 delta
- 门槛：标注 ≥ 10 条且两类各 ≥ 2 条，未达门槛只攒着不动模型
- delta 先落文件、再落版本号；模块端加载失败不登记版本号并退避重试（1s / 3s / 10s）
- 换过 base 模型后，旧 delta 会被指纹校验拒掉并自动重拟合

## 记录与标注

- **按内容聚合**：同一 App 的同一条推送只占一组，显示「N 组 / 累计 M 次」与 `×N`；上限 500 组，载入即压实、超限淘汰最久未活跃者
- **权威源在模块端**：记录 `logs.json`、标注 `labels.json`，都在 `/data/misc/notifyguard/` 下模块自己的目录里 —— 卸载重装本模块 App **不会**丢（标注上限 5000 条，按标注时间裁最旧）
- **App 主动拉取**：模块端落盘，App 用广播拉取。这条改道是为了绕开 ColorOS 的「关联启动」管控（`system_server` 经 ContentProvider / startService 拉起别的进程会被拦），因此**不需要**你去系统设置里给本模块放行任何自启动
- 标注动作串行化（在途时整表置灰），**没有回执就不改本地状态** —— 显示「没生效」而不是假成功
- **命中保护类型、且该保护仍开着的条目不可标注**（标垃圾 / 标正常置灰，撤销不受限）：这类通知在保护这一步就放行了，不进规则也不进 AI，标了只是给微调喂噪音；要去掉限制就到设置 →「保护类型」里关掉对应那一类
- **学习屏**是这条链路的回看面：被拦下但还没标注的条目列成待办（误杀回溯从那里开始），已标注的可以改判或撤销，记录窗口里已经找不到的标注（孤儿标注）也列出来供撤销
- 学习屏展开某条能看到**这条文本里把分数推得最高的片段**：特征桶里只有权重、没有词表，所以只能到片段、到不了词。高亮用的权重与判定同一份；读不到已下发的微调时界面会明说按纯内置模型归因

## 保护与规则

- **六类保护**（默认全开）：通话 / 闹钟 / 导航 / 媒体（`CATEGORY_TRANSPORT`）/ 前台服务 / 会话（MessagingStyle）—— 命中即放行，优先级高于用户规则
- **硬保护词**：验证码、校验码、动态码、动态密码、安全码、登录码、短信验证、身份验证、`otp`、`verification code`、`one-time password` 等，命中即放行
- **规则**：关键词（OR = 任一命中，AND = 全部命中；忽略大小写）或正则（`find` 语义、区分大小写，需要放宽就自己写 `(?i)`），可限定到若干包名；关键词按字面匹配、不做正则语义（用户词里出现 `(`、`[` 时，正则解析会给出与预期不同的结果）
- **白名单**：白名单中的应用**只跳过 AI 段**，用户规则照常生效

> 界面入口（五屏）：**首页**给出拦截统计（模块端累计，清空记录不归零）与当前记录窗口的命中排行；**规则**屏管理关键词与白名单；**记录**屏是记录列表与就地标注；**学习**屏回看标注与判定依据（被拦未标注的待办、标注清单含孤儿、展开看「为什么判成这样」的片段高亮）；**设置**屏管总开关 / 观察模式 / AI 开关 / AI 阈值 / 六类保护开关，并给出模块状态（是否生效、走的哪条判定路径、是否熔断、本次装配明细）。

## 熔断

模块在 system_server 里，出错会连带系统服务。因此：

- 判定与写盘异常一律不放行异常、不阻断入队
- 连续异常 / SystemUI 在 30 秒内死亡超过 2 次 → 写入 `safe_mode` 标志，模块转入只记录不拦截
- 标志是**粘滞**的：不处理的话下次开机也不会装拦截；`FileObserver` 盯着它 ⇒ 删掉即免重启恢复
- **在设置屏的「模块状态」组里看得到熔断状态、触发时间与原因**，并能一键清除标志（不需要 root）
- 熔断停的是判定，不是诊断：状态通道在熔断期间照常应答（模块另挂了一条取 Context 的钩子）

## 开关与生效方式

配置走框架提供的 remote prefs，**改动即时下发到模块端并热更新**，不需要重启。

| 配置 | 界面入口 | 生效方式 |
| --- | --- | --- |
| 总开关 / 观察模式 / AI 开关 | 设置屏开关 | 拨动即下发 |
| AI 阈值 | 设置屏滑杆 | 拖动只预览，**松手才下发**（拖动途中的中间值会被当场拿去拦通知） |
| 六类保护 | 设置屏开关（默认全开） | 拨动即下发 |
| 自定义关键词规则 | 规则屏 | 开关拨动即生效；关键词改完点「保存关键词并下发」（半截词下发会被按半截词拦通知） |
| 白名单 | 规则屏 | 勾选即下发；候选取自模块端记录里出现过的包名（不必额外申请读取已安装应用），也可直接填包名 |

框架未连接时控件置灰 —— 改动无法下发，页面会直说而不是假装成功。**总开关关闭时，它的从属项（观察模式 / AI 开关 / AI 阈值）同样置灰**：判定链第 2 步已短路，这些项无处生效（观察模式的定义就是「判定照跑」，总开关关着时它不可能成立）。置灰处各给一行提示写明原因与恢复方式 —— 拨不动的开关旁边没字，用户只能猜。

## 界面语言

界面文案走 Android 字符串资源：`res/values` 是**默认（英文）**，`res/values-zh-rCN` 是**简体中文**。跟随系统语言，不需要在 App 里选。

本项目只提供英文与简体中文两套：其它中文地区（如繁体）会回落到英文。

两套键逐条对应（CI 门禁按全集比对，漏键会让那一条静默回落英文）；模块端跑在 system_server 里，它的日志与判定 `reason` 是排障用的技术串 —— 存储与回流一律是原文，记录页只在展示层另给一份人话（点开那行的判定元数据能看到解释与原始码，见 M4k 实施方案）。

## 安装

1. 安装 [LSPosed](https://github.com/LSPosed/LSPosed)（支持 libxposed API 102）
2. 安装本模块 APK
3. 在 LSPosed 中启用本模块 —— 作用域 `system` 已在 `META-INF/xposed/scope.list` 中**静态声明**，无需手动勾选
4. **重启设备**（注入发生在 system_server 启动时；LSPosed 里的「重启系统框架」等效）
5. 保持**观察模式**跑一两天，在记录页看 AI 分数分布；确认误判可接受后再关掉观察模式

生效与否看 LSPosed 日志（tag `NotifyGuard`）：应出现模块加载与 `installHooks: … ok`，并写明实际选中的判定槽。

## 下载

本模块**尚无正式版**。测试包在 [CI 滚动页](https://github.com/Vstory/NotifyGuard/releases/tag/ci)：每次 push 自动出 **debug + release** 两个包，同一把密钥签名、可互相覆盖安装。

- `debug` 包调试日志最全，排障优先用它
- `release` 包开 R8，与将来的正式版同构建类型

## 构建

```bash
git clone https://github.com/Vstory/NotifyGuard.git
cd NotifyGuard
./gradlew assembleDebug        # 或 assembleRelease
./gradlew test                 # 228 条 JVM 单测
```

- AGP 9.4.1（**内置 Kotlin**，不声明 `org.jetbrains.kotlin.android`）/ Gradle 9.7.1 / JDK 21
- `compileSdk 37` / `targetSdk 37` / `minSdk 29`（Android 10 起）
- 签名从 `local.properties` 读（`storeFile` / `storePassword` / `keyAlias` / `keyPassword`）；没配时 release 产物是未签名包

### 模型训练

内置模型与 `training/` 管线同源，两端一致由 `parity.json` 钉住（54 条样本 + 1e-6 容差，进单测）：

```bash
cd training
python3 -m venv .venv && .venv/bin/pip install numpy scipy scikit-learn
.venv/bin/python train.py      # 离线数秒，产出 model.bin 与 parity.json
```

语料为**真实手机通知导出**（不是短信语料），来源、许可与标签噪声见 [`training/samples/SOURCE.md`](training/samples/SOURCE.md)；训练配置与一致性要求见 [`training/README.md`](training/README.md)。

## 版本

- 当前版本：`v1.7.0`（versionCode 9）—— **未发布**；开发期不 bump 版本号，靠 CI 的版本后缀区分批次
- 模块 API：libxposed Modern API 102（`minApiVersion=101`、`targetApiVersion=102`）
- `minSdk 29` / `targetSdk 37` / `compileSdk 37`

## 兼容性

| 项 | 取值 |
|---|---|
| 目标 ROM | ColorOS（真机唯一验证环境，不做 AOSP 兼容承诺） |
| 模块 API | libxposed Modern API 102（`minApiVersion=101`） |
| 作用域 | `system`（只注入 system_server，不进 SystemUI 与普通应用进程） |
| 已知边界 | ROM 自己的拦截（隐藏应用 / 企业定制 / 通知中心黑名单 / 分身 / 预加载 / 异常组件等）优先于本模块，本模块不接管这部分 |

## 隐私

- APK **不申请任何权限**，不含联网代码（记录走本地文件 + 广播，配置走框架的 remote prefs）
- 通知内容与标注**只存在本机** `/data/misc/notifyguard/`，不上传任何服务器
- 卸载本模块 App 不清除记录与标注（它们在模块端）

## 许可证

[AGPL-3.0](LICENSE) —— `Copyright (c) 2026 Vstory`

内置模型的训练语料与构建依赖包含第三方组件，声明见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)。

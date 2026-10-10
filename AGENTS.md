# NotifyGuard

ColorOS 的**通知拦截** LSPosed 模块（AGPL-3.0）。入口在 system_server 的通知入队漏斗，命中即不让通知入队。包名 `io.github.vstory.notifyguard`，作用域 `system`，真机（ColorOS）是唯一验证环境，不承诺 AOSP 兼容。

本文件是 agent 指南入口，**改这个仓库之前先读完**。运作方式的细节在 [README.md](README.md)（判定链、端侧 AI、微调、记录与标注、熔断）。

## 工作规程

- **构建交给 CI，本地不跑 Gradle 构建/打包**（`assemble*` / `bundle*` 一律不出现在本地命令里）。编译、单测、产物校验由 `.github/workflows/build-ci.yml` 在 push 后跑。
- 本地自查只跑**纯脚本**：`python3 scripts/check_string_format_args.py`（秒级，字符串模板 ↔ 调用实参核对）。这是 CI 第一个门禁，先过它能省一整轮 CI。
- 看 CI：`python3 100_System/tools/github.py --repo Vstory/NotifyGuard --run latest`，失败看 `--run latest-failed --logs`。**轮询一次不超过 5 秒**，超时就报当前状态，不做多轮等待（CI 由用户自行监督）。
- 本仓库的改动**改完直接提交并推送**，不必逐次询问：`python3 100_System/tools/github.py --dir /workspace/Project/NotifyGuard --yes`。授权仅限本仓库；知识库、其它仓库、以及任何历史改写都要先展示变更再确认。
- **严禁强推、严禁 amend 或 rebase 已推送的提交**，任何仓库、任何理由都不例外。
- 提交信息：**英文、一句话**，`<type>(<scope>): <summary>`，命令式、小写开头。scope 取实际改动面：`core` / `sync` / `judge` / `ai` / `ui` / `data` / `records` / `build` / `chore(ci)` / `docs` / `test` / `i18n` / `debug`。**不写正文**，更不写分步说明 —— 根因与取舍写进代码注释。
- 同步维护知识库里的项目档（`300_Projects/io.github.vstory.notifyguard/`）：实现方案改动就同批更新对应的 `M*实施方案.md`。知识库有自己的提交规范（PKB 号 + `pkb_gate.py` 预检）且**推送前必须先问用户**，照那边的规矩来，别把本仓库的免询问授权带过去。

## 项目形状

一份 Kotlin 源码，**两个进程各跑一半**：

| 侧 | 载体 | 里跑什么 |
|---|---|---|
| 模块端 | system_server 注入进程（[MainHook.kt](app/src/main/java/io/github/vstory/notifyguard/MainHook.kt) 起） | `judge/` 判定、`ai/` 打分、`data/` 落盘、`sync/*Sink` 应答 |
| App 端 | 普通应用进程（Compose） | `ui/` 五屏、`sync/*Client` 广播、`sync/DeltaFitter` 拟合 |

两侧靠**四条广播通道**（配置 / 标注 / 记录 / 状态）+ **两个 remote file**（配置镜像 `config.json`、微调量 `NSGD`）沟通。权威源在模块端：`/data/misc/notifyguard/` 下 `logs.json`（记录）与 `labels.json`（标注）。App 只拉取、只请求，不自己存权威副本 —— 所以卸载重装 App 不丢数据。

三条容易记错的因果关系：

- **配置热重载不能靠 prefs**：core 里注入进程的 remote prefs 是构造时的内存快照（`computeIfAbsent`），重读拿到的还是同一个对象；push 又只投给 daemon 侧「当前加载的模块服务实例」，模块 apk 一更新，老进程注册的回调就再也收不到。所以走的是「App 写镜像文件 + 广播唤醒 + 模块端重读镜像」，push 只是快路径。
- **热重载靠代际仲裁退场**：新代装配成功后发布新代际号（`core/Generation.kt`），旧代在判定入口、四条通道的 onReceive、标志监听、周期落盘上读到过期即自行释放（`core/ModuleTeardown.kt`）并转纯放行。新增任何常驻资源（线程池 / receiver / FileObserver）都必须给它一个 `release()` 并接进退场清单，否则每次热重载净滞留一份（线程是 GC root，会钉住旧 ClassLoader 与那份模型）。
- **换代的顺序是安全边界**：**先装新 hook、装上了才 unhook 旧句柄并发布新代际号**。反过来一旦新代装失败就是模块停摆；判定入口的代际校验是第二道保险 —— 即便 unhook 失败，过期代也只放行、不记录。
- **热重载的两个前提**：装上带仲裁的新包后**第一次热重载必定失败**（框架问的是正在跑的那一代），必须重启一次 system_server；新代接线（重取 Context、注册四条通道）发生在**下一次通知入队**时。
- **判定链顺序即优先级**，每一步「放行」都短路（表见 README）。改顺序是行为改动，不是重构：保护类型必须早于规则（误杀通话/闹钟/导航的代价远大于漏广告），硬保护词必须晚于规则（用户把它写进规则就是明确要拦）。**判定不设默认拦**，任何一步拿不到结论都落回放行 —— 在 system_server 里抛异常的代价是通知直接发不出来。

## 代码地图

| 包 | 职责 |
|---|---|
| `core/` | 注入入口与运行环境：`MainHook`、`EntryHook`、`ServiceContext`、`CrashGuard`（熔断）、`ModuleStatus`（装配明细）、`ModuleLogger` / `AppLogger`（两端日志） |
| `judge/` | 判定链与数据模型：`Judge`、`RuleMatcher`、`ProtectGuard`、`NotifySnapshot`、`SpamFeatures`、`LogRecord` / `LabelRecord`、`RecordSink`、`ConfigModel` |
| `ai/` | 打分与拟合：`SpamModel`（base）、`SpamDelta`（`NSGD`）、`TunedScorer`、`SpamTuner`（确定性 SGD）、`SpamAttribution`（片段归因）、`DeltaHolder`（模块端加载）、`DeltaStamp`（微调版本标识） |
| `data/` | 模块端落盘：`ModuleDir`、`LogStore`、`LabelStore` |
| `sync/` | 跨进程：`*Contract`（action/extra 常量）、`ChannelAccess`（调用方校验）、`*Sink`（模块端）/ `*Client`（App 侧）、`*Codec`、`ConfigReader` / `ConfigWriter`、`DeltaFitter` / `DeltaWriter` |
| `ui/` | Compose：`MainActivity`、`Destination`、五屏 `ui/screen/*`、`UiText`（文案载体）、`ReasonInfo`（reason → 人话） |
| `training/` | 离线训练 base 模型（纯 Python，产物进 `app/src/main/resources/model/` 与 `app/src/test/resources/model/`） |

## 版本与日志

- **`versionName` 是版本唯一来源**，构建期把提交短号并进去：CI 出厂是 `1.7.0+ci-debug.b4efc10a`（渠道段由 CI 传的 `-PciVersionSuffix` 给），本地构建补成 `1.7.0+b4efc10a`（工作区**已跟踪**文件有改动时追加 `.dirty`）。一律是 semver 的构建元数据段：**一个 `+`、点分标识符**。不要再另立第二个来源（短号出现两次就会拼出两个 `+` 的串，任何工具都解析不了）。
- 脏判定只看已跟踪文件（`--untracked-files=no`）：CI 打包前会把签名配置追加进未跟踪的 `local.properties`，按全量口径 CI 产物会带一个假 `.dirty`。
- **模块端每条日志都带 `[<versionName>]` 前缀**，由 `ModuleLogger` 统一加，不在调用点各写一遍；App 端同口径（`AppLogger`）。注入瞬间的横幅是全量信息行（api / 框架 / 是否 system_server / 进程名）。
- 模块端日志**必须走 `XposedInterface.log`**（LSPosed 日志页不读 logcat）。调试级别用 `ModuleLogger.debugRaw("[DBG] …")`，并用 `BuildConfig.DEBUG` 闸住。
- 配置每次上线都要写来源：`配置生效（startup|push|file/broadcast|file/check）`。这一行回答的是「热重载走的哪条通道、是不是已经降级成兜底」。
- **微调版本标识 = `<下发时刻 ISO 8601 UTC>+<delta 文件字节 SHA-256 前 4 字节>`**（如 `2026-10-10T08:41:33Z+3f9a1c2b`），实现只有 `DeltaStamp` 一份，模块端日志与 App 侧界面显示的必须是同一串。摘要是「同毫秒重发」「版本号没变而文件被重写」的唯一判据。

## 文案与界面

- 用户可见文案全在资源里：`res/values`（英文，**默认**）+ `res/values-zh-rCN`（简体），跟随系统语言。两套键逐条对应，缺键会让那条静默回落英文。
- **ViewModel 只产出 `UiText`（资源号 + 参数），不拼成品文本**：拼好的是某一语言、某一字号下的一份快照，重组时不会重算。参数里可以嵌 `UiText`（外层模板套内层片段）。
- 资源占位符与调用实参的**个数和类型都要对得上**：`stringResource(id, vararg Any)` 与 `getString(id, Object...)` 在编译期与单测里都不检查，错配只在真机点进那一屏时炸。这就是 `scripts/check_string_format_args.py` 存在的理由 —— 改完文案或调用点跑一次。
- 模块端产出的 `reason`、日志、技术串**存原文**，人话只在展示层给（`ui/ReasonInfo.kt`）。别在数据层做翻译。
- 拨不动的控件旁边必须有一行说明原因与恢复方式。这条在本仓是硬要求（总开关关闭时其从属项全部置灰并各给一句提示）。

## 跨进程（最容易静默出错的一节）

- 新增广播通道：入口一律先用 `ChannelAccess.isFromApp` 校验（按 appId 取模比对，不是 uid），再处理；通道常量进 `*Contract`；模块端 `*Sink` 注册进 `ServiceContext`，App 侧 `*Client` 在 `MainActivity` 里同步状态。**动作本身无害不代表可以省掉校验** —— 通道纪律不一致，迟早会被照抄到有写动作的通道上。
- `openRemoteFile` 两端**不对称**：App 侧（`XposedService`）文件不存在则创建，模块侧（`XposedInterface`）只读、缺失即抛。
- 因此「**文件先落地、版本号后写**」不是优化而是正确性：反过来模块端会登记新版本号却读不到文件，从此不再重试。改 `DeltaFitter` / `DeltaWriter` / `DeltaHolder` 的顺序即是引入 bug。
- 覆盖写 remote file **必须 `truncate(0)`**：fd 指向已存在的同名文件，不截断会留上一次的尾部字节，被解析成「项数巨大」而拒。
- 广播**只带唤醒、不带内容**（内容走私有目录里的镜像文件）：隐式广播同机应用可监听，不带载荷就没有额外暴露面；伪造广播最多让模块端白读一次文件。
- 广播触发的那次核对**跳过节流**（`force = true`）：节流是给「顺手核对」设的，App 刚说完「我写完了」不该被上一次核对挡回去。
- 诊断留痕：镜像读不到等降级路径必须留一行 ERROR 说明「只能靠什么、需要做什么」。过去这类通道断得无声无息，排障只能靠猜。

## 判定与打分

- 判定热路径**零 IO**：模型同步加载一次并缓存（失败也缓存），正则与特征表在配置解析阶段就绪。
- 阈值默认 0.8；文本不足 4 字不进 AI 段；带分数的 reason（`ai:0.93` / `below_threshold:0.21`）是标定阈值的依据，**不要为了好看截断或改写它**。
- 微调是**稀疏 delta 叠在不可变的 base 上**（不链式叠加），头带 base 指纹与桶数；换过 base 后旧 delta 被指纹拒掉并自动重拟合。
- 拟合必须**确定性**（样本按 key 排序、固定轮数、输出按桶下标升序）⇒ 同标注必得同 delta。迭代顺序不许依赖 Map 实现。
- **样本门槛保留**（总数 ≥ 10 且两类各 ≥ 2，判据 J8）：单类样本会把阈值拉爆，比不学更糟。界面上的「重发微调」按钮**不绕过门槛**（它回答的是「该不该再发一次」，门槛回答的是「这份数据配不配一次下发」）。
- **拟合时间与下发时间是两个字段**：`version` 本体是下发时刻、`fittedAt` 是拟合完成时刻（App 私有 prefs）。有了跳过下发（下一条），只有前者在真的推送时才动。
- **下发前必须比对内容**：读回 remote file（`DeltaWriter.read`）比摘要，一致就跳过 —— 拟合是确定性的，摘要相同 ⇔ 模块端已持有这份，再推只是推进版本号让模块端重读同一份文件。判据取**读回的文件**而非状态回传（状态说的是「上次加载成功」，文件才是模块端下次加载要用的那份）；换了 base 时读回会因指纹不符而失败 ⇒ 照旧下发。
- **一次拟合有三种结局**（`DeltaFitter.Delivery`）：`SENT` 真下发、`SKIPPED` 内容一致未下发、`RELOADED` 内容一致但按用户要求推进版本号让模块端重读（**不重写文件** —— 模块端认的是版本号变化）。界面上的「重发微调」短按走重算（可能落到 SKIPPED），**长按**走 RELOADED。
- 熔断：连续异常或 SystemUI 在 30 秒内死亡 > 2 次 ⇒ 写 `safe_mode` 粘滞标志，转入只记录不拦截；标志被 `FileObserver` 盯着，删掉即免重启恢复。**熔断停的是判定，不是诊断** —— 状态通道在熔断期间照常应答。
- 判据编号（J4 / J8 / J10 等）出自知识库 `200_Knowledge/dev-guide/how-to/AI标注与数据落盘设计.md`。注释里引用判据时保持编号与原文一致，别凭记忆改写。

## 注释纪律

注释是本仓库的资产，密度高是刻意的 —— 多处注释删掉不会编译报错，但会静默出错。

- **只写代码里看不出来的**：踩过的坑、判据编号、时序/并发约束、为什么不能换成另一种写法。
- **不写**：复述下一行、字段名解释、分步讲解、文件头、格式性 KDoc。
- 删一条注释前先判断它是不是某条不变式的唯一记录；是就别删。
- 每段 KDoc 回答「这条纪律防的是什么」，不回答「这段代码做了什么」。

## 测试

- 位置与被测包同构：`app/src/test/java/io/github/vstory/notifyguard/<包>/<Subject>Test.kt`。JVM 单测（JUnit 4 + 真 `org.json`），由 CI 跑 `:app:testDebugUnitTest`。
- **只测能测的**：纯函数、编解码往返、状态机、筛选与文案映射。Compose 不测 —— 因此逻辑要主动抽成 ViewModel 的纯函数或 `FitLine` 那样的无 Compose 渲染函数（「事实 → 资源号 + 参数」这一段必须可测）。
- **时间断言只判格式**（CI 跑在 UTC、本机在 +08:00，写死时刻会让其中一个环境必红）；只有时区已钉死（`DeltaStamp` 用 UTC）才可以断言精确值。
- 测试钩子：`internal fun resetForTest()`、可替换的 `ConfigReader.readRemoteFile`、`FakeIface` / `FakePrefs`。新增全局状态时补上 `resetForTest` 并让 `@Before` 调用。
- `SpamModelParityTest` 是**训练侧与推理侧一致性的唯一证据**（判据 J10）：改任何一侧的特征实现或重训 base，都要重生成 `parity.json` 并让这条过。
- 断言失败时的消息写清「期望什么、实际是什么」，用中文，和项目其余文字一致。

## 常见任务

| 任务 | 落点 |
|---|---|
| 加一个配置项 | `ConfigModel` → `ConfigCodec` → `ConfigReader` 应用 → `SettingsViewModel` + 屏 → 双语字符串。字段**只增不减**，缺省值要保证老包可读 |
| 加一条广播通道 | `*Contract` 常量 → 模块端 `*Sink`（`ChannelAccess` 校验）→ App 侧 `*Client` → 注册进 `ServiceContext` / `MainActivity` |
| 加一条界面文案 | 双语同改，键逐条对应；改完跑 `scripts/check_string_format_args.py` |
| 改判定/规则行为 | 先想清优先级与短路理由（README 表），改 `Judge` + 补 `JudgeTest` |
| 重训 base 模型 | `cd training && .venv/bin/python train.py` → 换 `model.bin` + `parity.json` → `SpamModelParityTest` |
| 改微调拟合或下发 | 注意文件/版本号顺序、确定性、门槛；`DeltaFitterTest` 与 `SpamTunerTest` 覆盖 |
| 改 CI | 产物名有三处引用（重命名 / 正文表格 / 清理正则），`PREFIX` 与发版工作流同源，改一处必须同改 |

## 依赖与构建配置

- Kotlin 由 AGP 9 内置（不加独立 kotlin 插件）。UI 只走官方 Material 3（compose 版本由 BOM 统一，不自定版本号）+ `material3-adaptive-navigation-suite`（一套代码自适应底栏/侧栏，不自写两套导航）。
- libxposed 三个本地 jar：`api.jar` 是 `compileOnly`（**不进运行时**），`interface.jar` / `service.jar` 是 `implementation`。单测需要 `api.jar` 显式补 `testImplementation`，否则造不出假实现。
- `compileSdk` / `targetSdk` / `buildToolsVersion` 都钉死：不写 `buildToolsVersion` 时 AGP 会挑自己的默认版本，本机与 CI 只装了 37.0.0 时会直接报「Failed to find Build Tools revision」。
- release 开 `isMinifyEnabled`（配 `proguard-rules.pro`）；`buildFeatures { compose; buildConfig }` 都开着 —— 日志前缀、DEBUG 闸、变体判断都依赖 `BuildConfig`。
- 新增依赖前先自问能否不加（本模块跑在 system_server 里，体积与启动代价都放大）。

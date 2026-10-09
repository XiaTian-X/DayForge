# 测试规范

## Android 测试执行约定（2026-09-20 用户决策）

后续所有 Android 测试统一使用已连接并授权的真机。不得下载、创建或运行模拟器、AVD 或系统镜像；
不得以 JVM/Robolectric 执行替代真机测试。没有可用真机时停止 Android 测试执行，明确记录未验证。
构建、静态审查以及此前的本地单测结果均不代表当前真机测试通过。

真机入口为 `ANDROID_SERIAL=<设备序列号> ./tools/verify android`，默认要求唯一已授权真机；
`adb` 的未连接、未授权、多设备歧义及 QEMU/AVD 均导致失败。使用 `deviceTest` 构建和
`com.dayforge.testbed` 包，执行本批 instrumentation 测试并输出覆盖率，结束后测试工具卸载测试包。
`./tools/verify all` 也要求真机。构建与测试均沿用锁定依赖，不下载系统镜像。

无真机的托管 CI 仅执行 `./tools/verify android-build`（编译、lint、APK 和编译警告门禁），
明确输出“真机测试未执行”；CI 绿色不代表 Android 行为验收。PR 必须记录当前变更的验证范围、
结果和未执行项；涉及 Android 行为时附对应真机结果，纯文档/工具配置变更不强制全量真机测试。
历史 `src/test` 测试已全部审查并迁入 `src/androidTest`，旧 JVM/Robolectric 依赖、资源映射和变体已移除。
构建配置拒绝 `src/test*` 中的 Kotlin/Java 源文件，防止新增测试落入无人执行的目录；所有 Android 用例仍由真机入口运行。

## 验证层级

### 配置导出的账户素材依赖与真实文件链（2026-10-09，Issue #160）

新增 AccountConfigExport、账户素材库的一次事务依赖快照及控制器内部准备/短发布接口。
范围是素材所有权、当前角色映射/固定引用、真实私有文件与冻结 v2 输出，不读取业务数据库，
不改变现有设置页、schema、HTTP、后端、依赖、默认 v4 或 UI。

初次 `353b6f63e3ff486a82c8046483911f80` 在构建期间主动取消：审查发现两个新夹具缩小
对象范围后保留未使用 unresolved 角色，不符合原契约。取消拥有的 Python 进程、等待
wrapper/实际子进程和两个 testbed 包退出后才合批修正；未执行行为，不作为成功证据。
最终完整五类 AccountConfigExportTest、AccountIconRepositoryTest、AccountIconStoreTest、
AccountIconSelectionTest、ConfigBundleOutputTest 共90/90 passed、0 failures/errors/skipped，
证据 `e4f66c93ef934112927cb200eff06d41/targeted.json`，build2m02s、connected2m09s。
root51、构建/lint/警告、APK/发现/XML/新覆盖输入与两个测试包清理门禁通过。

11 项新增用例使用真实 Room/DataStore、认证/设备/副本、选包和安装收据、Android 私有文件及
PNG/SVG/ZIP 校验；只在真实系统读调用处加计数或有界暂停，不用模拟仓库替代所有权证明。
验证固定素材来自选包之外、精确使用闭包/明暗字节、hash 去重但每个资产身份都须授权、
缺角色不读取占位图、未就绪/丢失/损坏拒绝、事项用途与清单变化拒绝、不可变快照及预算，
账户/重新认证/设备/权限/副本的晚到结果、只读离线导出/同代次刷新、实际读期间登录或选包
交错、取消等待真实读与释放租约；持久素材表不变、冷重开回读一致。没有网络请求或自动下载。
没有新增最大完整 ZIP/128 素材端到端容量证明，不重复未变长计时/容量或全量矩阵。

日常定向结果不是 Android 全量、后端 SQL、正式 Hilt 设置页/系统提供者/桌面/平板或人工验收。
业务 Room 快照、当前事项默认筛选/显式模板、稳定新身份与安装/替换日志、恢复/因果同步、
v5/Worker/素材/图标/六组件联合及全量验收仍待完成。150s/1500s 不改；无正式 APK 安装、
清库、部署、Docker 或自动合并，不宣称 #160 完成。

### 新版配置的映射、归档生成与文件传输（2026-10-09，Issue #160）

合批实现 NextConfigMapper → ConfigBundleOutput → ConfigBundleDocuments，复用完整只读 v2
输入校验；无账户/Room/协议/迁移或当前设置页改动。旧 JSON 工作流尚未替换，内部文件链
不能当作正式配置功能或联合启用完成。

初次 c9a6f209782148b39ab72299dffb822b 在测试编译发现缺失序列化扩展导入，主动取消拥有的
wrapper 并 join；4b85462ad572445ca78eebb6d0683e28 构建通过，但发现阶段因新表达式用例
非 Unit 返回而失败，均未执行行为。修正后完整七类 aba9d68c6fb3480fb3c6e709ee6311c2
执行 66 项：64 passed、2 failed、0 errors/skipped（build1m27s、connected54s/XML25.74s）。
两个失败均为映射夹具：溢出的精确异常应为 ArithmeticException，完成事项的完成事件必须
等于头事件。生产源码不改；完整映射类复测 9a49440ef9e347ff88b6e560c6bd2f56 为5/6，
发现测试辅助方法对 null 外观提前 !!，未让该错误真正进入生产验证；改为只为非 null 引用
收集角色，保留生产失败断言。最终完整 NextConfigMapperTest 六项6/6，0失败/跳过，
证据 c387bf514af84b0cb73338492321fb76/targeted.json（build16s、connected23s/XML2.595s），
构建/lint/警告、发现/APK/XML/
新覆盖输入及测试包清理通过。先前六个未改完整类60项通过；不拼为66项最终完整运行或全量。

实际真机原生验证清单全部字段、秒/分钟转换、IANA/日锚点、父子/UUID/主键一致性、三种
指标聚合、已完成事项显式模板选择、旧/无法表达结构拒绝与历史排除。共享原始 fixture 和
平台 ZIP reader/writer 检查完整明暗 PNG/SVG 字节、去重闭包、深度不可变及重放；变更/损坏
元数据在读取字节前拒绝，真实图片错误、原 I/O 和取消不返回部分包。8/32 MiB 输出缓冲的
等于/超限为实际分配写入检查，不称完整最大图标包端到端容量证明。
实际文件 section/描述符/pipe 验证 offset/length、一次打开、来源后变与冻结写出、截短/无效
内容、读取消/超时、阻塞写超时及关闭故障；边界使用函数提供真实 FD，不冒充完整系统选择器。
原完整 ConfigBundleArchiveTest、ThemeDocumentsTest、NextStructureMapperTest 和
ThemeConfigContractTest 相邻矩阵保留。没有改150s/1500s或已有断言，不重跑未变长计时/容量。

账户一致快照、待同步事项筛选、完整素材授权、稳定新身份/替换事务/恢复和因果同步、正式
设置页与v5/Worker联合、Android/后端SQL全量、#344以及人工/平板/外网/NAS仍未完成。
默认v4，不合并、安装正式APK、清库、部署或操作Docker。

### 小组件 provider 与遗弃配置入口清理（2026-10-09，Issue #160）

核对全部 provider XML、Manifest 和调用方：专注从未链接 FocusWidgetConfigActivity，
没有其他实际调用方。删除该源文件及独立导出声明，不新增替代配置流程；保留六类 Receiver、
三类选择配置、自动排序/时间窗口、刷新队列、界面与周期配置。

初次 `b4c102b1404241e585a5a9be0937bca4` 在构建阶段主动取消：新增元数据夹具错误地
假定全部周期相同，核对实际 XML 后按专注30分钟/其余24小时修正；未执行行为、不改产品周期。
取消拥有的 Python 包装进程并 join，确认子进程及两个 testbed 包不存在后才改夹具。
最终独立完整 WidgetEntryMetadataTest、WidgetRefresherTargetsTest、WidgetRefreshSchedulerTest、
WidgetRefreshWorkerTest、FocusWindowReceiverTest、WidgetSummaryReceiverTest 6 类15/15 passed，
0 failures/errors/skipped；证据 `dac23a1ac00c423f817dc693a3af934f/targeted.json`。
root51/build1m4s/lint/告警、APK/发现/XML/新覆盖输入与 testbed 清理通过。

元数据测试读取真机已安装 testbed 的真实 AppWidgetManager/PackageManager：六类 provider
与更新 Receiver 均存在/启用，三类选择配置组件可用，三个自动组件 configure 为空，原刷新周期
与加载布局有效；已移除的旧组件不存在，配置 intent 只解析三类真实选择页。不用源文本或
mock provider 替代此证明。相邻矩阵继续验证六类加载/显示次序与专注闹钟路由、无绑定实例、
失败隔离、空载荷 APPEND_OR_REPLACE、有界重试及窗口广播路由；这些测试对平台/消费者边界
有明确隔离，不证明真实已绑定桌面绘制。生产来源、账户权限、业务事实或 outbox 没有改动。

不重复未变的配置 UI/主题/长计时/容量矩阵，不称 Android 全量或正式联合验收。
默认 v4；150s/1500s 不变，无 schema/HTTP/后端/依赖/布局变化。实际桌面多实例/像素、
账户素材/图标消费者、持久绑定与进程死亡恢复、正式 v5/Worker/配置联合、Android/后端 SQL
全量（含 #344）、人工/平板/外网/NAS仍未完成；无正式 APK 安装、清库、部署或 Docker 操作。

### 小组件独立页面的完整主题宿主（2026-10-09，Issue #160）

三类真实配置入口和目标/指标/重新激活/计时切换/放弃五类弹窗使用共享完整主题，保留
原有字号、布局和操作。普通配置窗口复用显式 DayForgeWindow 的背景/系统栏/safeDrawing，
透明桥接不绘制全屏背景或修改 Activity 系统栏。配置卡片复用原 CardColorResolver。
指标与计时切换初始化 effect 在主题显示门禁之外，不因主题重试重新抓取状态。
失败恢复 UI 不调用业务确认、放弃或录入回调，不伪造可用默认主题。

首轮 `dc48ec65ce0f46b3b0fc5e4cbee1c563` 完整 9 类实际 54 项为 53 passed/1 failed，
0 errors/skipped，connected8m12s/XML466.972s，build2m2s/lint/告警通过。唯一失败为新增
实际配置颜色用例：已提交控制器快照不等于 Activity collector/绘制帧已消费，直接采样得到
先前浅色。其余完整类包括 WidgetFactEntryTest、CardColorResolverTest、TimerServicePersistenceTest、
ThemePresentationTest、DayForgeWindowTest、ThemeRecoveryPresentationTest、DeviceWidgetThemeTest、
WidgetActivityThemeTest；真实目标确认及计时切换/放弃和原完整一分钟计时断言实际执行通过。
首轮整批不是通过，原 XML 由下一轮工具旋转保留在 prior-results。

确认测试注入与生产入口是同一个主题控制器，等待实际卡片呈现预期颜色，再保留全部背景、
文字像素和整库/outbox/绑定不变断言；未使用固定休眠或扩大原 5s 等待。生产行为未因该失败
更改，只整理两处 effect 的缩进。最终独立完整 WidgetConfigurationEntryTest、WidgetActivityThemeTest、
ThemePresentationTest 3 类 12/12 passed，0 failures/errors/skipped，connected1m26s/XML60.352s；
证据 `0941db88a34e45b4ba301c0986c74188/targeted.json`。root51/build1m29s/lint/告警、
APK/实际发现/XML/新覆盖输入与两个 testbed 清理通过；wrapper/Gradle 及测试包退出后再编辑文档。
不重复未变的长计时/容量矩阵，不拼接为 54 项最终整批成功或 Android 全量。

新共享宿主使用真实临时目录/DataStore/主题文件/进程控制器及 Compose，验证冷加载浅色/OLED、
SYSTEM 明暗与卡片样式、字号保留、Loading/Failed 无正常内容、私有错误脱敏和明确恢复动作，
以及透明宿主窗口 flags/decor 背景不被修改。三类真实配置 Activity 验证 OLED/两种卡片样式及
文字像素、真实损坏保存选择后的阻止操作和明确修复；同时保留已有配置成功/重复点击/失败
重试/账户切换/取消回滚。仅隔离 launcher 发现/ID 解析/host 提交，不替换真实业务或权限。

透明宿主 flags/decor 断言不证明已安装桌面像素；五类弹窗未全部新增端到端交互用例，
指标/重新激活业务成熟度、Focus 无 UI 配置生命周期、进程死亡绑定恢复、图标素材消费者、
正式 v5/Worker/配置联合、Android/后端 SQL 全量（含 #344）和人工/平板/外网/NAS仍未完成。
默认 v4；150s/1500s 不变，无 schema/HTTP/后端/依赖/主界面布局变化，无正式 APK 安装、
清库、部署或 Docker 操作。相关 Android 系统栏依据为
[官方 Compose 系统栏文档](https://developer.android.com/develop/ui/compose/system/system-bars)。

### 三类小组件配置入口与账户绑定租约（2026-10-09，Issue #332）

保留原 Activity/Manifest 入口、类型过滤、标题/目标值说明、列表/卡片布局和配置 Toast，
移除三类 UI 的直接 DAO、脱离生命周期协程及重复配置实现，统一 ViewModel/工作流/仓库。
读取前捕获账户范围；首次旧结果/错误不能借重试进入替换账户。绑定提交前复核 UUID/类型
和当前已配置实例，检查 commit Boolean；原范围叠加本次内存租约后发布，typed 计时
保留 writer 强锁。同账户后来的相同值配置也使旧发布/回滚失效。失败/取消条件撤回自身
绑定并安排当前数据恢复，不写事实/来源/outbox；失败不返回 RESULT_OK，显式重试重读选择。

`987fe3ac710048fa907d4e7816559d3f` 在构建中主动取消：发现两个测试清单类名不正确及
新参数注解作用位置告警；join/确认子进程和测试包不存在后合批更正类名与 @param: 目标。
`48c635d47dfe4d4b865600fc9d38aa54` build1m41s 成功，但新 SQL 夹具 arrayOf 交叉类型
告警被预算拒绝，未执行行为；显式 arrayOf<Any?> 修正，未扩大告警预算。
`bbac3614bc224de0a3f26278bb8ed3c4` 完整 14 类实际 67 项为 65 passed/2 failed，
0 errors/skipped，connected4m45s/XML259.191s。两项为新增夹具：直接 spy Android 的
SharedPreferencesImpl 导致内部锁为空，以及使用了错误的 habit_name 键；不删除断言。
其他完整类包含计数轮次、显示发布、实际事实/生产入口、计时生命周期/强发布/读取/tick、
打卡状态、刷新路由和清理恢复。此轮不是完整通过；原 XML 按工具旋转保留于下一轮 prior-results。

改为委托真实 SharedPreferences/Editor 的接口包装：实际原 commit 后仅首个结果注入 false，
第二次真实恢复提交，检查原值及真实整库证明。显示断言使用各组件自身的 HABIT_NAME_KEY。
同时补首次加载旧来源错误的范围复核和真实 DAO 读取后的账户交错用例；无共享发布/计时
读取器变更。最终独立完整 WidgetConfigurationRepositoryTest、WidgetConfigurationEntryTest、
CheckInWidgetConfigActivityTest、WidgetDisplayPublicationTest、ProductionWorkflowInjectionTest
5 类 24/24 passed，0 failures/errors/skipped，connected2m27s/XML123.353s；证据
`1197691699c04657a611c36bcc55c580/targeted.json`。root51/build1m53s/lint/告警、实际
发现/APK/XML/新覆盖输入及 testbed 清理通过；wrapper/Gradle 与两个测试包退出后才编辑文档。

实际 Hilt/Room/令牌/mutex/绑定偏好/Glance/配置 Activity/ViewModel/Compose 读取与点击；
三类完成和重复点击，host 失败后的显式重读/重试，账户变化关闭及页面关闭后的结构化
取消/回滚均由真实生产入口验证，等待并 join ViewModel scope 后收尾。仅隔离 launcher
发现、Glance ID 解析和 host 提交；持久化故障仅注入原提交返回值，不伪造本地业务权限。
仓库验证来源晚到/旧错误、重复绑定复核、ID/UUID 替换、同值 ABA、取消与回滚的整库/outbox
不变。不把合成 Glance ID 称为已安装 host，不将两轮拼接为最终 68 项或 Android 全量。

配置旋转依赖 ViewModel 归属，但本轮未新增旋转/进程死亡恢复或实际磁盘失败验收，不能
以页面关闭证明这些场景。绑定持久归属/跨进程日志、真实 launcher 多实例/像素、正式 v5/
Worker/素材/配置联合、Android/后端 SQL 全量与人工/外网/NAS仍未完成，#344 未闭合。
默认 v4，150s/1500s 不变；无 schema/HTTP/后端/依赖/既有手机平板布局变更，无正式 APK
安装/清库/部署/Docker 操作；未重跑未变容量长链或一分钟服务。

### 计时显示、专注闹钟与账户清理生命周期（2026-10-09，Issue #332）

Timer 在来源读取前捕获原账户显示范围，typed 正常发布在既有 writer 强锁内复核；
真实竞态发现未运行旧习惯可借新账户生成开始声明，现已拒绝。删除/v4/来源错误及 tick
错误使用原范围发布，删除/错误清除全部动作和 tick 标记。Focus 来源准备不重入发布锁，
平台安排/取消复用原显示范围；无窗口仍保留具有有效原来源的活动计时分钟刷新。
清理按类型/实例隔离 host 故障、传播取消并请求当前数据恢复；实际未绑定 Glance 状态先清空。
目标确认由 Activity 生命周期在 IO 读取、Main 发布完整显示，Compose 不再拥有 Room 读取。

初轮 `92b2def165d94b63956081d0d046e58e` 缺 CancellationException 导入、
`9de109b440f64130b5da9495929021bb` 的 UseKtx lint 均止于构建，未执行行为。
后者保留平台 commit Boolean 检查，使用局部说明的 SuppressLint，不扩大告警预算。
`857d9265569f485c9b6f469b6486cd11` 17 类 65 项为 46 passed/19 failed，
其中新增夹具 mock 对象恢复旧已关闭 provider 引发串联失败；改用既有真实 setInstanceForTesting
并在规则关库前还原。另一个目标确认用例暴露 Compose/Room 续体线程问题，未忽略。
`a12c0cf174084409b7680b5b60556417` 13 类 54 项为 51 passed/3 failed；
`4b01c25a5a474131a98866f132d7b5e8` 4 类 16 项为 14 passed/2 failed；
修正新夹具旧对象 completionPolicy 和重名唯一约束，不放宽业务规则。
`265b1c5b84db4e4ba2ac263460bed442` 2 类 9 项为 7 passed/2 failed，包含上述
真实未运行计时账户竞态和目标确认；追加原范围复核，IO 移至 Compose effect 内仍不足。

`3a87147a5e6f43f2bb1ea3bfa5044a09` 完整 13 类 54 项为 53 passed/1 failed，
connected3m56s；TimerLifecycle、AlarmLifecycle、CleanupRecovery、DisplayPublication、
TimerPublication、TimerTickRead、TimerRead、TimerAction、SummaryEntry、RefresherTargets、
AccountLocalStateCleaner、ProductionWorkflowInjection 12 类通过，唯一目标确认仍失败。
失败原 XML 保留于下一轮 `e5ec9b4cb1e54f73812635b03bb28bc8/prior-results`，
不称此轮完整通过。将生产来源读取改由 Activity lifecycleScope 拥有后，最终独立完整
WidgetFactEntryTest、ProductionWorkflowInjectionTest 两类 11/11 passed、
0 failures/errors/skipped，connected2m19s（XML117.760s），证据
`e5ec9b4cb1e54f73812635b03bb28bc8/targeted.json`。root51、build1m47s、lint/警告、
实际发现/APK/XML/新覆盖输入及 testbed 清理通过；wrapper/Gradle 和测试包退出后才编辑。
最终源码仅删除两个未使用的 intent 缓存读取，不变显示、输入契约或业务断言。

新增用例使用真实 Hilt/Room/令牌、原读取、共享账户 mutex 和 Glance 状态；仅在真实 DAO/
计数读取后注入等待或失败。清理恢复仅隔离平台发现及 host 提交；闹钟仅替代 AlarmManager
提交。不把合成 Glance ID 当已安装 launcher，不把部分结果拼接为最终 54 项/Android 全量。
目标确认使用现有 Compose 1.7.6 的 StandardTestDispatcher，等待无初始 root 时继续轮询，
原 5s 条件、实际点击、过期拒绝、提醒取消、账户关闭及整库/outbox 断言保留。
150s/1500s 不变，不重复未改容量长链/一分钟服务，无依赖/schema/HTTP/后端/布局变更。
绑定落盘失败跨进程恢复、配置入口竞态、真实 launcher 多实例/像素、正式 v5/Worker/素材/
配置联合、Android/后端 SQL 全量及人工/外网/NAS 仍未完成，#344 未闭合；默认 v4，
无正式 APK 安装/清库/部署/Docker 操作。

### 进度/激励的账户发布与只读恢复（2026-10-09，Issue #332）

两类总览接入 WidgetDisplayPublisher：保留逐习惯真实读取和原资格过滤，显示提交前复核
账户范围；旧结果及旧来源错误不能覆盖替换账户的展示。来源失败隐藏旧总览并提供明确
只读重试，正常加载恢复标记；provideGlance 被动消费，平台更新只入队，移除独立协程。
激励在同次准备中复用 typed 状态，保留旧计数记录日/旧计时达标日连续规则和正常布局。
不宣称整份总览为同一 Room 快照，不缓存写权限或生成业务/同步数据。

首次 `f1403436a0584b4faf47edee46d18fb0` 在构建阶段因新接收器测试缺少
ExperimentalGlanceApi opt-in 退出，行为未执行。修正实验接口 opt-in 与实际 application
Context 匹配后，完整 12 类发现/执行 59 项，58 passed、1 failed、0 errors/skipped，
connected3m50s（XML206.070s），证据 `09a83cb832074ecd8976eefb63d87eac/targeted.log`；
其 XML 被下一轮按原工具旋转至 `aff486c848e4456fb4bdf27d9c2c4459/prior-results`。
唯一失败为新增一次性事项夹具错误采用默认严格模式，原契约要求宽松模式，未放宽契约。
11 个相邻类全部通过；本轮不是完整通过，不删除失败记录或拼接成全量。

仅修正夹具 failMode，生产源码未变；最终按风险重跑完整 WidgetSummaryEntryTest、
WidgetDisplayPublicationTest、ProgressWidgetTest 三类，16/16 passed、0 failures/errors/skipped，
connected1m08s，证据 `aff486c848e4456fb4bdf27d9c2c4459/targeted.json`。root51、
build20s/lint/warning、APK/发现/XML/新覆盖输入及 testbed 清理通过，真实 wrapper/Gradle
与两个测试包不存在后才编辑文档。此前 12 类还包含 ProductionWorkflowInjectionTest、
WidgetFactEntryTest、CountRoundConsumerTest、OneTimeWorkflowTest、WidgetSummaryReceiverTest、
WidgetRefresherTargetsTest、WidgetRefresherTest、WidgetRefreshWorkerTest、WidgetRefreshSchedulerTest。

真实 Hilt/Room/Glance 验证原计数日规则、事项/目标排除、空库零进度、旧连续口径、坏 SQLite
类型隐藏统计及明确恢复先加载再提交；实际生产计时读取验证运行中不产生完成进度、原策略
编辑和来源损坏恢复。账户竞态仅在真实 calculator/Room 读取后注入等待/错误，原 publisher、
令牌切换与 Glance 状态真实；重试仅隔离 host 提交。平台接收器测试实际 override 入队，
仅给 superclass 工作提供真机内拥有/取消/join 的暂停调度器，不安装/绘制合成 host 实例。

不重复未变容量/重启长链或一分钟服务，不调整150s/1500s期限。最终16项不称59项最终
完整门禁或 Android 全量。计时错误/删除发布、账户清理失败/闹钟竞态、已安装 launcher
多实例/像素、正式v5/Worker/素材/配置联合与Android/后端SQL全量、人工/外网/NAS仍待完成，
#344后端全量取消尚未闭合；默认v4，无schema/HTTP/后端/依赖/既有手机平板UI变更，
无正式APK安装/清库/部署/Docker操作。

### 打卡/计数/专注显示的账户发布边界（2026-10-09，Issue #332）

WidgetDisplayPublisher 捕获原账户/副本/设备/权限与同步内存标记，准备阶段调用原账户/Room
读取器，发布阶段复核后在账户锁内、Room 外写主题/Glance；不把不可重入锁套在整条读取链。
旧准备结果和旧读取错误在账户替换后丢弃，匿名登录再退出 ABA 同样失效。来源错误隐藏操作并
清除声明；主题/显示错误与取消继续向刷新链传播。CheckIn/Counting/Focus 保留原事项
canChange、计数日规则和 Focus 时间窗口/两个候选排序，不声称全候选是单一 Room 快照。
三类 provideGlance 被动渲染，配置/动作/明确重试先显式加载；平台更新与 Focus 闹钟只入队
持久化刷新，移除两类平台接收器和窗口闹钟的脱离协程。闹钟重排在发布锁外，避免日规则读取重入。

完整 `WidgetDisplayPublicationTest`、`WidgetFactEntryTest`、`WidgetFactActionTest`、
`ProductionWorkflowInjectionTest`、`OneTimeWorkflowTest`、`CountRoundConsumerTest`、
`CheckInWidgetStateTest`、`CheckInWidgetConfigActivityTest`、`WidgetRefresherTargetsTest`、
`WidgetRefresherTest`、`WidgetRefreshWorkerTest`、`WidgetRefreshSchedulerTest`、
`FocusWindowReceiverTest` 13 类实际 64/64 passed、0 failures/errors/skipped，connected
4m07s（XML suite 219.015s），证据 `d1af1066404c44d3ba7b488ebdaa680b/targeted.json`。
根工具 51、build2m14s/lint/告警、发现/APK/XML/新覆盖输入与 testbed 清理均通过，实际
wrapper/Gradle 和两个 testbed 包不存在后才编辑文档。本轮一次合批，无失败片段拼接。
新增真实 mutex/Room/DataStore 竞争、原读取重入、账户替换/ABA/注册变化、显示清理顺序、
来源/renderer 错误分离与两阶段取消；真实 Hilt/Glance 验证事项完成/撤销、坏 SQLite 类型
隐藏操作、明确重试先恢复再渲染，以及三类主题失败不改原显示/整库。重试仅隔离 host 提交，
callback/Hilt/Room/主题/Glance DataStore 均真实，不把合成 Glance ID 当已安装 launcher 实例。

不重复未变容量/重启长链或一分钟服务，不扩大 150s/1500s 期限，不称 Android 全量、
后端 SQL/正式 UI/launcher 多实例/像素/电量性能或人工通过。Progress/Motivation 整体
发布、清理失败及全部消费者/v5/Worker/素材/配置联合仍待完成；#344 后端全量取消尚未闭合。
schema、HTTP、后端、依赖和既有手机/平板布局不变，默认 v4，无正式 APK/清库/部署/Docker 操作。

### 计时小组件秒级显示与完整审计分离（2026-10-09，Issue #332）

TimerService 使用其 serviceScope 拥有的单消费者与 CONFLATED 队列；积压 tick 只保留最新
习惯，销毁/取消停止消费者，不再每秒广播或在 receiver 启动独立协程。typed tick 保留
原操作声明，复核账户/开始来源、原计划/活动行原 SQLite 指纹、显示日与设备时区，只改
秒数等显示字段，不写原事实/来源/outbox，不重新审计全部保留历史。指纹不复用或放宽
请求日志表白名单/hash 字节。完整加载由配置、平台更新、业务失效和显式重试负责；
provideGlance 不重复加载。缺缓存/状态变化请求一次普通完整刷新，恢复或习惯变化重置；
显示异常保留有界恢复，取消不安排恢复。v4 保留原加载器，布局/完成/时区规则不改。

首轮 `b93881529e8c42abb6d72ebdf0204c90` 发现 56 项，34/56、14 failed 时取消并清理；
主要真实实现错误是把 timelogs 交给仅允许请求日志表的 hash 函数，另有旧刷新次数断言及
切换确认 5s 等待失败。不称该轮通过或把等待失败直接归因于 hash。修正独立原行指纹及
原精确次数后，`f41a6fc18bcf4ce885f2744dda9daafd` 在 38/56、2 failed 时取消，保留日志，
确认本次 wrapper/Gradle 与两个 testbed 包不存在：Android spy 将同一次挂起读取恢复的
空参数记录计作 6 次；测试验证真实非空入口 1 次，完整读取后清空观察，严格要求后续
三次 tick 零次历史读取。服务夹具原先看到暂停 COMMIT 即销毁 serviceScope，可取消
post-commit 刷新；现在先等待精确刷新边界，再销毁，分阶段确认 1/2/3/4 与最终 5 次。
倒计时开始/stop 精确 2 次，全部原单调计时/命令/分段/分摊/冷重开/重复 stop 断言不变。
多实例异常隔离保留首个错误后继续，不对同一异常对象执行自抑制。

最终完整 `WidgetTimerTickReadTest`、`TimerWidgetTickerTest`、`WidgetTimerPublicationTest`、
`WidgetTimerReadTest`、`NextTimerWriterTest`、`ProductionWorkflowInjectionTest`、
`WidgetTimerActionTest`、`TimerServicePersistenceTest`、`LinkedMetricCoordinatorTest`、
`WidgetRefresherTargetsTest`、`WidgetRefreshWorkerTest`、`WidgetRefresherTest` 12 类实际
56/56 passed、0 failures/errors/skipped，connected 7m38s（XML suite 431.799s），独立证据
`1eedcbdb62244f9a87770925a06f6f3e/targeted.json`。根工具 51、build1m39s/lint/告警、
APK/发现/XML/新覆盖输入和 testbed 收尾通过，真实进程与两个测试包不存在后才编辑文档。
8 个完整真实服务用例均通过：切换确认 17.968s、原一分钟自动完成 62.847s，较长原规则/
编辑/恢复用例 79.716s。初轮等待失败未复现，不宣称已证明消除所有间歇性 UI 等待问题。
实际 Hilt/Glance 消费者验证健康 tick 只改四个显示字段、原声明/整库不变、旧显示日/时区
拒绝及开始来源损坏不回退；真实 Room 冷重开验证原规则和原始整数变化、账户切换及取消。
单消费者测试在真机插桩中用协程调度器验证积压合并、生命周期取消及有界失败/缓存恢复。
这些不是 launcher 多实例/像素/电量性能或人工验收。

150 秒仍是单项挂起保护，25 分钟是批次容量，不是产品性能标准；原期限和断言未放宽。
不重复未变容量/重启长链，也不把取消片段拼接成全量。schema/HTTP/后端/依赖/手机平板
UI 不变，默认 v4，无正式 APK 安装、清库、部署或 Docker 操作。Focus 等其余发布边界、
正式 v5/Worker/素材/配置/六组件联合、Android/后端 SQL 全量与人工/外网/NAS仍未完成。
前批 #354 精确 e153f8c03a747cb5a061bb99b461bf8f822342d5 的 CI37891324004 attempt1
已 completed/success（Android build/lint/warning、repository/secret policy，backend 未变
跳过），不代替本批精确提交 CI 或联合门禁。

### 计时小组件账户限定发布（2026-10-09，Issue #332）

NextTimerWriter 的 typed 显示发布保留同一账户协调锁至 renderer 返回，但在显示 I/O 前
结束 Room 只读事务。真实计时/原开始规则/当前轮历史读取复用原审计，不缓存写权限或
补造完成、来源和同步队列；回调不得重入账户锁或写业务数据。账户清理不能穿过此次发布，
旧排队请求、陈旧对象和损坏原来源拒绝；取消/显示异常释放锁。此范围仅为 TimerWidget
的 typed 发布，不宣称其他消费者或 launcher 像素生命周期的账户隔离全部完成。

首次命令误选 WidgetTimerActionTest 的包路径，`66d8ac9572a144c28ef3acf49ee8e648`
在行为发现/执行前取消并 join，确认子进程/testbed 不存在后更正命令；不称该构建通过。
更正后的完整 6 类实际 67/67 passed，connected 9m53s，独立证据
`d06b1a757d764b2590ef0214db9f9825/targeted.json`。其后静态审查发现新发布 catch 把主题/
显示异常误当作来源读取失败；修正为 renderer 开始后的异常向既有有界刷新重试传播，
只有来源失败写 READ_FAILED 并清除动作声明，不回退 v4。真实 Hilt/Glance 消费者通过
损坏隔离 testbed 的主题偏好验证异常传播、原展示和整库证明不变、锁释放及原选择恢复；
不新增测试专用生产分支，不修改正式偏好或主题文件。

最终源码完整 `WidgetTimerPublicationTest`、`WidgetTimerReadTest`、`NextTimerWriterTest`、
`ProductionWorkflowInjectionTest`、`WidgetRefresherTest`、`WidgetRefreshWorkerTest` 6 类实际
30/30 passed，0 failures/errors/skipped，connected 1m33s（XML suite 67.425s），独立证据
`032d53a29c5f47dc800866ef75244054/targeted.json`。根工具 51、build 1m51s/lint/告警、
APK/发现/XML/新覆盖输入与 testbed 清理通过；实际子进程与两个测试包不存在后才改文档。
最终 catch 只影响显示错误分流，未再改共享 reader/writer，故复测发布及相邻刷新重试链，
不重复未变的重启 39 项、容量或一分钟服务；此前 67 项不是最终源码完整矩阵通过。
未改 150s/1500s 期限或业务断言，不称全量/后端 SQL/正式 UI/人工验收通过。
秒级 tick 与完整审计分离、Focus 等其余发布边界和正式 v5 联合仍未完成；默认 v4，
无 schema/HTTP/后端/依赖/手机平板布局变化，无正式 APK 安装、清库、部署或 Docker 测试。

### 测试 APK 打包失败诊断（2026-10-09，Issue #332）

共享读取 #351 的精确提交 `9c22a2de3512aa511af403dff51d72ea0cb2434b`，托管运行
`37886056705` attempt 2 已 completed/success：Android build/lint/warning 与 repository/
secret policy 通过，backend 未变跳过。首次打包失败未复现，底层原因仍未证明。
午夜刷新 #352 的精确提交 `737d50fc60d1a17177740a963fea032213d0e40c`，运行
`37887144771` attempt 1 同样在 `packageDeviceTestAndroidTest` 失败且缺少底层原因；
repository/secret policy 通过、backend 跳过，不重复盲重跑或以 #351 成功替代此门禁。
单独在项目 Gradle 配置开启失败异常栈；不改源码语义、锁定依赖、告警、150s/1500s 期限
或任务选择。本地 `android-build` 实际 root 51、build/lint/告警通过（build 1m12s，121 项
up-to-date），日志 `672fc01fffd14cb4881a75cd6974a7da/build.log`；没有清理重建或复现
托管打包错误，不能称干净构建或故障修复。此纯诊断配置批未安装/执行真机用例，未沿用
前批行为结果为本批通过。包装进程终态、Gradle 子进程与两个 testbed 包不存在后才改文档。
精确提交 `78fe087d649ab1295370f6bc0610ad5d4a2f8595` 的托管运行 `37888370200`
attempt 1 已 completed/success：Android build/lint/warning 与 repository/secret policy
通过，backend 未变跳过。打包失败未复现，配置不是已证明的根因修复；#352 失败记录
不改写，也不等于真机行为/联合全量/人工通过。秒级组件优化与正式 v5 联合仍未完成。

### 午夜旧显示缓存与新模型只读统计（2026-10-09，Issue #332）

午夜任务仅通过账户协调器/Room 事务刷新旧模型缓存，保留原活跃度算法。新模型及部分
初始化行不写累计缓存；无本地归属/退出后不动保留数据。WidgetUpdateWorker 的默认构造
仍由 WorkManager 使用真实 Hilt 入口，测试构造仅注入刷新边界，不替代生产账户/事务证明。
合批覆盖真实数据库原行/来源/队列保持、晚期写失败与冷重开回滚、账户失效、取消收尾、
实际 Hilt 接线及先提交再入队、失败不入队和有界重试。
完整 `LegacyActivityRateRefresherTest`、`WidgetUpdateWorkerTest`、`ProductionWorkflowInjectionTest`、
`WidgetRefreshWorkerTest`、`WidgetRefresherTargetsTest`、`CalendarConsumerRefreshTest`、
`RecurringHabitReaderTest` 7 类实际 26/26 passed，0 failures/errors/skipped，connected 1m40s
（XML suite 73.363s），独立证据 `bf82f2d08e2a4797984ed5f8abb6084b/targeted.json`。
根工具 51 项、build 3m10s、lint/告警、APK/发现/XML/新覆盖输入及 testbed 清理通过，
进程终态且子进程/两个测试包不存在后才改文档。原完整业务断言和 150s/1500s 期限未改。
只验证实际受影响缓存、账户/事务与相邻消费者，不重复容量/重启长链或一分钟服务，
不拼接前批或声称 Android 全量/后端/人工通过。
调度周期与UI布局不变；不承诺后台准点执行，不触碰捕获时区/业务日期或计时完成条件。
计时组件 tick 分离、正式 v5/Worker 联合、全量及人工验收仍未完成。

前批详情共享快照提交 `9c22a2de3512aa511af403dff51d72ea0cb2434b` 的托管 CI
`37886056705` 首次在 `packageDeviceTestAndroidTest` 失败，原输出没有底层原因；
repository/secret policy 通过，backend 未变跳过。仅对同一提交的失败作业开启一次 runner
debug 诊断重跑（attempt 2），不修改源码或期限；后续终态见上节，不把未复现归因为环境。

### 当前轮详情共享读取与活跃度（2026-10-09，Issue #332）

RecurringHabitReader 将原计划、统计与动作票据放在同一账户/Room 快照，派生活跃度独立于
计划行；计数部分参与不成为达标，详情及列表/目标子项不读累计计数活跃度缓存。
首轮构建观察到新快照 data class 的非公开构造器/生成 copy 可见性告警，在行为发现/执行
之前取消并 join `7fa30315f2ca41c28b8dd5c214cad1d0`，确认子进程/testbed 清理后再编辑。
该运行不称通过或实际告警预算拒绝；改为不需要 copy 的普通只读快照类，不抑制告警。
首轮完整 8 类实际执行 79 项，77 passed、2 failed、0 skipped，connected 10m07s，证据
`aa8e2898d8b640f19f4adbceb09af6f7/failed-results`（保留本次原 XML/logcat），没有成功清单。
其中冷重开测试的新共享 reader 误复用旧 Room 实例的 editor，原事务检查正确拒绝；
另一测试的手动 repository 装配漏传新 reader，详情报 `RECURRING_READER_REQUIRED` 后原 5 秒
等待失败。生产 Hilt 装配与新 reader 测试通过不替代整批结果。收尾确认子进程/testbed 清理后，
只纠正这两处夹具依赖：冷重开重建同实例 reader/editor，手动 repository 补真实 reader。
保留事务检查、全部断言与原等待期限，并在既有真实 Hilt 用例中直接验证新共享读取。
最终完整 8 类 `RecurringHabitReaderTest`、`CalendarConsumerRefreshTest`、`NextRestartWorkflowTest`、
`NextObjectEditorTest`、`NextCountDayWorkflowTest`、`ProductionWorkflowInjectionTest`、
`HabitDetailViewModelTest`、`HabitDetailViewModelNotificationTest` 实际 79/79 passed，
0 failures/errors/skipped，connected 10m12s，XML suite 587.022s，独立证据
`10be0d6d5619441c83d09fc65a1b9435/targeted.json`。根工具 51 项、构建/lint/告警、
APK/发现/XML/新覆盖输入及 testbed 清理通过；进程终态后才更新文档。旧失败/取消不拼接通过。
验证包括真实依赖注入、当前轮跨日/冷 ACK、原事实保留、部分计数不达标、陈旧计划与账户
拒绝，以及实际列表/目标子项/详情的日期、时区与错误重试通知。没有新增重复的长链用例。
未重复运行未变的 10001 条容量或真实一分钟服务，不称全量 Android/后端 SQL/人工通过。
午夜缓存、计时组件 tick 与正式 v5 联合仍未完成；默认 v4，无正式 APK/清库/部署操作。

### 计时当前轮读取与原完整会话证明（2026-10-09，Issue #332）

首轮 21 个完整受影响类发现并执行 202 项，200 passed、2 failed、0 errors/skipped，
connected 19m06s。证据 `3220cb829faf47e39c1e4980475474ac`；正式包未操作，testbed 清理完成。
新增零目标夹具违反现行类型契约，改为验证非法编辑整笔拒绝，不放宽生产目标条件；
原专注组件损坏证明用例暴露统计阶段的错误边界缺失，补明确 READ_FAILED，不降级旧统计。
收尾同时使状态/子项统计与操作票据同事务读取，并移除误用于累计读取的旧一万条命令上限。

NextRestartWorkflowTest 新增 5 项覆盖当前轮跨午夜分摊、旧预取不复活进度、真实冷同步
ACK/完成出生、隐藏分摊与缺失出生只读拒绝、非法零目标回滚、实际详情日历轮头刷新，以及
未结算过去日期/暂停排除/另一确定缺口优先失败。真实一分钟服务仍由原完整类验证；
新增生产 writer 夹具的指定 UTC 区间不是新增设备单调时钟完成证明。
TimerHistoryIndexTest 的 10001 条合成保留索引仅验证读取容量和末尾原 SQLite 类型损坏，
不是已接受会话；只有实际 producer 生成的一条运行会话，不可产生完成进度或接受凭据。
第二轮发现 203 项，容量用例触及原 150 秒单项上限，随后原计时切换确认等待也失败；
在 158/203、2 failed 时取消并 join `614c0f418dec49a1b346d8d3b07b98c1`，确认子进程与
testbed 清理后才编辑。此运行不是完整通过，服务等待失败不在未复测前归因于容量用例。
读取改用事务内最多 128 条原行审计/DAO 批次，hash 字节编码不变；超 2 MiB 批次仍逐行
审计，不改变原单行上限、不 truncation、不缓存权限。容量末页增加合法大原文，验证回退。
TimerHistoryStorageTest 覆盖原 SQLite 正负越界 Int（时长/序号/代次/启动数/片段序号）
只读拒绝、真实正常会话恢复及原整库证明不变；另验证两个请求 kind 复用 UUID 时的批次
namespace、缺行和原队列整行 hash。合成碰撞仅是 SQL 元数据，不冒充接受事实。
存储夹具的混合数组类型推断触发新 Kotlin 告警，`71f63e013ab04ffab2bf079b0b7cb929`
在构建告警门禁退出，行为用例未发现/执行；退出后显式声明数组类型，不扩大告警预算。
随后 23 个完整受影响类实际执行 205 项，204 passed、1 failed、0 errors/skipped，
connected 21m29s，证据 `85fae5c533d8415b9c0c1a427d411df3/failed-results.xml`。
唯一失败仍为容量用例的原 150 秒单项超时；计时服务完整 8 项通过，原切换确认 17.839s，
该类最长实际一分钟/恢复用例 79.539s。不把本轮称为通过，或把此前等待失败称为已定位消除。
用户要求先区分测试增长与代码瓶颈并评估单项期限，暂不改生产逻辑及期限；只加测试方法内
阶段日志，单独执行完整 TimerHistoryIndexTest。最终 1/1 通过，实际用例 144.727s，证据
`0353595261394133b57120c6577f3ceb/targeted.json`，新覆盖输入/身份/构建告警及 testbed 清理通过。
同一原用例中索引构造 2.558s、正常生产读取 77.770s、末尾损坏只读校验 62.586s，方法体
总计 143.881s。该日志只定位插桩真机环境的阶段成本，不作为未插桩正式 App 的性能基准。
不是其他用例数量消耗了此项期限；新增容量场景暴露生产读取瓶颈，两次全索引读取合在
一项时又缺少波动余量。精确热点仍需核对，不能直接把逐行协程切换猜测当成已测根因。
现行 150 秒是工程挂起保护而非业务时限/Android 标准；本机锁定 JUnit 与 runner 的
withPotentialTimeout 包裹测试方法，独立 @Before/@After 在外，但方法体内构造属于期限。
后续先审查有界批次读取与独立容量场景，不扩期限/截断规模/删除断言或拼接旧结果。
随后仅优化有界原文批次的解析调度：同一批次在 Default 使用原严格 decoder，SQL 仍在
Room；保留取消检查、原行 hash、2 MiB/128 条边界及大原文逐行回退，不缓存权限。
完整容量/存储两类 3/3 通过，证据 `2ea2234618f3434394c9bf43b1a39cab/targeted.json`；
原容量用例 XML 62.539s，方法体 60.805s（正常读取 25.233s、损坏校验 32.145s）。
同一规模和断言下调度优化确实改善读取；不将单次插桩比较当作正式 App 性能保证或唯一热点证明。
最后合批执行完整 `NextRestartWorkflowTest`、`NextTimerWriterTest`、`ProductionWorkflowInjectionTest`、
`TimerHistoryIndexTest`、`TimerHistoryStorageTest`、`WidgetTimerActionTest`、`WidgetTimerReadTest`：
7 类/65 项全部通过，0 failures/errors/skipped，connected 11m31s，独立证据
`49c21e3793a54c0bb01ddb1d90d72b69/targeted.json`。较长批次中原容量用例 XML 96.303s，
方法体 95.876s（正常读取 48.064s、损坏校验 45.313s），同时记录波动，不仅选低耗时样本。
原 150 秒单项/1500 秒任务上限、10001 条规模、大原文与全部业务断言未改，未拆容量用例。
根工具 51 项、构建/lint/警告门禁、APK/发现/XML/新覆盖输入与 testbed 清理通过。
调度优化的最终范围包含读取/冷重开/新轮来源、原始损坏、取消/回滚、账户隔离、操作及真实
依赖注入；不重复执行未变的真实一分钟服务或其余整个 205 项矩阵，不拼接成全量通过。
本批定向门禁闭合，不称 Android 全量/后端 SQL 联合/正式 UI/人工验收通过。
活跃度缓存、详情统计与动作轮头的联合读取、小组件秒级刷新及正式 v5 接入仍待后续完成。
schema、旧来源/冻结字节/收据、HTTP、后端、依赖与 UI 布局不变；默认 v4 未切换。

### 打卡当前轮读取与共享普通事实审计（2026-10-09，Issue #332）

从 CountHistoryReader 提取实际 RecurringCompletionEvidenceReader/FactEvidence，保留计数
原日规则与原诊断；CheckHistoryReader 复用同一账户/Room、全事实、原来源和真实出生审计。
打卡不逐日期执行计数缺失日规则探测，仍验证所有有效事实及保留的计数规则。实际仓库、
操作进度、状态/严格失败、主列表/目标子项、详情日历、事实/动机小组件接当前轮；
操作后的新快照用于进度反馈，撤销多条中的一条按剩余有效记录报告完成。

NextRestartWorkflowTest 新增 6 项真实 Room/account/producer/runtime 与消费者用例：
双重离线重启/今日 toggle/冷同步、历史撤销保留出生、原始预取不复活旧进度/失败、旧轮
小组件拒绝且不写、隐藏 BLOB/缺失出生只读拒绝、真实四 ViewModel 在没有新事实的
轮头变化后同步更新主列表/子项/个人进度/详情日历，以及多条当前打卡部分撤销。

构建阶段静态发现打卡不应重复计数日期探测，取消并 join
`d1bd8b853b42483eb6404b3fa3dd4dbb` 后修正；未发现/执行行为用例。
新增夹具误用队列字段导致构建失败 `67c12ea861354388b9294267bd4b62f2`，同样无行为
通过。进程退出后合批修正夹具、操作后快照和部分撤销的真实状态反馈。
最终 15 个完整受影响类 162/162 通过，无失败/错误/跳过，connected 11m30s，证据
`cbb589e8e5f340398bded72037719fcb/targeted.json`。矩阵涵盖原重启工作流、计数原事实/
当前轮/操作/提醒、四消费者刷新、真实事实小组件行为/Hilt前台入口、原打卡操作/活跃度
算法、详情/通知订阅、嵌套和进度组件/卡片。root51、build/lint/warning、官方发现与
APK/XML身份、新覆盖输入和测试壳清理均通过，未扩期限/删除断言/跳用例。
未改 schema/HTTP/后端/依赖/正式包/布局/冻结来源；未重跑未改的完整计时和发送器长链。
不称 Android 全量/覆盖率全局校准、后端SQL联合或人工验收通过；计时及其余活跃度/
消费者、远端控制、正式 v5/重启/Worker/登录切换及 #160 其余联合门禁仍未完成。
本机 Docker 未执行；未正式安装、清库或部署。

### 待接受新轮的离线录入与编辑（2026-10-09，Issue #332）

完整 `NextRestartWorkflowTest` 新增 12 项，使用真实 Room、账户存储、编辑/习惯仓库与计时
writer、原来源、socket 及实际 runtime。覆盖连续两轮离线计数/原日规则、历史撤销、实际
打卡录入/撤销、新轮连续编辑与真实 Plan revision 桥接、旧轮普通编辑不成为新轮父依赖、
开始/取消及下一次重启的原出生、真实 ACK 已提交但 Plan 追赶中断后的单次同步恢复、
损坏原来源/Plan proof、旧私有编码冷重放，以及 replacement 的 deferred 最终 COMMIT
外键故障整笔回滚/冷重试。新增计时链验证 start/cancel，不冒称新增真实一分钟完成验收；
相邻计时服务完整类保留原实际完成条件及断言。

最初缺扩展导入构建失败 `89dab80e4267441385f9b5008a1cedb6`；随后核对实际计时票据接线，
构建阶段主动取消 `ed348c00f31145fabc53d38d3fa14f5f`，真实进程退出后才修改。新增 socket
夹具作用域错误构建失败 `07211dd0da37472c92da03cb830cb969`。三次均未执行行为测试。
合批修正实际 writer、缺省编码兼容及中断追赶后，20 个完整受影响类 284/284 全部通过，
无失败/错误/跳过，connected 21m17s，证据 `32d84221edcb43dfb3638670085ace45/targeted.json`。
矩阵包含核心请求、结构因果长链/纯合并、计时请求/开始顺序/writer/小组件、计数日规则/
全部事实证据/当前轮读取、round 普通/计时/writer/目标删除、运行时与结构/挑战恢复。

收尾审查加强陈旧票据用例：先构造合法计数日规则，断言实际旧票据拒绝原因及整笔回滚，
再证明新票据确可录入。生产 diff SHA-256 保持
`1c303a658249991ec56c58328a932e39311942da854d9460ab7caec781f41bb8`，新增绑定模块 SHA-256
保持 `9bc34ebf2c9c6e45eb312ac6cf84a871e678b80f04c7f33f745c53000d5b176e`。
仅重跑完整 `NextRestartWorkflowTest`，最终 28/28 全部通过，无失败/错误/跳过，6m22s，
独立证据 `6f12c6efb087487196fce2bdebd04e95/targeted.json`。两次 root 51 项、构建/lint/
警告门禁、APK/发现/XML/新覆盖输入与 testbed 清理均通过。不拼接为 312 项或全量通过，
没有重跑未改迁移/后端，不改冻结 schema、正式包、v4、UI、单项/批次上限或测试断言。
socket 不替代后端 SQL 联合验收；统一本轮打卡/计时及其他消费者、正式激活、全量/人工/
外网/NAS 验收仍未完成，本机 Docker 未验证。

### 离线重启内部链验证记录（2026-10-09，Issue #332）

新增完整 `NextRestartMigrationTest` 和 `NextRestartWorkflowTest`。真实文件 Room 验证
冻结 14→15 的旧行/DDL/原冻结字节不变、两张空私有表、错误 identity/占表/最终验证失败
整体回滚及冷重开。真实 producer、账户存储、socket 和内部 runtime 验证连续两次离线
重启、真实配置后继接受后才物化 revision、start/cancel 终态等待、响应丢失原字节重放、
日志先于本地 ACK 不造接受、永久/暂时拒绝、账户/原来源变化、合法 JSON 替换原文拒绝、
Plan proof 晚期副作用/接受写入故障回滚及远端墓碑不得级联待同步重启。
socket 边界不是实际后端 SQL 联合验收；新增接受故障用例是提交前故障，不能称为新增
重启专属 deferred 最终 COMMIT 故障证明，相邻已有最终 COMMIT 矩阵仍保留。

初次构建失败（`06fc27c0422148348200330a850303eb`）修正新响应夹具参数；随后构建/lint
通过但发现拒绝非 Unit JUnit 方法（`4ef961cc114e4648800452a678df310e`），均没有行为通过。
完整受影响 32 类/313 项实际执行 312 passed、1 failed、0 skipped，17m54s，证据
`cfa44da6d826455e8ba095e3083b8345`：新增配置链夹具漏传真实编辑票据，生产拒绝正确。
收尾后合批补真实票据、原文绑定、逻辑顺序、UTC 等价锚点及删除保护，再执行全部受影响
完整类：`NextRestartWorkflowTest`、`NextRestartMigrationTest`、`NextChallengeRestoreTest`、
`NextRoundSyncRuntimeTest`、`NextSyncRuntimeTest`、`NextStructureRestoreTest`。
最终 6 类/75 项全部通过，无失败/错误/跳过，connected 4m38s，独立证据
`8893279b8035401199d34a9e9646c9c9/targeted.json`。根工具 51 项、构建/lint/警告门禁、
APK/发现/XML/新覆盖输入及 testbed 清理通过。迁移旧 schema 1–14 没有改写。
不拼接成 313 项或 Android 全量成功，不重复执行未变迁移/长链矩阵；未执行正式 UI、
新轮后继离线录入、后端 SQL 联合、人工、外网或 NAS 验收，正式包未安装或清除。

### 按风险选择验证范围（2026-10-07 用户决策）

日常开发不再为每个小修改执行全量测试。先明确受影响入口、调用方和持久化边界，再选择下表范围；
相关修改合并成可审查的一批，修复完成后验证，不在每个中间编辑后重复构建或全量执行。

| 改动 | 日常最低验证 | 升级条件 |
| --- | --- | --- |
| 文档、验证工具、纯任务容量配置 | 根工具回归；受影响命令/实际配置检查 | 改变测试执行路径时增加对应定向真机 smoke；不因工具修正重跑全部业务用例 |
| 局部 Android UI、算法、仓库修复 | 构建/lint，以及受影响的完整测试类和必要相邻类 | 公共接口、生命周期或其他消费者受影响时扩大范围 |
| 数据库、账户、时间、同步状态机 | 受影响模块的完整矩阵，包含迁移、事务回滚、冷重开、隔离、取消及精确重试等相关场景 | 无法可靠界定影响，或正式接入跨模块流程时执行联合/全量验证 |
| 局部后端修复 | 锁定格式/静态/类型检查和相关完整 pytest 模块，保留 warning 插件 | 共享基础设施、协议激活、数据库框架/工具链升级及关键集成执行全量 |
| 关键联合接入、阶段收尾、发布 | 全量后端与 Android 门禁及适用人工验收 | 外网/NAS等未执行场景明确登记，不表述为通过 |

范围依据写入 PR：变更影响、选择的测试类/模块、实际执行结果、推迟的联合/人工验收。
定向通过只证明所选范围，不代替阶段全量验收；失败、取消及旧运行片段不能拼成全量成功。
若连续运行得到相同失败证据且源码未变，先定位原因，不通过反复重跑碰运气。
测试新增应针对不变量和关键失败路径，不为每个内部实现细节重复搭建昂贵的端到端场景。
优化公共夹具/等待/数据库生命周期须保留所有业务断言，独立证明没有泄漏或收尾缺失；
不缩短真实计时业务要求、不扩大单项期限、不删除有价值用例来换取速度。

### Android 改动

日常定向真机入口按完整类选择，不接受方法、包、通配符或外部参数注入：

```bash
ANDROID_SERIAL=<设备序列号> ./tools/verify android-targeted \
  com.dayforge.data.repository.NextCoreRequestStoreTest
```

一次可显式选择多个相关类。入口复用构建/lint、真机与跨 worktree 锁、独立 testbed、官方
`log=true` 发现、原 150 秒单项/1500 秒任务上限和进程取消收尾。每个选定类必须发现实际用例，
包含参数化展开；真正执行使用同一类集合与 `log=false`，新鲜 XML 身份必须与发现集合完全相等。
缺项、额外项、重复、跳过、失败、无效耗时、缺失/空覆盖率、APK 改变或清理失败均拒绝通过。
定向结果保存为独立 `targeted.json`，注明选择范围、身份、APK/发现/XML/覆盖率输入 SHA-256；
不执行全局覆盖率校准，不生成 `complete.json`，不声称全量通过。选择错误不得静默退回全量。
这是对完整类的显式验证，不是用过滤隐藏全量门禁中的失败。

全量门禁保留为关键集成/阶段收尾入口：

```bash
ANDROID_SERIAL=<设备序列号> ./tools/verify android
```

入口通过 `tools/run_android_tests.py` 执行一次 `lintDebug`、`assembleDebug`、`assembleDeviceTest`、
`assembleDeviceTestAndroidTest`；安装隔离 testbed APK 后，以官方运行器 `log=true` 在真机发现完整
用例身份（包括参数化展开），这一步不是行为测试通过。随后使用官方 `numShards=3` / `shardIndex=0,1,2`
顺序执行三次 `connectedDeviceTestAndroidTest`，显式 `log=false`，不使用类、包或其他用例过滤。
各批结束即保存 XML 和独立 `.ec`，核对三批身份并集与发现清单完全相等且无重复，才将三份新覆盖率
输入送入原 `createDeviceTestCoverageReport`。此时排除该报告任务的 connected 依赖，避免再跑一批；
不是跳过尚未执行的测试。真实测试和统计校准均须成功，且 testbed 清理完成后才写入 `complete.json`。

每次运行证据保留在 `android/app/build/reports/dayforge-device/<本次随机身份>/`：发现日志、三批
Gradle 日志/原始 XML/覆盖率文件和成功清单；成功清单记录 APK、报告与输入 SHA-256 和完整发现身份。
旧生成报告与覆盖率目录会移入该次 `prior-*`，不作为当前证据，也不递归删除用户文件。
原 `android/app/build/outputs/androidTest-results/connected/deviceTest/` 为单批暂存，结束后移走；覆盖率报告位于
`android/app/build/reports/coverage/androidTest/deviceTest/connected/`。
`tools/check_android_results.py` 拒绝缺失/空报告、失败、错误、跳过、统计错配、缺失/重复用例身份，以及
缺失/负数/非有限耗时（中断 XML 可能把未完成用例写成负数而没有 failure）。独立检查单批 XML 不证明
完整发现或覆盖率新鲜；完整验收必须经统一入口检查全部批次。任何批失败立即停止，不自动重试部分用例。
`tools/check_android_coverage.py` 要求输入校验、指标仓库和计时服务三个实际执行的应用类均有
非零源代码行覆盖，防止“测试执行了，但插桩没有记录”的报告被当作可靠统计。
这只是统计校准，不是百分比达标，也不能代替异常路径断言。

后端作业上限 25 分钟，CI Android 构建步骤上限 20 分钟、作业上限 25 分钟。
后端整套锁定串行测试增长后，两次托管运行持续通过到 90%/91% 才触及原 15 分钟作业上限，
因此单独调整整套作业容量；不改变测试范围、断言、告警预算或原用例内部期限。
取消的运行仍是未通过，不能把延长作业容量当作修复挂起或验证成功；必须取得精确提交的完整结果。
真机测试单项上限 150 秒（含完整一分钟计时），每批 Android 测试任务/包装进程上限 25 分钟。
用户于 2026-10-05 确认完整测试改为固定三个顺序批次，以容纳增长的真实图标界面集成测试及
Gradle 收尾；全量模式不支持任意扩批或用户过滤，单项上限不变。用户于 2026-10-07 批准将整批容量由
15 分钟调整至 20 分钟：完整发现增长至 1633 项，第三批在原期限内持续完成 513/555 项、无单项失败，
剩余 42 项在先前完整运行中约需 98 秒。历史耗时仅用于容量估算，不作为当前通过证据；
新增 32 段真实因果链仍须在原单项期限内完成全部确认及断言。此前两批证据仍是历史记录，
不能作为当前三批门禁通过的证明；任意一批缺失或损坏均拒绝生成成功清单。
超时属于失败，不能通过提高时限掩盖未结束的协程或挂起的测试。
后续容量评估先检查原始运行器身份/进度、单项耗时、资源日志及进程收尾，区分真实挂起、泄漏、
重复开销与正常测试增长。已有证据相同且源码未变时，不反复完整重跑来碰运气；真实问题先修复，
正常容量不足按实测单独规划并经用户确认。容量调整不允许放宽单项期限、断言或失败/覆盖率校验，
也不以过滤用例、调整类名分布、追加任意批次或拼接失败运行取得全量通过；容量修正按上述风险规则
验证，完整门禁在下一次关键联合接入/阶段收尾执行，不能把旧片段沿用为当前通过证据。
同日用户随后明确批准每批 25 分钟（1500 秒），覆盖 Gradle connected 任务与 Python 包装进程两层。
此前只提高包装容量，原 Gradle 15 分钟上限仍在，导致新一轮在 520/555 项、无单项失败时取消。
根工具回归同时核对实际 Gradle 任务块、包装容量与原 150 秒单项设置，拒绝旧容量、错任务、缺失或
重复赋值及单项期限改动，防止再次发生两层错配。被取消的部分结果不代表完整通过。
用户同日要求按改动风险和影响范围验证，不再因小问题重复全量；本次纯容量修正采用根工具回归与
实际 Gradle 配置检查，完整门禁留到关键联合接入/阶段收尾，不把局部结果拼成全量成功。
全局文件锁保证本机不同 worktree 的 Android 验证不并行；各 Gradle 调用使用独立 single-use daemon，
取消/超时终止并等待本次包装进程，随后仅 force-stop/卸载两个精确 testbed 包，再释放锁。
不得使用全局 Gradle stop、杀无关进程、清除正式 App 或从失败运行遗留文件拼出成功。
后端全量 pytest 和根工具测试也会验证同一跨 worktree 文件锁；在同一主机上不得与 Android
门禁并行，否则验证入口探针会因真实锁被占用而失败。联合验证按 `tools/verify all` 的
后端→Android 顺序执行，不能关闭锁或跳过探针规避互斥。
外部 Gradle 属性/环境注入 instrumentation 参数会被拒绝，托管 `android-build` 不发现/安装/执行测试。
工具回归使用临时生成物、伪命令边界和真实短命子进程验证顺序、缺批/漏项/重复、旧结果排除、
覆盖率缺失/损坏、APK 改变、过滤注入、链接目录拒绝、超时 join 与锁重用；这些不是 Android 行为测试。
运行器依据：[AndroidJUnitRunner 分批文档](https://developer.android.com/training/testing/instrumented-tests/androidx-test-libraries/runner)
及 [log-only 定义](https://github.com/android/android-test/blob/main/runner/android_junit_runner/java/androidx/test/runner/AndroidJUnitRunner.java)。

Android lint 使用官方 `android/app/lint-baseline.xml` 记录已有问题，并将所有未进入基线的
warning 提升为 error。Kotlin、javac 与 Android 资源编译警告由
`android/compiler-warning-budget.json` 按“类型、规范化消息、最大数量”登记，
`./tools/verify android` 会拒绝未知警告和数量增长。编译任务可能命中缓存，因此只有干净、
完整重编译后观察到的减少才可以用于下调预算；主机 SDK/命令行工具不匹配之类的环境提示
不属于代码警告预算。
`AndroidGradlePluginVersion` 和 `GradleDependency` 依赖实时仓库元数据，同一提交在不同缓存中
会产生不同结果，因此不进入 lint 门禁；锁定版本的升级必须通过独立的工具链/依赖审查 PR，
不能据此忽略编译、运行时弃用或其他静态分析问题。

Room schema、计时、后台任务或 Android 平台行为变化时还需要相应 instrumentation/真机测试。仅含 TODO、没有断言或没有执行路径的测试不计为有效覆盖。

严格计时失败读取的受影响矩阵包括完整 `StrictTimerFailureTest`、`DurationDayAllocatorTest`、
`TimerTransitionDaoTest`、`CapturedDayStatisticsTest` 和 `NestedViewModelTest`。
`StrictTimerFailureTest` 使用真实文件 Room 验证跨午夜待结算不写完成/分摊/outbox、暂停不计时、
确定的更早/更晚断签优先、捕获时区/DST/绝对时限上界、损坏片段拒绝、冷重开、正常 stop/cancel
以及 App/小组件共同判定；不修改手机时钟，不冒充完整计时服务或新轮次同步验收。
实际会话资格仍由原服务及开始规则测试覆盖；只读待结算上界不是完成阈值证明。

挑战轮次契约采用同一 `contracts/next/challenge-rounds.json`：后端完整
`test_challenge_round_contract.py` 与 Android 完整 `ChallengeRoundContractTest` 验证 UUIDv5
初始基线、整数/修订上界、严格类型、轮次及计划前置条件、未结束计时拒绝、精确创建绑定、
旧页不回退、连续增量与完整恢复、原设备限定操作命名空间及输入不变性。相邻验证包括
`test_one_time_sync_contract.py` / `test_next_contracts.py` 和 `OneTimeSyncContractTest` /
`ContractIntegerTest`。这些是领域契约测试，不是迁移、账户授权、真实 SQL CAS、冷恢复或
在线同步验收；持久接线完成后必须补对应矩阵，不能将有效序列化记录当成接受或写权限。

后端持久化由完整 `test_challenge_storage.py` / `test_challenge_migration.py` 验证实际增量迁移、
旧全行/DDL 不变、无出生回填、最后 checkpoint/中间 DDL 回滚、跨账户同 UUID 及数据库复合外键。
真实 storage 验证两个设备依次争用旧头（不是并发压力测试）、原计划/能力/未结束计时拒绝、
原 ID 冷重放、新头不回退、出生/撤销/计时完成继承、计划/头/轮次/日志同保存点回滚，
以及真实 deferred FK 在最终 COMMIT 失败时全部原行不变、同意图重试成功。
受控实际 v5 HTTP 验证 JWT/API Token、
正倒计时、跨午夜暂停排除与完整 stop、提交后独立连接读取绑定、旧 stop 收据精确重放。
物理/逻辑 v4 往返验证完整轮次/头/旧事实；重新校验和的坏链、坏归属、错头、严格类型及旧版本标记
仍拒绝并逐表回滚。相邻矩阵包括完整迁移/SQLite 事务、v4/v5 同步及计时、日规则、一次性事项、
全部逻辑归档/账户复合引用/素材归档、物理备份及 HTTP 提交边界模块。此批不代表挂载重启 API、
共用操作收据/全客户端协商、Android Room/统计/完整恢复或正式重启入口已经完成。

后端挑战档位由完整 `test_challenge_profile.py` / `test_challenge_receipt_migration.py` 验证：
共享 wire 向量 `contracts/next/challenge-sync.json` 的完整 `test_challenge_sync_contract.py`
另验证原对象/副本 hash 不因 sidecar 替换、严格创建绑定和完整元数据，尚不是 Android 消费者验收。
真实 JWT/API Token HTTP 准入、严格档位/原来源集合、共用原收据、新头后的旧接受/拒绝重放、
旧冻结 v5 初始出生不改写、晚到旧轮事实/计时、同日新轮及共享计数日规则、撤销继承、
正倒完整暂停/恢复/stop/cancel、未终态重启冲突、目标级联/分离的完整子头检查、混合批次拒绝
不毒化后继操作、最终 COMMIT 失败整体回滚/原请求重试、坏响应/原 hash/结果/归属不推进游标。
物理与逻辑往返保留原 sidecar/原副本，重新校验和的坏收据导入逐表回滚；增量迁移只追加 NULL 列，
保持全部旧列/原请求不变，中间 DDL/最后 checkpoint 失败可重试，非空证据/离线禁止降级。
相邻完整矩阵继续覆盖既有 v4/v5 同步、完整计时、日规则、事项、迁移、SQLite/HTTP 提交与快照、
逻辑/物理/素材恢复及冻结契约；后端档位结果不能标作 Android/人工或正式启用验收通过。

Android 完整 `ChallengeSyncContractTest` 使用相同 `next/challenge-sync.json` 与
`next/challenge-rounds.json` 校验原 operation/command 不变、精确档位、唯一来源集合、重启
不可合并/删除、完整链和原出生、原 device/intent/revision ACK、冻结计数规则及空结果的严格元数据。
完整 `NextSyncHttpTest` 同时覆盖旧八类核心路径和新六个挑战档位：真实 socket/auth/DataStore，
单值 GET 标记与原副本头、冻结字节/数字/省略字段的丢响应精确重试、坏请求零私有流量、
坏或缺失证明拒绝、账户/副本/版本准入、取消时关闭 socket 并 join、无游标/注册发布及无旧路由回退。
相邻完整类是 `ChallengeRoundContractTest`、`OneTimeSyncContractTest`、`ContractIntegerTest`、
`NextCoreRequestStoreTest`；单独 HTTP 改动不重复执行全部迁移。真实 HTTP 夹具
不冒充服务端最终 COMMIT、Android Room 接受/轮次统计、正式 v5 调度或人工联合验收。

Room 挑战恢复改动须运行完整 `NextChallengeMigrationTest`、原全部生产迁移/新库/兼容升级类，
及 `NextChallengeRestoreTest`、`NextSyncRuntimeTest`、核心发送/计时接受/准入和相邻 HTTP/契约类。
13→14 对冻结 v13 全部旧列、原队列/字节、旧 cursor 列/约束逐项比较，检查空轮次表、旧档位 0、
DDL/最终 Room 校验失败回滚及冷重开；新增 cursor 列仅由这一迁移负责验证，旧 schema 不改写。
真实 profile HTTP→Room 测试覆盖分页/冷恢复、旧出生不跟随新头、共享 count-day、空页检查点、
原创建来源唯一性、缓存裁剪不删历史/出生、不重建删除计划、旧冻结队列不变、旧路径拒绝、
下载期间新增本地工作不提交、坏历史/出生/SQLite 类型/档位、账户/设备/epoch、晚期游标及
轮次写入触发器故障整体回滚、同响应重试仍审计历史。testbed 明确清理后外键/重开正常；
只读恢复不声明完整上传、计时命令归属、统计/UI 重启、正式协议启用或人工联合验收已通过。

普通 profile operation 的写入/接受由完整 `NextRoundOperationStoreTest` 验证真实文件 Room、
认证 DataStore、实际 round HTTP、丢响应完整 envelope 冷重试、无第二次 HTTP 的接受冷重放、
同日新轮共享原计数规则、旧回调拒绝/历史撤销继承、未接受事实撤销的原来源证明、
新增身份事务的显式基线、结构 replacement 保留原上下文、档位严格类型及账户/能力改变。
缺失/错误出生、侧表触发器修改其他业务、晚期收据损坏侧表和真实 deferred FK 的最终 COMMIT
失败均须保持原队列/业务/历史/游标，清除故障后同一原 delivery 可重试。
相邻完整矩阵包括核心 producer/sender/接受、结构因果/重建、计数日、计时接受/开始排序、
计数原事实证明/共同消费者/提醒（读取新意图不丢失旧证明与冻结日规则）、
目标删除、一次性接受/发送、profile 恢复、统一 runtime、HTTP 与轮次/事项/整数合同类。
Room schema 未改变，不重复无关全部迁移；这一范围不代替后续全量联合或人工验收。

当前轮计数读取完整 `CountRoundConsumerTest` 使用真实文件 Room、账户、原 producer/ACK、
连续 metadata/Plan 合并及共享消费者，验证同日归零而原日规则保留、旧事实晚接受不复活、
冷重开、新待创建身份、隐藏旧事实/缺出生/原设备副本/冻结上下文损坏只读拒绝、账户替换、
仅轮头变化的实际 Flow 刷新，以及旧物理清历史拒绝。小组件实际 increment/undo 与新来源
绑定、旧 plain/旧轮声明和 PendingIntent 隔离、原提醒控制器的旧唤醒拒绝/当前进度读取继续
执行。完整相邻矩阵包括 `CountConsumerWorkflowTest`、`CountFactEvidenceTest`、计数提醒、
小组件事实入口/动作、档位 operation/writer、计数日、日历刷新、实际 CheckInService 与捕获日期。
原事实验证先于轮次筛选；测试快照保留 SQLite 列类型及冻结 BLOB，不通过删除故障断言取绿。
夹具修正且生产源码未变时，仅重跑完整受影响类及必要相邻类；保留原失败，不拼为全量成功。
该范围不证明实际重启 API/离线新轮、打卡/计时及活跃度统一统计、正式激活或人工联合验收。

档位计时完整 `NextRoundTimerStoreTest` 使用真实文件 Room、账户 DataStore 和 socket 验证正倒
离线 start/pause/resume/stop、完整开始快照/原配置 ACK 前沿、原出生继承、取消不复活、响应丢失
的完整原 envelope 冷重试及无网络冷收据重放。较新头的晚终态 ACK 保留旧出生、不伪造 Plan
修订；坏出生/档位/设备、过期开始票据、孤立后继及取消/旧账户保留原工作。
侧表/晚收据/一致改游标触发器及真实延迟外键最终 COMMIT 故障须整体回滚并允许同 delivery
冷重试；真实完成事实页须匹配原 stop、同出生和完整毫秒，恢复原日分摊，坏事实不推进游标。
相邻完整矩阵包括原计时 sender/policy/ordering/writer、核心与普通档位生产者、结构因果、
目标删除、计数日、实际计时服务/片段/分摊、事项接受及同步恢复/runtime/HTTP/档位合同。
这些用例使用已有命令/时间夹具，不冒充真实一分钟计时服务、正式 v5、远端恢复会话控制权
或手机/平板人工验收；原计时服务实际一分钟断言继续执行，默认 v4 不变。

档位事项 `NextRoundOneTimeStoreTest` 使用真实文件 Room、账户与 socket，覆盖独立 complete/undo
离线链、原来源/设备/提交证明、完整 envelope 冷重试、在途撤销、较新轮头不改变事项状态及无
网络冷收据。缺 metadata、伪事项出生、坏档位/设备/无档位降级、旧单独 prepare/ACK、过期权限
与账户/取消均拒绝；来源/首次传输/晚期收据一致改游标与侧表跨业务触发器、真实延迟 FK 最终
COMMIT 故障整体回滚，原事实/历史/队列/指标草稿仍有证明。明确冲突保留原首项及依赖后继，
不产生成功事实或自动选边。完整相邻类包括原事项 local/accepted/sender/history/workflow、
普通档位/计时/核心 sender、目标删除、实际恢复/runtime、HTTP 与事项/档位契约；不改 schema，
不替代正式 UI/runtime、全量联合或人工验收。

完整 NextRoundSyncRuntimeTest 使用原子 NEW 普通/计时/事项 producer、真实 HTTP 与共用
Room 接受器，验证同一协调器先实际配置 ACK、完整原计时链，再计数/指标/事项原 CAS，最后
拉取实际事实及分摊并发布游标/成功时间。冷 bootstrap 必须无旧工作，混合来源业务 HTTP 前
拒绝；响应丢失冷重试不重复事实。暂时/永久拒绝保留原字节，不饿死独立数据且不虚报成功。
晚期拒绝/游标故障回滚，事实页失败不撤销此前真实 ACK；冷重试只拉取不重发已确认业务。
计时控制权冲突保留原完整本地终态与待发命令；拒绝缺 metadata/实际 reply 时间，晚期故障
不留下拒绝证明，不生成完成事实或分摊、不发布成功，实际完整拒绝仍允许独立指标上传。
完整相邻原 runtime、ordinary/timer/once sender、档位来源/恢复及 HTTP/契约继续执行。
内部协调定向通过不代替正式 UI/Worker、远端计时控制权、全量联合或人工验收。

跨事务初始身份由完整 NextRoundOperationStoreTest 验证：真实离线创建后冷重开、后续编辑/
计数/原事实撤销、实际创建 ACK 后同头票据继续可用，以及旧/未知来源、严格标志、实际
设备/依赖损坏和晚期原根来源故障全部拒绝/回滚；不写接受侧表或伪造收据。
完整 NextRoundTimerStoreTest 同时验证离线新建后原 start/pause/resume/stop 全链保留原规则，
只有实际创建配置 ACK 后才允许计时 HTTP，完整接受不回退本机终态或片段。
公开未认证 identity 探测不代表计时上传。计时后继的晚期仅创建来源损坏也须整体回滚原
本机状态/片段/队列/新来源，原创建证明仍可冷重试；不能仅复查“新动作”而漏掉继承出生。

卡片和指标详情的 UI 调整须保留功能信息回归：`HabitCardProgressTest` 检查长期完成/目标数值、
可访问进度语义、窄宽大字体换行及原打卡回调，并对正/倒计时执行开始、暂停、恢复、停止的实际触摸，
确认完成后不能重新开始且不误触卡片导航；`MetricCardTest` 检查空状态、展开/收起不误导航，
以及有无记录时三种目标方向。`MetricDetailScreenTest` 验证首次记录前后的目标/范围与配置可见，
并真正执行录入、备注、outbox、重开、删除取消和确认流程。布局变化可以更新过期的节点数量断言，
但不能删除业务断言或让用例一直在前置步骤失败；组件限定宽度测试不等于真实平板/分屏人工验收。
用户于 2026-09-23 确认计时数值文案不追加“已暂停”后缀；中英文格式测试按此显示规则验证，
仍保留时间补零、目标分钟和参数顺序检查，不据旧文案断言恢复已明确删除的后缀。
目标状态颜色像素测试在原 5 秒内要求连续两次捕获均超过 10 个精确色值像素，并断言最后一次
捕获的同一结果，避免等待后重新截图的绘制竞态；另验证不连续帧重置和缺色超时拒绝。
这不改变卡片 UI、主题颜色、导航回调或业务状态，也不放宽颜色/数量/超时断言。
首页 ViewModel 测试保留真实 Room，在小组件调度边界使用替身并验证打卡刷新请求，避免后台刷新
越过单用例数据库生命周期；调度器、刷新器、Worker 的专门真机测试不能因此移除。
创建习惯/目标、习惯详情、嵌套目标、关联指标提示及计时服务持久性测试使用显式的
`IsolatedWidgetRefreshRule`：它必须位于数据库、Activity/Hilt 规则外层，覆盖 setup、测试体和
teardown，最后恢复真实调度对象。测试仍检查真实业务数据/outbox，并断言刷新请求次数；
不能在全局 runner 或所有数据库测试中自动屏蔽 WorkManager。该规则自身覆盖异常退出恢复与
重复文件数据库生命周期，专门的小组件调度/Worker/刷新器测试不能使用它。
完整结果除 XML 计数外，还须检查 `SQLiteConnection ... was leaked`、`file unlinked while open`、
关闭后访问及致命异常日志；GC 报告所在用例不等于资源创建者，须结合前序请求链定位。
习惯详情与通知 ViewModel 测试在 clear 后须驱动受控 Main 并等待实际 owner Job 完成，
再关闭真实 Room、重置 Main；仅执行一次 runCurrent 不证明已取消的 IO 查询完成。
收尾等待有界且必须断言完成，不吞掉关闭后查询异常或将其归咎于下一条用例。

图标库页面及图片句柄从真实 IO 发布状态，Compose 状态收集显式调度到 Android Main，
不允许测试环境的 eager continuation 在发布线程同步执行布局。图标库 Hilt/真实文件集成测试
检查每次页面 composition 的主线程归属，保留安装、选择、像素和注销清除断言；账户授权
与短状态发布仍在原账户锁内完成，不把 Keystore 或文件操作搬到主线程。

网络监测回归使用真实 Android `Network` 身份值和 `NetworkCapabilities`，以手写观察源控制
`NetworkObservationSource` 的注册/注销、初始快照和失败，不在该类中模拟 Android framework 类型。
生产默认构造/Hilt 经 `AndroidNetworkObservationSource` 调用原 `ConnectivityManager` API；
回调、同步锁、路径选择和状态发布仍由同一 `NetworkMonitor` 执行，不在替身中复制。
真实系统注册/关闭 smoke 与现有同步持久性测试继续经过生产构造。身份夹具按
[Android 15 Network Parcelable 格式](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/android15-release/framework/src/android/net/Network.java)
构造，并断言完整 Parcel 消费、往返、相同身份的 equals/hashCode 及回调中不同实例互认；
这些合成身份不得用于真实 socket/网络查询。平台格式变化必须明确失败，不能回退为模拟身份
掩盖差异。保留 12 路径并发与全部订阅/断开/冷启动/关闭断言，不以减少并发、跳过用例或放宽时限优化耗时。

`NextRecoveryMigrationTest` 从已提交 v7 schema 经生产入口升级到 v8 并冷重开，逐列比较原 16 表、
原 DDL 和冻结请求，验证恢复表为空、伪身份拒绝、DDL/最终 schema 校验失败回滚后重试。
旧迁移测试继续比较其原始 schema 的全部列和约束；最终生产版本断言随增量迁移推进（当前 v14），新增恢复表
由独立迁移测试及新库测试验证，不纳入旧 DDL 比较。`NextCommonRestoreTest` 的恢复检查点测试
验证全部实体/事项历史与候选 cursor 的同事务提交、最终检查点 ABORT/IGNORE 和晚期实体失败回滚、
取消、重开及精确重放、认证/四类副本身份/旧代次/倒退 cursor/耗尽/非单例损坏行拒绝、并发 CAS，并保留真实
冻结事实、指标提示和无关计时命令。`accepted_data` 不代表在线恢复或人工进程杀死测试已完成。

`NextRequestMigrationTest` 从冻结 v8 schema 升至 v9，逐列与 DDL 比较原 17 表、原请求和计时、
事项绑定及恢复状态；新来源/传输表必须为空，不认领旧零尝试计时。缺表、schema 校验失败和
伪 `room_master_table` 身份不得提交部分迁移。`NextCoreRequestStoreTest` 验证真实本地业务、
outbox 与来源同事务，原字节首次发送/冷重开重放、认证与副本隔离、锁外 HTTP 和原 SQLite 类型。
最终 COMMIT 故障使用测试独有延迟外键注入：失败后不得发送业务请求，关闭并冷重开真实文件
再断言日志未提交、原业务/队列/来源仍在及可重试；不以 WAL 读连接可见性证明持久化回滚，
也不承诺框架连接在该类失败后可立即复用。生产 schema 不添加该延迟约束或全局关库恢复。
服务器返回 applied/already_applied 仍不是本发送层消费队列、接受远端实体或推进 cursor 的授权。
`NextAcceptanceMigrationTest` 从冻结 v9 升到 v10，逐列/DDL 比较原 19 表（含 BLOB 原字节）、
冻结来源/传输和恢复/事项证明；新收据为空，伪/缺失/额外/BLOB 身份、占用收据表和最终 schema
失败不得认领或破坏旧数据。`NextCoreRequestStoreTest` 另经真实 HTTP/Room/DataStore 验证普通
完整成功接受、冷重开、精确重复/并发、后继离线修改与先删除后确认、前驱阻挡、不可变事实原意图
证明、错误/残缺响应、来源和账户/设备/权限变更拒绝，以及收据和队列 ABORT/IGNORE/改写故障。
最终 COMMIT 的延迟外键测试须关库冷重开后核查无收据/无新影子、原队列/字节保留和重试成功。
新增普通接受路径不证明计时/事项联合确认、增量 cursor、恢复激活或人工验收完成。
`NextOrdinaryResultStoreTest` 扩展普通删除确认：四种完整墓碑、冷收据无网络重放、离线父删除后的
事实/关联影子确认、无来源缺行拒绝及已确认父删除的完整证明；晚期父证明改写、收据/队列
触发器和最终 COMMIT 失败须保留原意图并回滚影子。另验证合规撤销仍为新 immutable upsert，
历史单位修改不影响旧收据，连同摘要改坏原事实或墓碑的冷重放仍拒绝。删除冲突与同实体
前驱不自动重定基准；计时/事项联合确认、增量恢复及正式协议启用仍属后续接入门槛。
`NextTimerRequestStoreTest` 在既有真机文件库、DataStore 和实际 socket 夹具中验证计时原字节
发送/确认、完整离线转移、较早 ACK 不回退本地完成状态、取消后不复活、同会话前驱顺序及
冷收据无 HTTP 重放。完整结果的身份/状态/控制代次/序号/时间/有效时长须绑定原命令及已知
前驱；错误/损坏响应、取消/旧账户、队列/收据/本地状态触发器和最终 COMMIT 故障保留原工作。
实际恢复须匹配已确认 stop，再同事务保存完成事实/分摊/候选游标；错误事实、无收据、未解决
命令及写后的收据改写不能获得确认。定向通过不证明正式运行时、协议激活或人工验收完成。
`NextStructuralCausalMigrationTest` 从冻结 v10 升至 v11，逐列比较原 20 表（全部非空夹具及 BLOB
字节）和原 DDL，验证空因果表、严格 v10 身份、占用两表/索引、最终 schema 失败回滚和冷重试。
`NextSyncStateMigrationTest` 从冻结 v11 经生产入口升级至 v12并冷重开，比较原 22 表的列/DDL，
保留原 BLOB 请求与候选恢复状态，验证新活动游标/拒绝表为空、占用表/坏旧身份拒绝及最终
Room 校验失败的完整回滚和精确重试。旧迁移用例只更新最终版本和新增表排除项，不改旧数据断言。
`CountDayMigrationTest` 从冻结 v12 经生产入口升级至 v13，逐列/DDL 保留原 24 表及冻结请求，
新增日规则为空；占用表、伪旧身份及最终 Room 校验失败必须完整回滚且可精确重试。
`NextCountDayWorkflowTest` 验证新日规则、实际数量、outbox、原请求的同事务出生，目标增减和
正倒模式变更、全部撤销、跨时区同字面日期及冷重开保留原日规则；来源失败、取消和晚期规则
损坏回滚全部写入。结构前驱与首次计数须精确 ACK，已送达但未接受不能放行后续配置，冷重试
保留原字节。全部撤销后丢失日规则仍被保留的原请求阻挡，不从当前目标重建。
回执/队列触发器在事实恢复之后改坏或删除日规则，仍须回滚接受并保留原网络字节，冷重开后
可精确重试。候选恢复检查点及实际增量游标的晚期触发器也覆盖同一故障，不能确认损坏规则；
保留数量、原请求与原游标，移除测试故障后正常接受。
普通映射/恢复相邻类验证严格规则、原回执匹配、已撤销事实恢复、已知/未知规则混杂拒绝与
晚期恢复故障整批回滚。`CountConsumerWorkflowTest` 使用正式仓库/服务及文件 Room 验证当前
可编辑配置与每日展示规则分离、目标和方向改变不重算达标/连续、录入与撤销返回提交后状态、
全部撤销/冷重开保留原规则、实际数量超过 Int 范围仍精确、历史逐日目标、未知历史、坏原规则/
原始数量越界及换账户拒绝读取。
`CountFactEvidenceTest` 在真实文件 Room/账户/原请求上检查合法范围内的数量、日期、时区、
发生时间及原 SQLite 浮点/事项字段损坏，第二条来源缺失/跨账户、重复有效 UUID，均只读
拒绝且不改事实/规则/outbox；129 条真实独立事务覆盖原请求 128 条分配边界，不截断历史。
旧未知历史及跟踪类型编辑前的打卡保留实际量、不推测目标。`NextCommonRestoreTest` 另经
实际接受恢复检查无本地来源的两个计数、冷重开、投影/快照摘要损坏及已撤销投影复活拒绝；
合法同日历史打卡与计数共存必须保持原有效量聚合，打卡快照不被补上 count_policy。
这些定向断言不代表服务端真连接、正式协议启用或全量/人工门禁。
2026-10-08 来源证明批次先执行 7 个完整类、75 条，全部通过且无跳过，证据
`c23658be31bc4009ad18d779fab656ad/targeted.json`。随后补齐合法同日历史打卡与计数
共存回归，仅重跑来源证明及实际接受恢复两个完整类，40 条全部通过且无跳过，最终证据
`36db4b56bd07439aad93a8d6d620d24c/targeted.json`；构建/lint 及隔离测试壳清理通过。
两次结果各自保留源码/APK 与发现身份，不合并成全量通过；正式 APK、协议激活及人工验收未执行。
同批后端执行计数、原生产同步、迁移/事务及物理/逻辑归档的 11 个完整模块，313 条通过，
169.68 秒；保留告警插件，唯一开发 JWT 告警在既有预算内。锁定 Ruff/格式、224 文件 mypy、
当前/next OpenAPI 及根 51 条工具回归通过。该范围不代表全部后端或联合门禁。
`HabitCardProgressTest` 的计数用例验证原始倒计数规则、完成后不再提供倒计数动作、
Long 正计数继续录入以及未知规则只显示实际数量，不捏造目标。
消费者批次同时运行完整相邻统计、录入协调、主界面筛选/窗口、详情、目标树、图形及小组件类。
`CountReminderWorkflowTest` 使用同一真实账户/文件 Room 与原规则证明，仅替换 OS 边界，检查
配置变更/冷重开、全部撤销后保留今日目标、午夜无声重读新日目标、5000 次同窗合并和完成
跳过、旧认证/副本/习惯身份、旧日期/时区/计划、通知开关、未知历史及事项完成/撤销后均不
捏造契约未支持的 preferredLocalTime 提醒；观察
业务变化重建、退出后清除通知，并断言调度/通知不处于 Room 事务、不改事实/outbox。
进程启动须保留同代次当前账户的未读通知，重新认证时清除旧代次且旧唤醒不能再次发布。
同类还验证提醒点击的账户/代次/副本/UUID、删除及退出拒绝，冷重开、完整 Long ID、
主线程跳转且不在 Room 事务内、不改事实/outbox。
令牌解密线程断言覆盖 Keystore 前的快照读取，账户验证在 IO 完成，主线程仅做受保护的跳转。
并发账户切换须等待主线程跳转结束，随后旧声明被拒绝；子协程异常必须向测试传播。
`ReminderNavigationEffectTest` 使用真实 Compose/NavHost 覆盖冷图等待、已打开 app 的连续声明、验证拒绝不改变路由、返回不重复
及登录界面不读取个人数据；Intent 解码和未消费声明的 Bundle 恢复另由提醒计划类验证。
`CountingSlotCalculatorTest` 对原逐槽公式做完整对照，覆盖重合窗口、边界和下一未完成槽；
Int.MAX_VALUE 目标保留全部逻辑序号但只查询有限时间边界。`HabitReminderPlanTest` 检查
周/月/间隔和 DST、目标一及非计数的原提醒时刻、真实 PendingIntent 的 Long 身份隔离、
冷取消且不新建、账户/日期/窗口 tag、Long 通知文案及非法/旧广播拒绝。
提醒定向批次还覆盖完整相邻仓库、账户消费者、计数原始事务、优先级、主窗口和生产 Hilt
接线类；中英文资源测试保留参数含义与计时文案断言。构建失败不能算真机用例已执行。
生产 Hilt 用例在真实共享账户锁内清空隔离测试库，验证日规则/outbox 一起清除、认证身份
未被篡改及真实 PendingIntent 消失；清理只在 COMMIT 后取消 OS 任务，不嵌套账户锁或在
主线程执行磁盘提交。故障夹具必须同时更新提醒分钟和 wire 元数据，不能放宽生产校验。
这些定向范围不证明正式 v5 联合启用或人工验收；不能拼接旧结果当作全量门禁。通知实际
展示/点击、系统延迟、重启和手机/平板集中验收仍需另外记录，不以替身断言代替人工结果。
`DeviceCalendarTest` 检查前台午夜等待、同日时区/时钟变化失效、取消及 DST/跳过日期；
`CalendarRefreshEffectTest` 使用真实平台接收器及 Activity STARTED/STOPPED 生命周期，检查
释放、恢复遗漏事件和偏好检查点失败后继续观察。测试时间源受控，不修改手机系统时间，
手动调用捕获的广播回调也不冒充真实系统时区事件。`CalendarConsumerRefreshTest` 使用四个
实际 ViewModel、账户和文件 Room，验证没有数据库通知时的重新读取、损坏规则显示可重试
失败而非成功空列表、恢复及失败计数动作不改事实/outbox；受控日期只是失效信号，不替换
消费者真实业务时钟。`DataReadMonitorTest` 验证源重新订阅、投影失败恢复和取消传播；
`DataReadFailureTest` 检查窄窗大字体下错误提示及重试可达。构建/定向结果须另记实际证据，
这些用例不等同于全量门禁、正式 v5 联合启用或人工跨日/时区验收。
2026-10-08 此批定向矩阵实际发现/执行 15 个完整类、94 条：90 通过、4 个新夹具失败，
无跳过。计数集成夹具补齐真实计时授权依赖并保留该计时习惯的进度断言；生命周期夹具
在移除组合后推进 Compose 测试时钟，等待实际释放，不提高等待预算或放宽断言。
仅复测两个受影响完整类，6 条全部通过；其余 13 类未重复。首轮证据
`9aa65ce840004805ac8f0a43c64da25c`（XML 由下轮移入 prior-results），复测证据
`0f78c4d6766d47b1840dc7fab8bd96e5/targeted.json`；两轮生产源码一致，构建/lint
无新增问题，测试壳清理确认。不得将两轮拼接为“94 条全量通过”或正式人工验收。
`NextSyncRuntimeTest` 使用既有真机文件 Room/DataStore/真实 socket 完成上传→全量协调→
连续增量页→冷重开，检查活动游标与 v4/候选状态分离、同实体多版本/删除、清洁缓存清理
与原工作保留、明确拒绝隔离与无关事实接受、响应丢失原字节冷重试，以及游标 ABORT/IGNORE/
晚期缓存改写和最终 COMMIT 失败的整页回滚。联合完整计时/事项完成撤销恢复及事项增量
缺边拒绝均经过真实运行时；授权/设备、非法原 SQLite 整数、旧代次和取消不能推进游标。
暂时计时前驱缺失不得写永久拒绝或更新时间，冷重开后原字节可被正常接受。
该批执行完整受影响类矩阵，不重复运行全项目；正式消费者、协议激活和人工联合验收未因此通过。
`NextObjectEditorTest` 覆盖真实习惯/目标/指标编辑 ViewModel、文件 Room/DataStore、原子原请求、
实际 HTTP 接受及冷重开，验证账户过期、远端结构改变、只读权限、素材归属/用途及来源写失败拒绝，
并保留未显示的规划字段和事项投影；提醒分钟变更须与 wire 时间一起保存。
`ObjectAppearancePickerTest` 通过真实 PNG/SVG 安装与对话框触摸选择固定引用和 tint，验证任务用途
隔离、旧认证回填拒绝及缺失引用保留。它们使用既有显式小组件隔离规则（位于数据库/Activity 外层），
真实目标/事项编辑仍断言刷新请求，取消并等待 ViewModel 与图片 IO 后才关闭库。
这仅验证类型化编辑链，不代表正式 v5 切换、全部事项消费者、配置包或人工验收已完成。
`NextObjectCreationTest` 使用同一真实存储/图标/HTTP 夹具，从四类实际创建 ViewModel 检查五种
习惯模式、只读子草稿与一次图提交、显式未完成事项、指标范围/聚合及真实 ACK 冷重开。
还验证晚期来源失败整体回滚、精确目标重试、认证/权限变化、恢复草稿过期及用途/混合协议拒绝。
入口 ticket 由隔离测试显式签发，不代表默认导航已经启用 v5、完成正式注册或人工联合验收。
`OneTimeWorkflowTest` 从实际完成服务、状态读取及指标协调器验证事项保留、跨日撤销、归档独立、
手机筛选/目标子项/实际详情状态、不计每日进度和连续天数，以及草稿关闭后冷恢复。
指标提交验证原 v5 来源与事实/队列同事务出生、来源失败回滚后以冻结时间和身份重试及真实 HTTP 接受；
过期显示动作/认证、已知权限撤销、拒绝队列及缺失指标不能伪造事实，缺失指标仍可显式跳过提示。
共享服务、协调器和相关 ViewModel 的完整类作为相邻回归；指标弹窗另以真实 Compose 触摸验证
原始输入恢复、编辑回调和显式刷新。此范围不等于默认协议切换、小组件动作链或人工布局验收完成。
`NextBusinessWorkflowTest` 经真实习惯/指标详情 ViewModel、仓库、Room/DataStore 与 HTTP 验证普通
事实录入/撤销、原请求确认、冷重开、聚合与关联操作。晚期来源失败必须回滚观察批次、完成及
重新激活；混合协议批次、过期 UI authority、结构权限缺失及普通入口提交计时结果不得产生部分数据。
指标删除验证本地级联墓碑与原来源同事务保留；它不证明目标/事项级联删除、计时历史清理或正式
运行时切换已完成。相邻完整类覆盖现有仓库、编辑器、详情界面、一次性事项和完成服务，不重跑
未修改的传输/迁移矩阵来代替正式入口接入。
`NextPlanDeletionWorkflowTest` 从实际仓库、删除确认和编辑 ViewModel 经文件 Room/DataStore/HTTP
验证完成事项删除不变成撤销、待删立即隐藏、同名重建、原事实先确认、显式目标级联/脱离与
原创建/编辑前驱版本，以及关联/指标草稿、运行中计时保护、旧账户与只读拒绝、晚期来源/接受
失败回滚和响应丢失后的冷重开原字节重试。同步内部读取仍能访问保留行，普通用户读取不能。
本批扩展原结构因果链覆盖一次性事项结构、新计划及关联删除，保留原严格完成事实路径和未知工作拒绝；
因此集中执行完整因果/请求/事项类及正式仓库、编辑/创建、运行时和受影响消费者相邻类，
不因未改变 Room schema 执行全部迁移，也不声称默认 v5 激活、拒绝解决界面或人工验收完成。
`ProductionWorkflowInjectionTest` 使用生产 Hilt 图、隔离文件 Room 和真实账户偏好验证创建、
类型化编辑、事项完成/指标提示/撤销、目标脱离与删除的原来源，同时验证只读/旧认证回滚和旧 v4
读写路径。它不手工构造带可选依赖的仓库，不以构建通过证明正式图的业务协作者已接入；
源码与模式未变的传输/迁移矩阵不因依赖工厂修正重复全量执行，正式协议激活仍须联合验收。

`NextRoundGoalDeletionTest` 使用实际类型化编辑入口、文件 Room 和原 HTTP/共用接受器验证
目标解绑/级联的逐项子接受前沿、独立事项无出生、真实计数/日规则先确认、原合法 replacement、
冷重开丢响应的原字节重放、缺失/损坏收据与来源拒绝、未知远端子集合明确冲突，以及晚期
子证明故障回滚父移除/墓碑/接受/队列；明确拒绝落库也前后审计原子接受证明，晚期损坏整体回滚。
旧编辑票据不能升级，新票据须绑定已接受 profile；
目标确认绑定原子节点集合，新增独立事项不能被旧确认级联或解绑。
本批集中验证完整相邻结构因果/普通请求/编辑创建/删除/计数/事项/计时/运行时及领域类；
无 Room/API/后端模式变化。后端完整挑战档位与相邻结构/事项/原同步、最终提交和快照模块
覆盖实际 JWT/API Token 下未完成/已完成的独立子事项、两种删除政策、明确冲突不改变业务、
子操作接受后原拒绝仍冻结、显式新确认及真实完成事实保留；未放宽原 wire 或回写旧收据。
局部风险矩阵不重复整套后端，不代表正式激活、全量或人工验收。
2026-10-09 本批授权 MI6/API35 首次完整定向门禁为 16 完整类 214/214，0失败/错误/跳过，
物理任务13m43s；receipt `17d98384840e47c7bc56829d09234ad6/targeted.json`，根51项、
build/lint/warning、实际发现/APK/XML/新覆盖输入及隔离测试壳清理通过。最初新夹具构造参数
错误仅导致构建失败（276da0e755224652aa49e5136bbb5de3），无行为通过；随后在静态复查发现
显示子集合缺口时，验证过的包装进程 SIGINT 并 join/清理后才修改，已执行的29/213项
（9089acd3e68947ab8744eaa278e8c97d）不作为通过。该单次完整214项包含该保护及新13项类。
提交前补齐明确拒绝前后子证明审计和第14项故障回滚用例，最终源码执行目标删除、普通请求及
档位运行时三个完整受影响类，71/71通过，无失败/错误/跳过，物理任务4m24s；receipt
`64d37316fe3f4195a005c4d866b2dc42/targeted.json`，根51项、构建/lint、发现与证明输入及清理通过。
原长因果链与未变的相邻消费者不重复；不把两次结果拼接为最终源码215项矩阵或全量通过。
后端9完整模块226/226，62.77s，warning budget 1 observed/1 allowed；锁定 Ruff检查/格式、
全241文件mypy及current/next OpenAPI通过。该局部 pytest 明确 `--no-cov`，非全量覆盖率。
后端源码之后未修改；无正式安装/清库/部署/本机Docker/自动合并。

`NextTimerStartOrderTest` 以真实文件 Room/DataStore/socket 验证开始前编辑的完整 ACK、开始 ACK、
开始后编辑的跨队列顺序，直接发送同样不能绕过；响应已送达但未接受仍阻挡后继，冷重开保留
原 ID/字节。`NextTimerWriterTest` 覆盖开始目标增减不重算当前快照、旧显示配置/账户/设备/已知
权限拒绝、状态/片段/命令/来源同事务回滚，以及旧通知/确认不能改动新会话、来源损坏不回填、
无关保留来源不影响当前证明，合法 ticket 搭配其他会话/序号的输出同样整体回滚。
真实状态 Flow 在旧显示配置、认证替换与延迟清理时禁用旧动作而不终止，重新认证后可恢复。
`WidgetTimerReadTest` 在真实账户/文件 Room/原开始请求上验证小组件只读快照：开始后修改目标与
正倒模式及冷重开仍用原规则，倒计时只减本会话时间，旧对象、缺失/损坏来源和换账户拒绝且
不修改会话/命令/原来源。`ProductionWorkflowInjectionTest` 另通过正式 Hilt 与两个小组件的真实
Glance DataStore 入口检查冻结目标/方向、来源损坏后隐藏旧操作区及恢复后重新读取；取消其
真实焦点刷新闹钟后才关闭文件数据库。状态断言不等于桌面 RemoteViews 点击/绘制、账户素材
或全部小组件动作链已验收；这些联合入口仍须另行完成。
停止派发不等于保存完成；指标提示须等到同一会话真正完成后
在原账户发布，暂停或认证替换不能显示完成提示。相关同步请求与运行时、原 v4 计时和两个
实际 ViewModel 的完整类作为相邻矩阵，构建/lint 不代替这些行为断言。

`NextRoundWriterWorkflowTest` 验证实际新建图/指标、独立事项完成撤销及持久指标草稿、本机计时
writer 的显式档位来源与原接受链，含冷重开、未接受创建前沿、旧 plain 表单/动作不能升级、
旧设备/损坏 profile、目标 SavedStateHandle 恢复不能换档位、原开始政策/出生与严格序列化声明、
来源失败和第二 observation 改坏前条来源/事实/提交证明及最终草稿的晚期故障整体回滚。
草稿提交失败保留原时间/身份/输入，不制造服务端接受。相邻完整创建/编辑/目标删除、
事项/提示、计时/小组件声明、实际计时服务、Hilt 图和档位运行时共同回归；不重复未改的长结构
因果链/Room迁移/后端矩阵，不把局部接线称为正式协议激活、统一本轮统计、launcher 或人工验收。

2026-10-09 实际 writer 批次首轮 18 完整类175项，172通过/3失败/0跳过，物理任务14m54s，
原始证据 `7b9fd42a55974c1db318c143cdb75674`（失败 XML 随下一运行轮转保留）。调用栈确认新目标
空 activity 的生产读取缺陷；新取消响应夹具遗漏1ms，另有原计时切换确认5s超时。此前修正事项
响应夹具不能替代该生产缺陷。草稿隔离补例的构建阶段经验证进程取消/join后调整，未发现/执行行为
（`afe1a3b6b0754c2f86a1b6d2c7e062f1`），不记通过。随后三个完整类29项，28通过/1目标缺陷失败，
0跳过，7m12s（`71453f07a1024945892258db4b42333b`）；原计时服务8项和创建8项全部通过，
确认窗口源码、断言和5s预算未改，超时未复现，不宣称已定位/消除间歇性问题。
修正目标类型判断并补整个指标批次末尾事实/提交/草稿审计后，最终源码执行四个完整受影响类：
`NextRoundWriterWorkflowTest`、`NextRoundOperationStoreTest`、`NextRoundGoalDeletionTest`、
`CompletionMetricPromptStoreTest`，62/62通过，0失败/错误/跳过，4m41s，receipt
`e3ed7ce5eb3d4e1f887fe91c0689667a/targeted.json`。根51、build/lint/warning、准确发现/APK/XML/
新覆盖输入及隔离壳清理通过，未发现实际测试日志资源泄漏/致命错误；源码随后未改。
不重复未变计时服务/长结构链/后端，不拼接为最终176项或全量绿色；正式启用、联合及人工门禁未完成。
上一批目标删除精确提交 CI `37822032767` 在25分钟上限取消，后端2836条 PASS 日志、93%，
不是完整后端结果/覆盖率；同提交 hosted Container `37822032502` 成功，不代表本机Docker验证。
不盲重跑未变源码、不扩时/过滤用例或拼接；后端全量容量整改须另批保留完整语义和校准门禁。

`TimerServicePersistenceTest` 通过生产 Hilt 图和实际前台服务/通知，真实运行一分钟后测试正计时
配置修改、暂停/服务粘性恢复、旧 PendingIntent 拒绝及 stop 后完整事实/日分摊再发布；另验证
倒计时在配置改成更长正计时后仍按原一分钟自动完成。不得加速业务时钟或补造已完成结果。
后端完整 `test_v5_production_sync.py` 模块同时验证 JWT/API token、正倒计时、目标增加/减少、
缺少/不匹配/非法快照、提交失败及精确旧回执重放；状态/分摊保留，下一会话才使用新配置。
旧未携带快照的原请求指纹保持不变。该定向矩阵不代表正式 v5 切换、小组件动作接入、全量
联合或人工验收完成；计数目标增减与历史达标规则由 D-017 及独立计数矩阵验证，不能以计时
验证代替。

`WidgetFactActionTest` 使用既有真实文件 Room/账户/原意图夹具，串联打卡及撤销的显式 CAS、
冷重开、计数连续点击与倒计数归零、D-017 编辑/全撤销后的原日规则、账户/副本/设备/日期/
时区和同 ID 不同 UUID 拒绝、事项完成/撤销与持久指标提示、普通指标元数据/事实变化和精确
弹窗重试、目标确认及原来源插入失败的整笔回滚；严格声明解码不恢复任何真实操作票据。
`WidgetFactEntryTest` 使用正式 Hilt 图、CheckIn/Counting/Focus 的真实 Glance DataStore、实际
操作 Activity 和真实 Compose 目标确认触摸；验证旧操作不二次切换、当天冻结显示、配置变化
拒绝后退出、账户切换关闭原确认，以及真实提醒在确认归档后取消/重新启用后恢复。
完整相邻仓库/编辑器/计数/事项/完成服务/指标协调/旧小组件回调和计时服务类一起
按风险验证。该矩阵不代表桌面 RemoteViews 的厂商绘制/点击、重新激活、新协议激活、全量或
人工联合验收通过；构建/lint 不代替真机行为结果。

`NextOneTimeRequestStoreTest` 使用既有真机文件 Room/DataStore/真实 socket 夹具，串联离线
完成/撤销、原请求发送、完整成功/拒绝和 cold 收据。核对原字节、队首顺序、在途后继、锁外 HTTP、
取消/响应丢失及冷重开，v4 零准备、旧来源/绑定不认领，错完整事实和账户/副本/设备/权限变化拒绝。
收据/队列/提交凭据/本地事实故障和首次日志/接受最终 COMMIT 失败保持原工作；父已删仅新删除
证明允许 shadow-only，晚期证明改写须回滚。拒绝保留原响应、阻挡后继、不冒充成功；冷收据连同
摘要改坏正文仍拒绝且零网络。事项及相关恢复按完整受影响类集中验证，不因此宣称统一运行时、
正式 v5 激活、全量门禁或人工验收完成。
`NextStructuralRebaseTest` 在真机验证纯字段合并与原子策略组、Decimal/完整权威结果和实际前驱 ID 绑定。
`NextStructuralCausalStoreTest` 经真实文件 Room/DataStore/loopback HTTP 验证多段离线同字段编辑、
远端独有修改、真实重叠保留、逻辑/物理顺序、在途新增修改、并发唯一替代、响应丢失及冷重开原字节
重放、原逻辑 ID 的无网络收据重放、零尝试已有日志不替代、设备/epoch 隔离、来源/因果/退役/日志
及接受的 ABORT/IGNORE/改写故障、最终 COMMIT 故障关库冷读与精确重试。另验证同事务
32 次编辑的显式前驱、习惯/关联的实际后继、取消未知替代后的原字节冷重试，以及超限
快照/来源和非法整数在完整 Room 读取前拒绝；事务内前驱定位复用不取消提交前的逐项原始摘要核验。
长链纯计算复用限于单次调用内按精确原文匹配的解析与合并，合法 JSON 的晚期改写仍须回滚；原摘要编码以固定 SHA 和
真实 SQLite 行黄金值核对，不能通过改变摘要、减少 32 次确认或增加时限改善耗时。
只读证明遍历只在其无写入的同一 SQLite 快照内复用有界摘要及已审计的元数据行，退出即失效；写入/触发器后的
独立遍历须重新取得原始证明。晚期来源/前驱/退役/日志/确认改写测试同时验证这一边界。
传输/回执的批量 Room 读取仍必须先通过同批原始类型/长度/摘要审计，计入同一个 256 项/2 MiB
只读元数据预算；operation ID 的来源存在性与原队列 ID 的缺失分别证明。真实冷链回归验证坏
传输整数、BLOB 回执及不同物理 ID 的残留来源仍被拒绝，精确修复后才能重新遍历。
同一协调器/事务的暖遍历另验证合法原文改写、规范快照的整数→字符串及坏 SQLite 整数均被重新拒绝，
精确恢复后仍可完整确认；不会用重新创建协调器掩盖缓存失效错误。
暖遍历还在保持摘要绑定一致时改写前驱回执，验证坏字段类型和会改变合并结果的合法远端字段
均不能命中旧结果；切换设备仍拒绝，精确恢复后可继续。纯计算复用不替代当前授权或数据库证明。
多段已确认链另在暖遍历后连同摘要绑定一起改坏中间回执，末端的合法 ACK 不得掩盖它，恢复后可重新遍历。
单次调用的纯值容器另在多个已提交事务/新协调器间复用，验证连同摘要一起更改的合法回执、坏 SQLite
类型和当前权限仍重新拒绝；修复原行后可完整确认，不新增网络请求或留下队列。
无替代链的初始结构操作另在冷重开后，连同摘要一起改坏规范字段类型/必需字段，验证两种实际重复确认入口
均拒绝、投影/影子/cursor 不变且零网络；恢复原回执后可正常重放。
初始结构操作还验证低于冻结基准版本的 applied/already_applied 回执不得消费工作；冷重开重试保持
原发送字节，合法较新版本可完整确认。连同摘要一起改低内外版本的已保存回执仍拒绝零网络重放，
投影/影子/cursor 与原坏行保留；精确恢复回执后才可重放，不以恢复层忽略旧快照代替确认校验。
有界批量审计与逐行审计逐项核对真实来源/依赖/退役/接受、BLOB 原日志和缺行摘要；超过 2 MiB
批预算回到完整逐行核验，单行超限和坏 SQL 类型仍拒绝，黄金摘要不变且原数据保留。
完整来源快照另覆盖两类 outbox 的 129 行跨批边界、第二批坏整数拒绝、同事务写后摘要变化、
删除后的 AUTOINCREMENT 水位及冷重开；超过批字节预算仍返回全部逐行摘要，不能截断队列。
断言原来源/ID 保留、退役不产生原 ID 的 ACK、业务不重复、cursor 不推进，孤儿新元数据阻挡全部 11 个旧修改入口。
基础请求与新增因果用例按职责分别位于 `NextCoreRequestStoreTest` 与 `NextStructuralCausalStoreTest`，
共享不含测试的抽象 `NextCoreRequestFixture`；拆分本身不改变原基础测试身份或任一方法体/断言。
历史工作缺少新来源时，因果入口仍须保留 `OLD_INTENT` 的明确拒绝；原发送及发送/确认入口
与冷重开重试均验证零业务 HTTP、无传输/确认、原旧队列完整保留，不转换为泛型参数异常。
全量门禁的完整发现与固定三批执行仍覆盖全部用例，不使用类/方法过滤或另行增加批次。
当前内部因果链交付按 2026-10-07 风险规则使用定向模块矩阵：全部受影响的迁移/新库/兼容链、
基础请求和因果存储/合并、结构与事实映射/恢复、协议激活屏障、TokenManager 及核心真实 HTTP
共 22 个完整测试类。一次运行同时验证定向工具入口，保留 32 段真实链的全部确认与原期限。
只记录当前模块通过，不沿用此前取消的三批结果；全量联合门禁留到正式接入/阶段收尾。
这些内部入口仍未接入正式调度，不把自动化用例等同人工联合启用验收。
旧协议门禁另覆盖仅有 awaiting/accepted 检查点及损坏非单例行时的全部 11 个修改入口，
验证零网络、业务/队列/偏好和原检查点不变，以及隔离测试库账户清理包含检查点。

`AccountIconRepositoryTest` 使用独立文件 Room、真实 DataStore 与账户锁，覆盖账户/服务器/epoch
隔离、登录代次/设备/权限版本失效、刷新、未知权限拒绝、不可变冲突与并发精确重试、UTF-8
元数据和去重字节额度、降低限额、ABORT/IGNORE/日志身份改写的同事务回滚、损坏元数据和
缺失日志拒绝、SQLite 超范围整数/非整数/异常列类型在 Room 强制转换前拒绝、取消/排队旧回调，
以及业务库清理/退出后独立素材记录保留、真实设备主题选择不变与冷重开。
真机查询回调验证每次目录读取是一次新标量审计及五次真实行查询，暖调用仍全部执行；跨账户
旧上下文在 SQL 前被实际凭据拒绝，新 namespace 不读取旧 owner。合并审计另检查 UTF-8 字节/
数量、各表坏 SQL 类型/范围、ready/选择值和副本隔离；拒绝后原损坏值不变，恢复后重新读取。
暖解析须验证合法原文变更、重复 JSON 键、事务回滚；复用解析不能掩盖实际行变更，SQL 类型、
安装意图、ready 与选择仍逐次审计。
新库测试比较导出的当前 v5 身份，拒绝未知版本和伪 schema，均限定 `com.dayforge.testbed`。
`AccountIconMigrationTest` 从已提交 v1 schema 经过生产入口升级，比较原三表的全部行/DDL/
operation ID、最终 v5 identity、空 ready/selection/transfer 和账户复合外键；DDL 阻塞和最终 schema 校验失败须回滚
至原 v1，精确移除测试障碍后能重试，不通过清库取得成功。
升级前显式核对冻结 v1 identity；伪造、缺失、额外行及 BLOB 类型不得随迁移被覆盖成有效身份。
直接 v2→3 独立验证原四表全部行/DDL/ready 保留、空 selection、复合外键及冷重开；冻结 v2
身份伪造/缺失/额外/BLOB、selection DDL 阻塞或最终 schema 失败必须保留 v2，精确修复后重试。
同名完整 selection 表及其已有选择行、被其他表使用的同名索引（含大小写变体）必须拒绝并保留，不能按合法
最终 DDL 静默接纳未证明的偏好；测试仅移除自己明确注入的对象后验证迁移可重试。
`AccountIconStoreTest` 使用真实文件 Room、DataStore、PNG 解码/SVG 绘制和 syscall 故障验证
三维 namespace 字节隔离、裸 hash/非归属/权限拒绝、安装回读及冷重开、不可变重试、并发实例、
ready ABORT/IGNORE/改写回滚、发布后 fsync 失败、部分写入/精确恢复、未知文件保留、逐级链接/
FIFO/目录拒绝、ready 缺失/损坏与 pending 损坏拒绝清理、清理失败可重试，及实际 4096 文件/
512 MiB 物理清单边界。事件屏障验证阻塞 I/O 期间退出/重新认证不会被账户锁阻挡，旧安装不提交
ready、旧读取不返回字节；取消等待真实 I/O 收尾并保持第二仓库实例互斥。
独立调度屏障另覆盖 I/O 已结束但调用方线程尚未收到结果时重新认证：旧读结果拒绝返回，
已提交安装保留真实 ready 而返回会话失效，不能因调用失败虚构数据库回滚。独立 Room 连接
须在文件阶段成功提交新元数据，证明没有跨图片 I/O 持有数据库写事务。
这些不验证缓存、在线素材接口、生产启动调度、人工断电或清库，不能据此宣称 #259 已全部完成。

`AccountIconTransferMigrationTest` 从冻结 v3 经生产入口升级至 v4，逐列/DDL 比较全部五张旧表，
验证空传输队列、身份、外键和冷重开；伪/缺失/额外/BLOB 身份，同名外来表、视图及索引
（含大小写变体）和最终 schema 失败均须保留原版本及数据，精确移除测试障碍后可重试。
`AccountIconTransfersTest` 使用真实文件 Room/DataStore/原生 PNG，验证本机 ready 不冒充服务端确认、
包的原子入队和声明依赖、稳定 operation ID、冷重开/中断代次、精确响应重放与身份/内容错配拒绝，
四类错误捕获快照及跨设备重绑尝试不得确认或释放实际意图，同 UUID 的素材与包保持类型独立，
以及账户/副本/设备/权限变更、只读下载的真实字节证明、完成下载的显式代次重查与旧回调拒绝、
损坏文件保留、服务端确认不可借重查撤销、配额降低、原始 SQL 类型审计、
ABORT/IGNORE/身份改写/跨表副作用整笔回滚、代次耗尽、并发实例与取消。插入阶段的真实查询屏障
必须等待 Room 工作结束，再验证全部意图回滚和冷重开；错误依赖装配在写入前拒绝。
这些是持久队列和严格响应值测试，不是 HTTP 或生产 Worker 已接入的证明。

`AccountIconHttpTest` 使用真机实际 loopback ServerSocket/OkHttp、生产认证附加/刷新、真实文件
Room/DataStore 和原生 SVG/PNG，验证独立原始 JSON 的路径/body/query/版本/凭据、公开身份无令牌、
v4 零领取/零恢复、素材/包/双变体确认及冷重开、只读已归属下载与实际 domain 入口。
响应身份/内容/类型/charset/重复键/未知字段/整数类型、压缩/重定向、声明长度/未知长度 chunked
超限/截短、hash/MIME/cache/nosniff 和实际原生坏图均不得确认。另覆盖真实 401 刷新、坏/超限刷新
不保存凭据（含控制字符）、非法本机 access 与在途 401 期间的坏凭据替换不进入重试或泄露原值、
旧账户 401/同账户重登的晚响应拒绝、四类副本/权限变化、可变地址拒绝、会话逃逸、
主请求及刷新取消关闭实际 socket 后 join、响应丢失/同身份重试、中断 sending 恢复、并发所有者
串行及真实 32 项限额后继续原身份。测试服务器等待并关闭自己的全部线程/socket，清理失败传播，
不吞掉晚期断言。明确信息及临时 503 的队列状态和未变元数据分别核对；不使用 MockWebServer/JVM。

另验证真实下载 `409 ASSET_CONTENT_PENDING` 每批仅尝试一次且保留身份/元数据/字节，冷重开后
字节到达可完整确认；等待下载不能饿死声明/上传，固定 32 项预算不变且跨批采用 generation 公平性。
错状态/错 kind/其他 409 仍阻塞，内容等待与后续网络/账户失效不虚报完成或跨会话释放。
这些测试不是生产 v5 服务器/版本门禁、Worker 调度、实际 LAN/WAN 或人工验收。

`AccountIconCatalogMigrationTest` 从冻结 v4 经生产入口升级到 v5，逐列/原始类型/DDL 比较全部六张
旧表、ready、选择和确认，验证新目录为空、当前身份、外键与冷重开；旧迁移继续证明 v1/v2/v3
整链至当前版本且不放宽原断言。错误/缺失/额外/BLOB 身份、错误 master ID、同名合法外来表/视图/
大小写索引以及最终 schema 失败都必须保留旧版及数据，精确移除测试障碍后能重试，不清库。
`AccountIconCatalogTest` 使用真实文件 Room/DataStore、实际 HTTP 和 domain 入口验证只读目录接收、
重复 hash/全变体意图、四页预算和冻结 through 冷续页、后来追加、精确重放及真实下载后的原生字节。
稀疏序列、引用乱序、重复身份、不可变错配、错类型/重复 JSON 键/上下文、旧 CAS 均拒绝，
v4/未来版本不请求目录；空末页不消耗代次。元数据/预留/包/意图/日志/检查点各阶段 ABORT/IGNORE，
最终触发器改写/伪 ready/选包和额度拒绝须整页回滚；坏 SQL 类型/超范围、缺历史/意图拒绝且不修复。
原 mixed pending/sending/blocked/complete、完整确认字段/UUID/ready 保留，两个真实 Room 连接
并发精确投递可在新事务重试；最终真实 SQL 屏障中取消须 join 原工作并冷重开验证全部回滚。
最大合法四包 HTTP 响应测试仍执行 128 素材/256 角色/最长 Unicode 名称与双变体，不安装字节或猜归属。
这些不表示生产 v5/Worker、正式服务器/外网或系统提供者/手机平板人工验收已完成。

`AccountIconBackgroundTest` 使用真实文件 Room/DataStore、原生 SVG、实际 loopback HTTP 与正式
domain/CoroutineWorker 结果，验证无效授权零初始化/零 HTTP、只读冷目录→下载、v4/未来版本零
恢复/领取/暂存清理、精确 v5 清理且保留未知文件、原 operation ID/CAS 冷续、真实 peer 中断和
取消 socket 关闭/join、仅自身期限返回 retry、32 项及 4 页共同轮次预算、永久/临时/内容等待
分类、损坏已知字节/坏队列先停止并保留现场、晚期账户替换不发布，以及 blocked 不饿死独立意图。
真实导入 syscall 屏障证明文件安装结束前后台零 HTTP/领取，失败仍唤醒且显式重试保留全部
意图；真实凭据刷新/无关设置不发布新素材身份，退出发布无效授权。
`AccountIconWorkerTest` 保留有限结果及 retry/failure 区别，上游取消与依赖失败不得伪成功。
`AccountIconWorkCoordinatorTest` 通过实际 WorkRequest 和受控 WorkManager 边界验证幂等启动、
无身份 WorkData/无 INTERNET 约束、30 分钟兜底/30 秒指数退避、导入/网络 2 秒去抖、真实 Android
Network 身份的本地路径/丢失/剩余路径/观察未知、授权/能力变化及退出仅取消自己的工作，
调度/观察异常留下 FAILED、有限取消且显式重启恢复。它不冒充系统实际调度/重启测试。
生产应用生命周期已接入独立素材 Worker，但在线 v5 服务、实际 LAN/WAN/VPN、厂商后台限制、
系统文件提供者及手机/平板分组人工验收仍待执行，不能由这些合成网络或托管构建宣称通过。

`AccountIconRendererTest` 在真实文件/Room/DataStore 上覆盖 PNG/SVG 的不可变缓存命中、
浅深/模板与原色、主题版本/UUID/tint/尺寸/素材身份、账户/server/epoch/重登及权限变化、只读设备，
以及命中时真实文件缺失/损坏/未知 profile 拒绝；验证 LRU 字节与项数、禁用/超单图预算、
失效和淘汰不 recycle 消费者位图。阻塞真实绘制屏障证明账户锁不跨绘制、取消等待实际收尾、
第二实例保持互斥，重新认证或失效在绘制中及 IO→调用方调度间发生时均拒绝旧结果。
生命周期回归直接调用真实 TokenManager 写入口，在无下一次绘制或 Flow 收集时验证全部渲染器
引用立即清空；覆盖退出/失效 refresh/重登/账户、设备、权限与副本变化，同代次 refresh 和无关
偏好不清缓存。真实 DataStore 事务屏障覆盖提交中取消等待实际结束、期间新旧渲染器不能发布、
排队变换前取消不提交，以及变换后失败保留原 owner/ready。冷重开验证其他 namespace 无权读取，
原 namespace 的 ready 字节及 pending operation ID 保留。登记回归覆盖交错提交与提交中新增实例。
这不替代正式 UI 消费者、在线下载或整体 RemoteViews 内存预算验收。

`AccountIconSelectionTest` 在真机真实 Room/DataStore/文件上覆盖选择/清除/重开/退出保留、
三维副本隔离、只读设备仅能选择已就绪包、CAS/精确无变化/并发实例、代次耗尽、SQLite 原始
类型/孤立版本拒绝，以及 ABORT/IGNORE/触发器改写导致整事务回滚。新声明不能暗改选择。
完整包须验证包括未使用素材及 dark 的真实 ready 文件；缺失/损坏保持原选择，不自动修复。
真实绘制验证角色随包变化、固定素材保持、用途拒绝、保留缺图原引用与显式显示占位、无包
无隐藏内置图片、所有登记缓存同步失效且不 recycle 借用位图。阻塞读/绘制验证认证和选择
变化不被图片 IO 持有账户锁/数据库事务阻挡，取消等待真实 IO 且不提交未验证选择。
`AccountIconRendererTest` 另用独立调用方调度屏障验证绘制后选包拒绝旧图、选包已经提交而
响应仍排队时拒绝过期选择，以及取消不虚构回滚且保留真实选择/ready 字节。
这些是未启用的内部入口，不是正式页面/小组件人工选包验收或在线下载证明。

`AccountIconImportTest` 以独立原始清单和平台 ZIP writer，经过真实 PNG/SVG、文件 Room 与
DataStore 验证一次来源预览/关闭/冻结/零写入、整包所有变体和未使用素材的 hash 去重安装、
不自动选包、冷重开精确重试、ID/额度冲突及 ABORT/IGNORE 预留与 ready 写失败。
部分发布/取消保留真实字节与原日志，明确失败不伪报成功；精确暂存恢复保留其他导入和未知
暂存，ready 文件损坏不从原包静默重建。还覆盖只读预览/确认拒绝、缺账户不开源、同代次
刷新保留预览，以及预览/真实文件发布期间认证、三类副本、权限变化和并发确认。
后续图片安装中损坏已 ready 的早期图片必须阻止整包成功；还覆盖整包回读期间的 ready 行
改写/账户切换。128 素材/256 角色的真实整包安装仍在正常单项/分批预算内执行；内部先协作
取消并等待收尾，避免原 150 秒 runner 超时后仍有数据库工作，不提高门禁时间。日志保留
预览与整包校验阶段耗时。非法来源和提供者 I/O 失败须关闭句柄且零写入。
最大包断言真实 fsync 覆盖四个父目录及每张暂存清理/文件/最终目录发布（共 388 次），
但不重复同步同一组父目录；重试的清理同步不能因暂存已消失而省略。
整包重试在任何暂存清理前预检全部已知最终图片；后期图片损坏保留早期自身暂存，ready 的
namespace 缺失明确失败，不创建空目录或重新安装来掩盖损坏。
正式导入还须验证素材/包、安装预留与传输意图同事务：队列额度、ABORT/IGNORE、身份改写、
跨元数据副作用和真实插入屏障中取消均回滚新增数据，冷重开后仍为空且文件 IO 未开始。
失败不能修改旧已选/已安装/已确认包；重试保留 pending/sending/blocked/complete 的实际行、
代次和确认摘要。文件阶段 ready 失败/取消保留已提交的 pending 意图，最大包仍验证 257 条
真实意图和原 388 次 fsync，不放宽 128 素材/256 角色及超时边界。独立素材原子声明测试验证
新增元数据/安装预留/队列整体回滚、重开重放、无文件安装和业务 outbox/全局主题不变。
真实 URI 确认复用冻结字节并只创建原始三条意图；正式 Hilt 页面实际触摸导入验证浅深变体
完整 pending 队列，预览不入队、选包/清除不重写、退出清除页面后重新授权仍保留原意图。
这验证正式本机导入生产者，不代表认证 HTTP、后台调度或 v5 在线联合切换已启用。
这不代表正式系统选择器/页面、全账户配置安装、在线授权或进程杀死/断电验收已完成。

`AccountIconDocumentsTest` 通过真实文件段、生产 ContentResolver 和 ParcelFileDescriptor 管道，
验证偏移/长度、恰好一次打开与关闭、来源修改后确认仍安装冻结内容、缺账户零打开，以及
截短、32 MiB 超限、实际非法 SVG、原始提供者错误和空描述符拒绝且零安装。
完整 ZIP 尚未 EOF 不能提前成功；可靠管道错误同样不得变为成功。
停滞/提供者打开期限、调用方取消和抛异常的取消监听须等待句柄收尾，保留原失败或清理失败；
真实账户切换不能被文件读取持有账户锁阻挡，旧预览不能跨账户返回。
原 `ThemeDocumentsTest` 的全部读写/取消/异常断言继续执行，共享文件机制不改变主题错误语义。
确定性中断回归验证 FileChannel 的中断异常不能覆盖已取消子任务的取消结果，关闭句柄并 join 后才返回；
未取消时的提供者 I/O 错误仍原样传播，不以取消为由吞掉普通失败。
这些不代替正式 Hilt/系统选择器页面、手机/平板人工验收或在线传输。

`ContractIntegerTest` 经真机真实 Json 字符串/树解码验证 Int/Long 极值、负零、超过 Double 精度
的整数、范围溢出，以及指数/小数/字符串/布尔/空值/容器/非规范数字拒绝；图标、主题、配置、
事项版本与素材额度/目录的实际嵌套模型不借数值转换抹掉原类型。真实主题读入验证单次打开/
关闭和旧冻结字节保留；图标/配置 ZIP 回归拒绝嵌套指数整数，合法浮点目标/系数及原生图片
仍完整读取。既有所有共享样例、顶层版本预检和核心 HTTP 的整数/游标断言继续执行，不重建样例。
结构/事实映射器另验证不透明载荷内的 revision、排序、策略版本、周期参数、计时时长及每日分摊
拒绝指数整数，合法 Decimal 指数目标/计数/指标/系数仍等价。实际规划元数据 codec 保留 Long 极值
与超 Double 精度值，拒绝原类型强制转换；事项确认失败不消费冻结工作，全实体恢复失败整笔回滚
且重开后 cursor/数据不变。已有撤销 shadow 的坏 revision 必须拒绝并保留，不能被较旧快照默默规范化。

`NextSyncHttpTest` 在真机实际 loopback ServerSocket/OkHttp 与独立文件 DataStore 上验证八个
核心 v5 HTTP 路径、原副本头/请求 ID/查询、匿名发现、旧版/未知版零私有流量、首次注册不发布
设备/副本及原始注册权限字段证明。独立原始 JSON 覆盖事项冲突/完整检查点和成功证明、不完整
历史/旧结构拒绝、逐项 tuple 回显、稀疏序列、倒退/重复/越界 cursor、两种计时与合法零秒目标/
完整毫秒、错误会话/控制权/代次/整数宽度，以及坏 UTF-8、类型/charset、重复键、未知字段、
截短、已知/chunked 超限、深度/Unicode、重定向/压缩拒绝和有限错误/退避。真实响应丢失后保留
原 operation/command body；核心及其续期响应另拒绝未知/重复字符集和重复 Content-Type，
缺省、大小写、合法 UTF-8 别名与引号声明仍通过真实网络核验。
类型化整数另拒绝指数/科学计数法、正号与前导零，不以第三方 JSON 库的整数转换作为原始类型证明。
ASCII/多字节 UTF-8 超限请求在实际流编码中停止，零私有流量且原账户/游标保持不变。
账户/重登/实例/epoch/设备/完整能力集合/revision 的晚响应不返回数据。
实际 401 续期保留原头/ID，并发仅续期一次；坏续期不保存令牌。原上下文失效时成功续期和真实
拒绝都不能修改当前凭据；真实 DataStore 写入边界另验证排队期间权限变化，不能只在调用前检查。
主请求/续期取消都关闭实际 socket 且 join；所有服务器线程/文件/作用域收尾失败均传播。
这不是完整 Room outbox/在线恢复编排、正式 v5 服务、LAN/WAN/VPN、系统调度或人工验收。
完整门禁仍执行全部用例的固定三批，不过滤本类，也不以编译或托管 CI 代替当前原生结果。

### 后端改动

最低要求为锁定环境中的格式、静态检查、类型检查和对应范围的 pytest（使用 `-p tests.warning_budget`）；
全量范围见上述风险规则。数据库相关改动还必须从空 SQLite 执行完整 Alembic upgrade，并验证模型与迁移一致。

SQLite 驱动升级须保留 `test_database_lifecycle.py` 的真实连接回归：文件库逐次归还连接及 PRAGMA、内存库跨会话存活、应用正常退出/异常/取消/启动失败清理、重复生命周期、清理失败可重试，以及请求写入取消后的回滚和连接释放。不能只依赖测试夹具调用 `dispose()` 来证明生产生命周期正确，也不得通过放宽警告预算掩盖未关闭连接。

事务策略须保留 `test_sqlite_transactions.py` 和 `test_http_snapshot_boundary.py`：独立连接提交后的
可重复读取、首个/读取后的保存点释放仍可外层回滚、DDL/Alembic 中途失败回滚与重试，
以及真实 HTTP 同步/计时/bootstrap/pull 在旧快照写升级失败后的完整回滚与原身份重试。
提交阶段 BUSY 注入仅证明响应/回滚处理；真实 SQLite 锁竞争另外验证，不能互相替代。

非认证行为测试创建种子账户时，可使用 `backend/tests/account_fixtures.py` 复用固定合成密码的
真实、生产强度哈希，避免重复执行昂贵的哈希生成；每个账户、会话与登录仍独立，真实密码校验不能缓存或绕过。
密码创建、修改、升级等认证测试必须直接使用生产哈希函数，不得使用该夹具替代待验证行为。

当前统一入口 `./tools/verify backend` 依次执行冻结环境安装、Ruff 静态检查、只读格式检查、全后端类型检查、pytest 与警告预算、
OpenAPI 一致性检查，任何一步失败即停止。Ruff 仅作为锁定的开发依赖，显式使用
`backend/pyproject.toml`，对整个后端（包括测试、运维脚本及 Alembic）启用 `E4`、`E9`、`F`；
已启用规则没有宽泛忽略或历史错误预算。必要的 pytest fixture 导入使用显式同名重导出，
不得被“无用导入清理”删除；模型注册等必要副作用导入须保留并说明理由。

格式门禁使用同一锁定 Ruff 的 `format --check`，不自动修改文件；既有代码已统一格式，
不维护格式债务基线。格式配置明确禁用 preview 和文档字符串代码示例重写，Markdown 不在本批格式范围内；
工具升级须独立审查格式差异。
纯格式调整须与业务修复分离，并对比 Python AST（包括字符串和文档字符串，忽略源码位置）及 OpenAPI，
确认没有逻辑或契约漂移，不能仅凭格式检查通过宣称行为未变。

类型门禁使用锁定的 mypy，默认扫描整个 `backend/src/`、`tests/`、`scripts/` 和 `alembic/`。
扫描规则不排除文件；测试验证四个目录新增的错误都会被默认入口发现，并核对所有已跟踪 Python 文件在门禁范围内。
启用未注解函数体检查、隐式可空值限制、
无用忽略和冗余 cast 检查，正常跟踪导入；不使用全局忽略、错误预算或跳过导入来取得通过。
这还不是 strict 全注解检查；动态 JSON/反射边界中已有的 `Any` 也不代表已得到精确类型保证。
门禁测试必须拒绝错误返回值、未注解函数体内的错误、缺失导入、修改只读适配器属性、
错误引擎调用、未收窄的迁移版本空值和把 UTC 校验的空值返回当作必填时间；同时验证该校验对
必填、空值、可空输入的精确返回类型，并保留每阶段失败即停及锁定工具调用测试。
同步模型类型整改须保留目标状态组合、跨午夜日期归属、时间精度、空值拒绝和校验错误顺序的回归测试。
计时精度须通过真实命令 HTTP 路径覆盖微秒跨午夜、暂停排除、1001 毫秒片段、刚好达标与原命令重放，
核对数据库与同步输出的每日分配总和等于有效时长，且 UTC 微秒不因分摊丢失。
模型边界同时验证 0/1/1001/1003 毫秒至 24 小时、额外不足一毫秒及不同显式偏移，不能以容差放宽总时长。
认证边界须保留 JWT 内部 subject 与响应公开 UUID 的区别、缺失数据库 ID 的拒绝行为、
JWT/API Token 的账户状态判断、过期拒绝和事务提交顺序；静态探针同时验证响应字段的非空和 UUID 类型。
`test_jwt_security.py` 保留升级前真实签发的合成 access/refresh 冻结片段，不用升级后的编码器
重生成。验证异常 NumericDate、深层 header/payload、非法 JSON/紧凑编码、算法与签名拒绝、
缺失有效期、独立 options 与上游可变 options 回归、16 KiB/32 层精确边界、引号/转义处理、
UTF-8 及解析前拒绝，程序错误仍传播；深度拒绝不依赖宿主递归阈值。
ID/版本边界覆盖布尔、超范围整数及可表示范围内的既有转换；真实迁移 SQLite HTTP 使用生产
依赖验证无效 access/refresh 返回 401、不查询/修改账户及冻结旧令牌继续正常认证/续期。
`test_jwt_runtime_server.py` 在临时目录显式迁移 SQLite，隔离配置与随机密钥/密码，使用实际
Uvicorn 单 worker 和已绑定 loopback socket 验证登录、续期、设备注册、bootstrap、增删改、
增量拉取、幂等重放、账户隔离及异常凭据返回 401；退出时只回收该测试创建的服务进程。
这组 TCP 冒烟验收不冒充全部业务联合验收；计时、冲突、素材及备份恢复继续由各专项矩阵覆盖。
这些测试不替代密钥配置审计、代理请求大小限制或公网部署验收；安全告警按实际调用前提评估，
不得通过忽略告警、宽泛捕获、放宽警告预算或重新生成兼容样本取得绿色结果。
ORM 数据进入响应时可使用 Pydantic `model_validate` 明确运行时校验边界，不得使用
`model_construct`、扩大响应字段可空性或无依据的类型断言规避校验。
管理边界还须验证家庭排序/成员隔离、设备响应不会创建策略，以及家庭创建者引用缺失时
保持非成功响应、整笔请求回滚且不泄露内部异常；不得通过默认归属或残缺成功响应掩盖数据完整性错误。

全后端接入指现有渐进规则覆盖所有 Python 文件，不等于 strict 全注解或消除了动态 JSON/反射中的 Any。
原始 wire JSON 测试和验收脚本的混合值容器须如实声明，不能伪造精确字段保证；载荷有效性仍由协议测试验证。
测试读取可空结果必须先断言存在，保留原有字段、状态和回滚断言；异步夹具必须使用正确的异步会话工厂，
保留外键/WAL、autoflush、expire_on_commit 和生产 HTTP session 边界。
类型整改须区分 ORM 表达式推断限制、经过验证的可空值收窄与真实错误，不使用全局忽略或无依据的 cast 掩盖问题。
后续新增规则、格式化及工具升级查阅官方说明并独立审查，不依赖工具默认规则隐式扩大范围。

### 联合改动

协议、时间、账户、数据库或同步改动必须启动临时后端，至少覆盖：登录、设备注册、首次同步、增量 push/pull、重复提交幂等、创建/修改/删除、账户隔离、冲突、时间与计时、备份恢复。

同步协议的仓库级 JSON 样例位于 `contracts/sync-v2/`。后端的 `test_sync_contract_matrix.py` 和 Android 的 `SyncV2ContractFixtureTest` 必须读取这些共享文件，不能在各自模块复制一份。修改 `contracts/` 会同时触发两个 CI 模块，避免只验证单端。

模型/校验依赖升级还须通过 `test_validation_upgrade_contract.py` 的既有规范 JSON 与操作指纹基线。基线只在明确审查的契约变更中更新，不能用新依赖的输出直接覆盖旧断言。OpenAPI 生成器的等价表达差异须逐项解释；同步外壳的通用 `payload` 是开放对象，其具体实体仍由领域模型严格校验，不能据此放宽未知字段或直接计时结果的拒绝规则。

逻辑归档须通过 `test_logical_archive_identity.py`：两个账户复用全部六类账户限定实体 UUID，
目标/习惯、指标关联、事实、计时片段和日期分摊在主键重新分配后仍归属正确；幂等记录与快照
也完整保留。冻结的 `tests/fixtures/logical-archive-v1.json` 来自修复前导出器的纯合成样本，
不能用新导出器重生成以掩盖旧格式兼容回归。`logical-archive-v2.json` 在改为 v3 之前由
`a86ad7b` 的 v2 导出器和合成账户素材数据生成，包含跨账户重复素材身份及 pending 状态，也不得重生成。
须拒绝重复键、未知或非整数版本、损坏校验和、
无法解析的引用及不一致身份，并验证失败后目标库（包括服务器身份与 epoch）逐表保持不变。
真实 HTTP 联合验收还须在导入后重新启动服务，核对 bootstrap 数据和身份/epoch 边界。

后端覆盖率必须同时跟踪 `thread` 与 `greenlet`（见 `backend/pyproject.toml`），
否则 SQLAlchemy 异步数据库调用切换后的已执行代码可能被误报为未覆盖。
修正统计配置产生的覆盖率变化不代表新增测试；仍需检查实际异常路径和断言。

HTTP 提交边界测试必须使用生产 `get_session` 依赖和启用外键的迁移 SQLite，不能用只 yield 会话的
测试 override 替代。至少覆盖提交阶段失败后的非成功响应、整批回滚、原 ID 安全重试，以及认证与
业务共用会话、响应发送时独立连接已能读取提交结果；保存点或 flush 失败测试不能代替 COMMIT 失败测试。

设备协议证明须通过 `test_device_protocol_migration.py` / `test_device_protocol_admission.py`：
真实旧 schema 升级保留全部旧行、身份、外键和非目标 DDL，NULL 不猜测版本，失败后完整回滚并可重试；
已记录证明阻止降级。JWT/API Token 生产注册事务覆盖重复注册、拒绝路径与真实延迟外键 COMMIT 失败，
原 v4 响应/权限/身份不变。未来只读门禁覆盖重复或非规范请求头、服务器/设备版本不一致、未知证明、
撤销/跨账户、新快照不复用 ORM 缓存及准入/拒绝均不 flush 待提交修改；测试桥不代表正式路由已启用。
真实单 worker TCP 注册返回后
独立 SQLite 连接读取提交证明；物理/逻辑恢复保留 4/5/未知及账户限定身份，旧冻结归档仍按其原 head 恢复。

`test_v5_replica_replay.py` 使用共享 `next/replica.json`、真实迁移 SQLite、生产认证/事务与
测试桥，核对副本头缺失/重复/非规范、发现后 epoch 改变、旧协议/恢复前回执拒绝、唯一插入
冲突重查、同副本精确重放、最终 COMMIT 失败回滚及完整 start/pause/resume/stop 不重复事实。
指纹独立按规范 JSON 构造对照，保留 v4 固定摘要；准入/拒绝不得 flush 调用方未提交数据。
原事项重放先于载荷校验、全业务矩阵继续执行，不把这些桥测试当作正式 v5 分派或真机验收。

`test_v5_production_sync.py` 不安装测试桥，通过实际主应用八个 register/sync/timer 入口、
迁移 SQLite、JWT/API Token 和生产最终 COMMIT 验证受控 v5 分派。设备证明由正式注册获得，
只有隔离测试库显式设置实际版本 5，不能据此宣称部署或 Android 已切换。覆盖原始版本类型、
全部入口的坏头/副本改变/旧未知撤销及跨账户设备、完整事项历史/跨日撤销/单独冲突状态/重放、
两种完整计时/心跳/精确重放、普通习惯/指标关联/事实/删除传播/账户复用 UUID 隔离、旧代次回执
拒绝及 v4/v5 分页边界。新响应缺少检查点或增量结构形状残缺必须失败并逐表保持数据库不变；
五类写入以真实延迟外键最终 COMMIT 失败验证无成功确认、整批回滚和原 ID 重试。
另由独立真实 SQLite 写连接在准入后旋转 epoch：JWT 旧快照写升级应返回 DATABASE_BUSY 并
完整回滚；API Token 使用记录已持有写锁时 peer 不能先提交，须等原请求 COMMIT 后才能旋转。
后续旧 context 都拒绝且无 mutation。此边界不是服务内自动重试或改写冻结请求，
也不是外网/NAS/正式手机客户端人工验收。

统一验证入口通过 pytest 插件和 `backend/warning-budget.json` 登记已有 warning 的完整类别、
规范化消息和最大数量。
未知 warning 或数量增长会令测试失败；减少不会阻断单个增量测试，但完整测试确认减少后，
必须在同一 PR 下调预算。任何预算增加都需要关联明确的问题和审查理由，不能用过滤、宽泛匹配
或提高上限来掩盖可修复警告。lint 基线也只允许随修复缩小。

## 必须长期覆盖的异常矩阵

账户素材文件适配器须在临时目录验证真实 POSIX I/O：账户公开身份隔离、读取不创建目录、
逐级符号链接/非普通文件拒绝、有界输入与真实 PNG/SVG 校验、同 hash 并发重试、短写和零进度、
同句柄回读损坏检查、每阶段 fsync 失败、日志发布/rename/unlink 失败、规范日志拒绝与精确收尾。
目录扫描必须有显式资源作用域，提前停止和异常后均释放句柄。收据不是数据库 ready 或认证结果；
文件测试不能替代后续授权失效、就绪事务、恢复编排及带字节备份的集成矩阵，也不等于 NAS 断电验收。

素材工作池使用真实线程和事件屏障验证：满载不入队/不消费输入，请求取消后线程仍占名额，
迟到成功/失败不继续执行调用方的就绪发布，关闭期间拒绝新工作、等待中断后可再次排空。
另验证实际文件发布取消后仅留下待处理日志、不自动收尾；此测试不使用数据库，因此不能替代
后续 ready 事务原子性验证。不得用固定 sleep 假定线程已进入目标状态或放大并发上限掩盖泄漏。

素材两阶段传输须使用真实迁移 SQLite、JWT/API Token、文件适配器与工作池联合验证：PNG/SVG 安装和读取、
同身份重试不重复配额或目录记录、明暗共享字节与账户隔离、文件阶段独立连接可提交、最终重新授权拒绝
账户禁用/凭据撤销/设备或能力变化/服务器与 epoch 变化/不可变描述错配。真实延迟外键 COMMIT 失败、
SQLite 写锁竞争和文件阶段取消必须保留 pending 及重试证据；实际 COMMIT 后取消可能已 ready，须验证
会话连接归还并按数据库事实恢复。收尾失败不否认已提交成功，也不泄露原异常或凭据；ready 文件缺失/损坏
不得伪装成 pending 或空下载。单日志恢复不得替失效请求补授权、清除未知归属或删除最终不可变字节。
这些内部服务测试不代表 HTTP 请求体准入、批量启动恢复或带字节备份已经完成。

素材运行时协调须覆盖完整请求名额而非仅文件线程：尚未读取/返回的请求也占名额，拒绝重复/跨任务/
作用域外使用；错误或取消后，实际线程未结束不能恢复清理。提交后延迟收尾须保留成功收据并暂停新准入，
另一请求成功不能清除此标记。实际目录锁须跨独立句柄和子进程互斥，不删除锁文件；关闭等待取消、并发关闭、
关闭失败重试都不能提前释放仍在使用的根。恢复扫描须验证条目/日志精确边界、未知文件/未证明暂存保留、
逐级链接/非普通文件拒绝、异常下目录迭代器与描述符归还。恢复不可用时仍测试真实 HTTP 事实与计时提交。
内部运行时通过不代表主应用生命周期、自动故障恢复调度、HTTP 限量读取或 NAS 文件锁已经验收。

生产素材接线由 `test_asset_production_http.py` 使用实际 main app、迁移 SQLite 与生产 `get_session`
验证。必须覆盖 JWT/API Token、三方协议、全部路径/重放/额度/账户隔离、路径和上下文绑定、UTF-8
与实际 Android MIME、有限错误响应、JSON/字节/目录响应预算、真实 COMMIT 与响应构造失败回滚。
真实接收/文件/响应屏障分别验证独立连接可写、撤销后拒绝、名额持续至发送结束、取消后真实线程排空；
主生命周期关闭被取消不得早于文件结束销毁数据库或释放根。根缺失/链接/未知证据和延迟收尾只阻断
素材字节，实际旧 v4 事实与完整计时命令继续提交；恢复不被新请求绕过退避。实际单 worker TCP 验证
返回成功后独立连接已读取 ready/profile。临时数据库中的测试激活不改变在线 v4，不代替 Android
生产调度接线、NAS 权限/断电、外网或最终用户联合验收。

素材备份归档须验证独立 ZIP writer 的 STORE/DEFLATE、强制 ZIP64 本地头和偏移、65535/65536
真实条目边界、实际条目计数与目录字节限额、重复/额外/遗漏名称、头部错配、未知扩展、加密/链接拒绝。
须有“CRC 匹配声明前缀、但实际 DEFLATE 仍有内容”的反例，另检查截断、尾随压缩流、解压膨胀、
跨读取块、短读/短写、异常和同句柄内容变化。PNG/SVG 从真实素材文件写入并回读，必须验证 hash/profile
与跨账户命名空间，不把 CRC 当作内容身份；文件缺失/损坏/链接不能成功导出。底层通过不代表物理/逻辑
数据库备份接线或真实部署恢复完成，旧逻辑归档入口的资源限制也不能由新读取器测试替代。

物理 v2 备份须用真实迁移 SQLite 与 PNG/SVG 往返，比较除 epoch 外的全表内容、跨账户同 hash、pending
与快照后并发安装；恢复前安全备份也必须含完整字节。验证缺图、损坏、未知 profile/时间、篡改清单、
重新计算 ZIP hash 后的额外条目、原地损坏拒绝与新目录恢复、根锁、未关联日志保留和安装收尾失败重试。
用子进程已提交但未 checkpoint 的 WAL 验证替换失败保留事实，另用真实读锁验证 checkpoint 失败不删除 WAL。
最终目录 fsync 失败可能已经替换数据库，测试须确认新库/素材完整且安全备份可用，不断言虚假回滚。
临时数据库 CLI、旧 v1 独立清单、无覆盖/短读写/输入上限、来源路径替换及保留策略路径拒绝均须覆盖。
本机测试不等同于维护停写的实际 NAS 操作、Docker 包装脚本接线或断电验证。

逻辑 v3 须用真实 PNG/SVG 验证主键重新分配后的账户归属、pending 和不可变元数据；重新导出比较所有
集合原字节，并校验服务器身份保留、epoch 轮换。所有可读版本都验证 ZIP 预检、JSON 重复键/深度/非有限数、
清单/单记录/单集合/总量/行数的精确预算、真实压缩膨胀及未知成员拒绝；不能先构造标准库 ZIP 再限制。
文件阶段须用独立数据库写连接证明预验证事务已回滚并释放锁；验证最终空库重检、真实延迟外键 COMMIT
失败回滚重试、实际提交后异常不虚构回滚、安装日志重试/未知日志保留、既有坏文件保留、来源路径替换、
同 inode 字节后变和最终发布 fsync 失败。旧 v1/v2 用冻结样本读取，不把新格式改数字冒充兼容测试。
素材 ZIP 的一次 verify 不授权后续跳过实际读取；维护普通文件使用非缓冲句柄，避免读缓存遮蔽后续磁盘变化。
真实临时 TCP 验证逻辑导入后重新登录、bootstrap、完整计时和事实幂等重放；不声称已启用在线素材 API。

账户/令牌 UTC 回归覆盖服务器处于 UTC、Asia/Shanghai、America/Los_Angeles，SQLite 往返和
隐式 updated_at 更新、历史无时区 UTC 值不被重写、正负偏移与夏令时重叠、微秒保留、令牌过期
前后一微秒及恰好到期。Android Retrofit 测试已在真机覆盖规范 `Z` 和旧时间字符串及可空字段；
固定响应转换不代表真实服务器联合验收。集中验收时追加一组：管理员新建/禁用用户、普通用户及管理员新建/查看令牌，确认
页面正常、日期未因格式变化异常；页面当前仅展示时间字符串的日期部分，不在本批改为本地时区显示。

- 无网络、有移动网络但局域网服务器不可达、Wi-Fi 可用但端口不可达。
- DNS、TLS、代理、认证、限流、数据库繁忙和协议不兼容。
- 服务端提交成功但响应丢失。
- 应用被杀死、设备重启、后台限制和同步任务重复调度。
- 服务器与终端时区不同、UTC 日期变化、负时区和夏令时切换。
- 计时跨午夜、暂停后跨日、离线完成后重放、重复 stop。
- 两账户同名实体、设备令牌撤销、服务端恢复后 epoch 改变。

## 验收表述

自动化、模拟器、真机、外网和 NAS 是不同证据。未执行的层级必须写为“未执行/豁免”，不能由其他层级替代。

### 发布前容器 CI（#22）

维护脚本的 argv/流程由 `test_maintenance_scripts.py` 使用隔离可执行替身记录，不调用 Docker daemon。
验证绝对素材根与含空格参数、非法/重复参数零调用、停服务→恢复→独立验证→启动的次序，
以及各阶段失败/TERM 后不自动启动或重试。该批在托管后端 CI 执行，本机按用户约定只做脚本静态检查；
替身不证明 Compose、容器或 NAS 行为，真实容器恢复仍须同时通过下述双架构 CI。

`Container verification` 对 backend/contracts/tools 相关 PR 和主分支变更，在 GitHub 托管的
原生 amd64、arm64 runner 中分别执行真实 Docker 构建及 `tools/verify_container.py`。
本设备不运行 Docker。仓库级 Python 测试只验证脚本的环境门槛、确认数量和恢复比较器，
不能代替两个架构的实际作业；任一失败均不能称为容器验证通过。

两个架构还须在构建产物的 Python 环境实际运行共享 PNG 字节样例，检查生产校验器的拒绝类别
及原生解码后的独立像素预期；安装 wheel 成功或服务健康不能代替解码依赖的运行验证。
本机可以执行同一纯 Python probe 的反例测试，但不得因此运行 Docker 或宣称双架构已通过。

HTTP 演练读取共享 wire fixtures，覆盖全部六类实体、计时命令和幂等重放。备份前确认事实、
计时区间/日分摊、命令、变更日志及 revision snapshot 非空；恢复逐表核对内容而非仅比数量。
损坏校验和与无效 Alembic revision 必须因预期原因失败，失败前后数据库保持不变。
成功恢复后身份保留、epoch 轮换，再启动服务验证登录、bootstrap 与幂等记录。
这是合成数据的容器运行/恢复验证，不是设备计时、真实 NAS 更新或跨版本回滚证明。
不发布镜像、备份或认证信息；公开发布、签名及 NAS 验收仍由 #22 后续阶段处理。

## 网络监控整改的集中真机验收（待执行）

Issue #20 的 NetworkMonitor 变更已按用户约定延后真机/NAS/外网验收，由用户在集中验收时操作。
真机内的受控网络回调和本机 HTTP 测试验证状态机与调用契约，不能代替设备厂商的实际网络路由、VPN 和后台策略。

1. 手机同时启用移动数据与仅能访问 NAS 的 Wi-Fi：冷启动登录/同步，新增打卡与指标，确认同步成功；
   若有 Ethernet 转接器，重复非默认以太网连接。检查“设备有网络”不被解释为“服务器已连接”。
2. 依次关闭 NAS、关闭 Wi-Fi、恢复 NAS 和 Wi-Fi：确认失败时本地记录保留、恢复后自动同步；
   快速切网与多次打开/关闭设置页后仍能同步，无闪退，无重复业务数据；离线点击同步后退出应失败并保留账户。
3. 有条件时通过 HTTPS 代理/外网地址及 VPN 重复上述流程，并测试 VPN 切换底层网络和锁定模式；
   错误服务器身份不得接受，局域网不可达时可回退正确远端，系统阻塞的路径不得被报告为成功。

每组记录 Android 版本、网络组合和结果；具备条件的组可一次完成，其余保留待验收状态。

## 小组件刷新整改的集中真机验收（待执行）

Issue #20 的本地广播替换按用户约定延后真机验收。自动化验证刷新入口、六类目标的加载/更新顺序、
空实例、配置变更、实例故障隔离、取消和有限重试；不能证明厂商桌面、Doze 或进程终止后的实际时效。

1. 添加打卡、正/倒计数、正/倒计时、进度、激励、专注小组件，部分类型添加两个实例。
   在应用内及桌面连续打卡/计数、撤销；完成一分钟计时，检查相关小组件最终状态与应用一致，
   计时过程中的实时显示及指标弹窗仍正常。WorkManager 全量刷新允许调度延迟，不要求每次点击立刻刷新所有实例。
2. 修改名称、切换主题/卡片配色，删除一个绑定习惯及带子习惯的目标；检查所有相关实例更新，
   已删除绑定显示删除提示，未删除习惯正常。增删小组件后再修改数据，确认没有影响其他实例。
3. 修改数据后退到桌面并重启应用；有条件时跨午夜后查看六类小组件（包括计数与计时），确认日统计更新。
   退出/换账户后检查旧内容最终清除。强制停止应用后的系统调度暂停不应被表述为程序可绕过的限制。

刷新失败排查需同时查看 WorkManager 输出 `failed_steps` 和日志：重试耗尽的队列项会结束以放行后继，
不能只用任务 SUCCEEDED 判断所有小组件已刷新。未执行的设备/桌面组合持续标记为待验收。

## 种子配色迁移的集中外观验收（待执行）

Issue #20 的配色 API 迁移使用迁移前固定输出验证 43 个种子、明暗两种模式的 27 个角色，
另覆盖逐项自定义覆盖、无效输入、OLED 与主题导出。数值一致不代替设备上的渲染验收。
按用户约定，真机外观检查留待集中进行：一次切换所有预设明暗主题和 OLED，再检查部分自定义颜色
及小组件配色。新主题格式替代旧种子模板后，只导出完整浅深色板的原始 JSON，不再提供生成式浅色模板；
旧 seed 文件明确提示版本不支持。确认文字可读、配色无意外变化。
此批不修改状态栏/窗口布局，系统栏整改需独立验收。

## 主窗口系统栏整改的集中真机验收（待执行）

Issue #20 的主窗口外观按用户约定延后集中验收。当前自动化只在 API 35 真机验证图标和窗口策略，
Compose 布局测试使用合成 insets 验证嵌套标题、底栏、键盘开关、侧边刘海、标题栏及 RTL；
不代表其他 API、实际厂商系统栏、键盘动画、旋转或分屏已验收。历史 Robolectric 的 API 26/29 结果不算当前证据。
测试工具仅进入测试/debug 配置，不进入 release。

1. 一次检查登录页、四个主页标签、设置和新建/编辑页：系统栏文字和图标清楚，标题不重复留白，
   底部按钮没有被系统导航遮挡。切换浅色、深色、OLED，以及系统与应用相反的明暗模式；
   自定义极浅/极深 primary 时状态栏仍可读。状态栏保留 primary 背景，修复后的图标颜色可能变化。
2. 登录、新建习惯和指标记录中打开/收起键盘，滚动到最后一个输入项并保存；检查键盘不遮挡输入，
   收起后没有残留空白。在带主页底栏的输入场景中，底栏应位于键盘上方，不额外叠加系统导航留白。
   同时检查指标弹窗的输入和保存（它是独立 Dialog 窗口，不由主窗口布局测试覆盖）。
3. 有条件时切换手势/三键导航，并在横屏、刘海侧边、分屏下重复第一组；记录 Android 版本、导航模式
   和结果。建议覆盖 Android 8–9 与 Android 15+；缺少的设备组合保持待验收，不自动视为通过。

## 测试可信度回归（Issue #124）

- 认证测试构造真实 Retrofit，仅在 HTTP 传输边界提供响应；独立断言方法、URL、JSON、响应字段和 401。
- 指标卡片、关联指标、打卡弹窗通过真机 Compose 语义和点击/输入验证，禁止恒真占位断言。
- 仪器测试中的 TokenManager 替身只证明加密器调用；Android Keystore 测试另行检查随机 IV、
  不可导出密钥、认证标签篡改、密钥丢失，以及文件中无明文和重开 DataStore。仅删除测试专用密钥。
- 计时测试从系统 Service 入口执行真实 Room 写入、前台通知、服务重建、重复/过期命令和 outbox 失败回滚；
  使用真实流逝时间，不修改设备时钟，不手工造完成记录。服务重建不等同于进程被杀或手机重启。
- 后端 `async_session` / `test_client` 用于模型和共享会话的 service/router 测试，数据库仍启用生产 FK/WAL，
  但不证明 HTTP 提交边界。提交、跨请求读取及认证持久化使用 Alembic 初始化的 `runtime_engine` /
  `runtime_client`，不 override `get_session`。CORS 方法必须发送真实 OPTIONS 预检并断言状态及允许头。
- 修改测试预期前先说明来自哪个契约或用户行为；用有代表性的错误实现确认断言会失败。
  覆盖率和测试总数均不代表测试预期一定正确。

## 同步持久性真机回归（Issue #126）

`SyncV2OutboxTest` 与 `SyncV2ContractFixtureTest` 已从 JVM 迁移到真机；后者从 instrumentation
assets 读取同一份 `contracts/sync-v2` 样例，不复制或自动生成测试预期。
`SyncDurabilityTest` 通过真实 Room、DataStore、Retrofit 与同步仓库验证请求重放、分页恢复、
确认边界、epoch 保护和合并失败，关键断言在关闭并重开存储后执行。
传输边界模拟异常不代表真实服务器幂等已验证；游标与 Room 分属不同存储，也不能据此宣称跨存储原子性。
本批验证结果、故障注入和剩余迁移清单见 [同步持久性报告](reviews/2026-09-20-sync-durability.md)。

同步父记录更新必须覆盖已有子记录而不是仅检查父字段：运行中/已完成计时、命令、区间/分摊、
打卡、指标记录与关联都须在重复合并及数据库重开后保留。还须拒绝同名不同身份的静默替换，
验证整页失败回滚以及明确删除后同名重建。真实计时服务回归应在一分钟倒计时运行中应用
同步变化并等待自动 stop，不能只在 DAO 测试中手工构造完成记录。对应 #169 的范围、
旧实现失败证据与验证结果见 [记录保留回归](reviews/2026-09-21-sync-record-preservation.md)。

## Room 迁移真机回归（Issue #128）

Room v3 的事项预备列须从提交的 v1/v2 schema 经生产入口升级并重开。`RoomUpgradeCompatibilityTest`
继续逐表比较全部旧列、冻结 outbox、冲突、计时与时间元数据，另外检查新增列均未初始化，
不因忽略新增列而放弃旧数据检查。DDL 对比只排除有意追加列的表；未声明变更的索引/触发器须保留，
最终 schema 和 identity 由 Room 与提交的当前 schema 双重检查；v3 文件保持为历史夹具。
`OneTimePreparationMigrationTest` 覆盖中途 DDL 失败回滚/修复重试、版本号碰撞但 identity 不符时拒绝、
新增列重开/事务回滚、跨日意图保留和隔离账户清理；这些合成事实只证明存储，不代表 v5 操作链、
服务端确认或 UI 已接入。完整真机测试继续覆盖当前 v4 行为。

`OneTimeLocalIntentStoreTest` 使用生产 Room 文件库和 DataStore，覆盖首次同步前离线完成、
跨日撤销/再次完成、原 operation/event 与冻结载荷重开、重复请求和身份复用拒绝、outbox 写失败
整笔回滚、独立协调器并发 CAS、账户/登录 generation/epoch 变化及 token 刷新、事实设备权限、
拒绝后继隔离和结果未知时原请求重放要求。孤立事实、非法持久状态、墓碑及载荷损坏不能被自动修复。
`ProtocolNextActivationBarrierTest` 覆盖每个旧同步变更入口，分别注入策略/部分投影、部分意图
和孤立拒绝队列，断言零网络请求、事实/队列/偏好不变且后置回调不运行。
这些测试不代表 once 创建、线上 v5 HTTP 或 UI 已启用；对应路径须另行接入并联合验证。

Room v4 的 `CompletionFollowUpMigrationTest` 从已提交 v3 schema 验证全部旧列/DDL 与冻结请求原样保留、
不推测旧提示、第二张新表创建失败后的整笔 DDL 回滚与重试、唯一约束及账户清理。v1/v2 的迁移测试
继续比较每一旧列与未改变的触发器；DDL 对比排除明确新增的表及其索引，并通过 Room 与
提交的当前 schema 校验新结构。旧版本号碰撞而 identity 不符仍拒绝，未知降级不自动清库。
`CompletionMetricPromptStoreTest` 覆盖提示创建失败连同完成/outbox/凭据回滚、草稿重开、整组指标
第二条 outbox 失败后无部分写入、原时间/ID 重试、确认移除后不重复保存、明确删除后不复活、
并发草稿 CAS/提交、跨日/撤销/再次完成、跳过与不再询问、关联重建/墓碑、单位变化显式确认、
有限数和 Unicode 备注边界，以及账户/epoch/权限失效。提示与凭据的孤立行也必须阻断旧恢复入口。
当前仍未切换 UI、普通习惯/计时提示和 v5 网络；不能把本地 saved 或提交凭据当作服务器成功响应。

`OneTimeAcceptedEventStoreTest` 使用真实 Room/DataStore 校验完整完成→撤销→再完成的顺序确认、
重开后原请求准备、先拉取后成功重放且队列不被提前消费、重复成功和独立协调器并发确认。
成功证明错配时间/日期/时区/来源/正文/身份/revision 必须零写入；确认删队列失败要回滚权威投影、
shadow 与触发器标志，下一次可按原请求重试。乱序增量、同版本分叉、快照篡改、整页中途失败、
墓碑/缺失父记录、拒绝前驱、账户/epoch/设备/登录变化及权限撤回均覆盖。指标草稿和已保存观察值
在完成与撤销确认后原样保留，UTC 等价表达按同一瞬时处理。夹具独立构造服务端事实，不使用
被测 mapper/reducer 生成唯一预期；这不是 HTTP、后端操作重放或完整 bootstrap 的联合验收。

Room v5 的 `OneTimeTransmissionMigrationTest` 从提交的 v4 schema 经生产入口升级，逐表比较旧列、
DDL 和本地凭据/草稿，不生成推测的发送身份；验证建表后 schema 校验失败的事务回滚、修复重试、
版本 identity 拒绝、唯一绑定、拒绝结果 CAS 和账户清理。v1/v2/v3 迁移继续走生产完整链，旧表
及冻结请求断言保留；有意变更的结构另由导出的 schema、Room 与下述 v6 专项校验。
发送测试还须覆盖首次绑定/尝试回滚、同账户重登、设备/服务器/epoch 改变后拒绝改绑、拉取时使用
首次来源设备、缺失/篡改传输记录、明确拒绝持久化与重开后因果后继隔离、拒绝事务失败重试、
错配/矛盾结果零写入、已接受事实不能改成拒绝，以及保存的指标事实和后续草稿不因拒绝而丢失。
网络失败不等于逐操作拒绝；这些真实存储测试不代表 HTTP/冲突恢复 UI 已完成。

`OneTimeHistoryStoreTest` 使用真实 Room 文件库、账户 DataStore 和独立事实夹具，覆盖空 checkpoint、
逆序完整历史、重复与重开、缺中段/缺尾/缺整份历史/缺 checkpoint/重复事件、完整正文错误、
多事项第二项写入失败连同第一项事实/投影/shadow 回滚、静默忽略 INSERT 拒绝、补齐增量缺口、
较旧快照及改写历史拒绝。还检查原请求和指标草稿保留、拒绝分支仍隔离、账户会话/设备/epoch
及父墓碑/未初始化保护，以及 300 次转换的批量恢复与重复恢复；来源结构与游标不由该入口修改。
上述证明事项历史组件的持久化边界，不取代尚未接入的全账户结构、普通事实/计时与 HTTP 联合恢复。

`NextStructureRestoreTest` 使用真实 Room 和账户 DataStore，验证结构与事项历史同事务恢复：
父子/事实乱序、重复与重启、缺父节点或普通习惯作父节点、相同 revision 分叉、较旧结构、
墓碑拒绝、冻结结构操作保留、未确认事实不被确认、无历史策略转换及已知事实/计时/删除 shadow
锁定、名称互换保持本地 ID、后续事实/shadow 失败与静默忽略 INSERT 的全量回滚和重试、
未初始化旧行/node_kind 变更拒绝、登录 generation/epoch 切换隔离。游标与既有 outbox 不由此阶段推进。
普通事实、计时和关联的联合恢复、素材授权及 HTTP 入口另行验证，不能把此阶段视为全账户恢复完成。

`NextCommonFactMapperTest` 在真机验证普通打卡/两类计数值、撤销身份、附带字段与历史追踪模式、
指标原时间/单位/备注、关联端点及提示/显示开关；身份、字段、类型和数值不能静默降级。
计时覆盖微秒跨午夜累计分摊、暂停、总和与日期错误、夏令时 23/25 小时日期容量、
单调有效时长大于 stop 墙钟间隔，以及设备切换时区后结果不变。带微秒的每日进位不得被逐日
向下取整误拒绝；总毫秒数仍严格相等。纯映射不替代完整原文的事务持久化、HTTP 认证和计时状态机验证。

`NextCommonRestoreTest` 通过真实 Room 文件库和账户 DataStore，将普通事实/计时/分摊/指标/关联
与目标结构、事项历史放入同一事务，覆盖乱序、重复重开、迟到 shadow 失败、静默忽略 INSERT、
已有日期分摊替换失败后的逐表回滚及同身份重试。核对完整原文、本地主键、来源标记、冻结 outbox
与游标保留；首次事实不能借用当前设备猜测缺失的冻结来源，未尝试/被拒绝/错配请求不得认领。
另覆盖普通撤销乱序及缓存撤销保留、跨活动/重复撤销、链接唯一对冲突、缺少快照依赖、不可变事实
分叉/墓碑、待处理与被拒绝链接编辑、重复本地事实 UUID、计时命令保护与 epoch 变化。
该存储入口不是完整账户替换或 HTTP/游标激活；相应协调器与人工验收仍须单独完成。

`ObjectAppearanceMigrationTest` 从已提交的 Room v5 schema 独立建库，比较全部 16 表原始列与
约束属性、冻结 outbox、提示和传输绑定；旧外观不猜测转换。覆盖 v5→v6 重开、未知 identity
拒绝、验证失败时两列与触发器替换一起回滚后重试，以及角色/固定素材/Once 日期的真实持久化、
结构更新仅产生一个 outbox、确认投影不产生结构 outbox、失败回滚与账户清空。此前 v1/v2/v3/v4
升级测试继续比较全部原始列；仅对新增列及明确替换的两条触发器调整 DDL 比较，并在 v5 测试中
核对触发器只有声明的新增字段条件。不得借新增列删除旧数据或冻结请求断言。
`OnceScheduleStorageTest` 覆盖日期边界/非法日期、外观严格解码、Room 重开、无周期扣分及旧 mapper/
配置导出拒绝；`ProtocolNextActivationBarrierTest` 覆盖仅含外观或裸 Once 的旧同步/恢复零写入保护。
这些测试不代表素材授权、配置包或页面消费者已接入。

`PlanMetadataMigrationTest` 从已提交的 v6 schema 独立建库，经生产入口升级及重开，比较 16 表
原始列数据、非变更 DDL、外观/冻结请求/拒绝传输，验证只增加 nullable planMetadata 和对应触发条件；
未知 identity 拒绝、DDL/触发器失败回滚后重试继续保留旧库。此前迁移测试继续验证原始字段与触发器，
只追加本次声明的新列/触发条件差异，不以最新 schema 重新生成所谓旧库。
`NextStructureMapperTest` 覆盖真实 next 夹具、一次性日期、四类周期计划/三类追踪模式及正倒数、
目标日期窗口与状态、指标三种聚合/范围目标、角色及固定引用、时区切换、秒/小数/偏移精度、
Room 重启后再编辑导出、元数据更新/outbox 的无操作与失败回滚、账户清空、严格类型及溢出拒绝。
`ProtocolNextActivationBarrierTest` 另外覆盖仅有 planMetadata 时全部 11 个旧修改入口在联网前失败，
保留队列、事实和账户设置。纯结构映射不替代素材授权、父子关系/历史锁和 HTTP 联合恢复验证。

Room 依赖升级还须执行 `RoomUpgradeCompatibilityTest`：从已提交的当前 schema 独立建库，
通过生产入口打开及重开，检查全部业务 / 同步表、schema identity、触发器、事务回滚和 Flow 通知。
库升级不应凭空变更 schema；无结构变化时保留原 schema 文件，并记录无差异证据。
本批边界与结果见 [Room 兼容升级报告](reviews/2026-09-21-room-compatibility.md)。

Room 后续升级还须验证关闭后的挂起读写明确失败且不隐式重开，重开后全表数据保持不变；
Flow 允许合并连续写入的中间通知，但取消订阅/关闭/重开后必须仍到达最新提交值，且 outbox 数量准确。
不得吞掉关闭异常或让遗留订阅跨测试存活。新系列结果见 [Room 稳定版报告](reviews/2026-09-22-room-stable.md)。

`FactTimeMigrationTest` 通过生产 `HabitDatabaseProvider` 和提交的 v1 schema 验证真实升级路径，
禁止测试另建一套与生产不同的 migration / fallback 配置。未知 schema / 降级须断言明确失败与磁盘数据保留。
迁移失败后应关闭 Room，用独立 SQLite 连接检查版本、DDL、事实、触发器和完整 outbox 是否回滚，
并验证移除故障后的重试。时区边界预期使用独立日期和毫秒字面量，覆盖实际完成时间与计划日期不同的情况。
本批结果、故障注入和限制见 [Room 迁移报告](reviews/2026-09-20-room-migration.md)。

## 同步冲突与拒绝恢复真机回归（Issue #130）

`IncrementalSyncRepositoryTest` 的 15 个旧场景已逐项对应真实存储测试，迁移映射见
[冲突与拒绝恢复报告](reviews/2026-09-20-sync-conflict-recovery.md)。共享 fixture 只控制 HTTP 边界，
不模拟 DAO、仓库或合并器。冲突保存与解决失败须核对事实、shadow、冲突记录和 outbox 一起回滚，
显式重试必须产生新 operation ID，并从磁盘验证准备字段清空。
放弃被拒绝修改沿用现有界面约定：移除待同步记录、保留当前本地数据；随后 bootstrap 应用服务端已有记录。
测试预期不得仅从当前实现推导，必须核对协议样例和用户界面约定，并保留代表性错误实现检出证据。

## 持续真机迁移

历史测试已按 [真机迁移总账](reviews/2026-09-20-device-migration-status.md) 完成迁移；后续新增及修改也须遵守以下约束。
纯算法也在 instrumentation 中执行；参数化展开数须与静态方法数区分。Retrofit 请求预期直接使用仓库样例，
不能用被测 DTO 自己编解码生成唯一预期；网络边界只采集请求，断言回到测试协程执行。

## DAO 真机持久性回归（Issue #134）

历史 Room/DAO 测试使用隔离 testbed 中的生产数据库入口，包含实际迁移、外键和同步触发器。
查询不能仅断言非空：必须覆盖准确记录集合、排序、停用状态、关联隔离及半开区间端点。
写入失败须核对事实和 outbox，关闭重开后验证回滚，再验证合法重试；不要只检查异常非空。
范围和更新时间预期使用独立字面量，避免时钟分辨率和 sleep 造成偶然通过。
详见 [DAO 回归报告](reviews/2026-09-20-room-dao-device.md)。

## 习惯业务真机回归（Issue #136）

配置导入须用独立原始 JSON 验证字段含义，并用真实 SQLite 写入失败验证整笔回滚与重试；
同一映射器生成输入再导出比较不能作为唯一预期。协调器的可见性发布、失败状态和取消透传
须有具体断言；边界替身不证明真实调度、桌面或服务执行。层级校验按 D-003 从仓库写入口验证，
拒绝后关闭重开核对业务与 outbox 均不变，同时保留合法移动/解除归属的正向场景。
故障注入会临时改变仓库源码，不能与包含仓库级检查的验证并行；必须恢复后再执行完整验证。
详见 [习惯业务报告](reviews/2026-09-20-habit-workflow-device.md)。

## 网络和计时同步真机回归（Issue #138）

网络回调夹具使用真实 Android capabilities / LinkProperties，路由选择通过边界替身和本机 TCP 服务验证；
不能等同于 VPN、NAS 或外网验收。DataStore 和 HTTP fixture 必须显式关闭并暴露服务线程意外异常。
计时同步使用真实 Room/Retrofit，固定 wire JSON 预期，验证响应丢失、临时/永久拒绝、错配确认与重开重试。
只有 command ID 和 session ID 都匹配才能改变对应命令。详细记录见
[网络与计时同步报告](reviews/2026-09-20-network-timer-device.md)。

## 主题与资源真机回归（Issue #139）

#243 将旧 ThemeManager、CustomThemeRepository、种子导入/参考模板导出及旧 APK seed 文件移除。
对应旧服务测试随 API 退役，不用于维持已取消的兼容行为：文件读写/并发/链接/失败清理/取消由
`ThemeFileRepositoryTest` 与 `ThemeCatalogRepositoryTest` 承接；另补全非法身份对读取、查找、
安装、删除和暂存回收的零副作用，以及悬空链接保护。目录损坏项须显式显示，不沿用旧静默忽略语义。
导出由 `SettingsThemeDocumentsTest` 验证完整双配色原文往返、失败重试和旧 seed 格式拒绝，
不再验证“导出时生成浅色参考模板”或非法颜色回退。旧 43 seed 色值、OLED 与覆盖色断言仍保留，
原生成器/模型仅移到 `androidTest/testing/legacy` 作为冻结比较夹具，CSV 字节不得改变；
生产 APK 不包含该生成器依赖。窗口测试改走实际新主题入口，保留全部布局/系统栏断言。

`ThemeCatalogRepositoryTest` 使用真实 DataStore 和文件验证安装/删除日志、同身份并发重放、
浅深双引用保护、选择已读文件后与删除竞争、实际偏好磁盘失败（预留前和文件发布后）、
写入/rename/unlink 前后失败及关闭重开恢复。未知暂存和未登记冲突文件不能被认领或清理；
链接/FIFO/目录拒绝、删除后根身份替换停止提交。损坏摘要阻止导出/重放/选择/冷读取，目录仍保留
故障与待完成项供显式处理。128 版本、64 MiB 物理存储和序号收尾预留分别验证，不能仅检查计数模型。
完整目录重复解码须验证有界精确原文缓存的不可变性，原文/序号变化及损坏输入不得复用旧值；
保留 128 次实际安装和原有重复设置断言，不通过减少边界规模、循环次数或延长门禁优化耗时。
摘要使用独立规范 JSON 的固定 SHA-256 值验证，切换默认语言也不能改变内容身份。

`DeviceThemeRepositoryTest` 覆盖独立实例并发冷启动只初始化一次、完整浅深/模式/卡片快照重开、
过期选择拒绝、一次来源预览/确认/完整原文导出/删除闭环、文件发布后中断的启动恢复、
损坏偏好和已选文件缺失的明确 failed 状态、观察者完整快照与取消关闭。旧偏好和无关账户设置保留，
不启动 MainActivity，也不以这些内部仓库测试代替设置页及小组件验收。

`SettingsThemeDocumentsTest` 从真实 ContentResolver 文件 URI 验证预览不安装、取消无写入、确认不重开
来源、完整导出后再导入、失败与取消目标、单一目标选择器及选择器启动失败后的重试；保存选择保护
与原设置回归不因新主题入口被删减。`ThemeDocumentsTest` 使用真实文件段和 ParcelFileDescriptor
管道验证 EOF/偏移/长度、读停滞、写回压、提供者打开取消、调用者取消、句柄收尾及原始错误传播。
提供者取消回调抛异常时仍须取消并等待读写子任务；保留原失败并附加清理错误，不能把已写出的部分或完整文件误报为成功。
不能只给协程加超时却留下仍读写目标的后台任务；API 35 结果不代替 Android 8–10 的实机验证。

`ThemeRecoveryViewModelTest` 使用真实 DataStore/文件验证损坏当前主题的显式替换、保留模式/卡片/
无关偏好、损坏元数据不重置、过期选择拒绝、未完成安装取消和已选引用保护。
`ThemeRecoveryPresentationTest` 验证恢复入口、错误脱敏、320dp/1.3 倍字体的滚动与显式确认、
删除取消/再次确认及所见目录序号。`ThemePresentationTest` 验证实际 Compose 明暗/OLED/卡片
快照、原选择器身份与内置名称、预览双配色和确认回调。组件测试不代表手机/平板整页或厂商桌面验收。

`SettingsThemeIntegrationTest` 从实际设置页触摸进入模式/主题/卡片选择与预览确认、删除取消/确认，
核对真实生产 DataStore、文件及冷读结果。使用生产 Hilt 注入的 workflow/controller 和小组件入口，
两者须是同一 singleton；同步观察和刷新派发使用显式边界，仍验证提交后的排队次数。
测试持有的控制器/页面 ViewModel 在收尾时关闭；不启动 MainActivity，也不把 Activity 组件测试当作
正式 App 登录、系统文件选择器、六类桌面组件或手机/平板人工验收。

`IconLibraryViewModelTest` 使用真实素材 Room、独立 DataStore、文件段/管道与生产控制器验证懒构造、
取消/过期/无请求回调、原冻结预览确认、账户及权限切换、只读选择、部分安装显式重试、关闭 join、
选包实际回滚与错误发布、就绪文件损坏不修复、旧绘制拒绝及借出像素不回收。
另以计数密钥边界从 Main 调用生产控制器，要求目录/授权检查和发布在非 Main 上执行，
每次仍证明当前 access 密文；坏 access 拒绝、坏 refresh 不改变本机授权，真实认证快照仍解密两者。
`IconLibraryIntegrationTest` 使用 Hilt 注入的真实 singleton/controller/凭据和实际页面 ViewModel，
通过官方 ActivityResultRegistry 测试边界触摸选择并返回真实 URI，核对实际 PNG、SVG、浅深、
模板/原色像素、预览不安装、安装不选择、选择/清除持久状态及注销后清除页面而保留文件。
它不启动系统文件提供者或 MainActivity，不等同于生产 Hilt ViewModel factory 导航/进程死亡、
手机/平板人工验收。收尾先移除 Compose 消费者，clear/join ViewModel，再等真实图片工作退出，
最后关闭独立素材库；只删除精确 testbed 测试 namespace，不触及正式 App。
真实 Room/文件/Keystore 不受 Compose 测试时钟驱动；图标库组件测试登记实际 loading/busy
的 IdlingResource，在后台空闲后执行原有 5 秒界面条件/像素断言，而不把界面等待误作整包 IO SLA。
不修改 Espresso 空闲策略、单例 150 秒或三分片各 25 分钟预算；错误/真实挂起仍失败，
不能通过报告空闲、替换真实存储或删除业务断言取得通过。收尾先注销该资源再取消并 join 所有者。
依据：[Compose 测试同步](https://developer.android.com/develop/ui/compose/testing/synchronization)。

`ObjectIconControllerTest` 使用真实独立素材 Room、DataStore、私有文件和原生渲染，验证角色选包/
清除与固定素材不变、用途拒绝/占位不改引用、在途绘制跨账户/server/epoch 失效、同副本重新认证、
关闭不再读取及坏文件/显式刷新不静默修复。凭据 cipher 为计数边界，不声称该夹具执行 Keystore；
真实 Hilt/Keystore 仍由图标库集成测试覆盖，不可移除。
`ObjectIconPresentationTest` 从实际习惯、父目标、子项、普通/平板指标卡捕获真实像素并检查原尺寸/
打卡与导航回调；20 次真实卡片参数更新后授权读取/绘制计数不增长。另检查实际选包、固定引用、
主题浅深/模板着色及注销清图/字节保留。指标详情新增真实 Room 外观行与私有素材的 hero 像素，
原 56dp 和业务行/outbox/历史不变。已有 v4 录入、计时、布局、汇总等断言继续保留。
这些组件/夹具不代表生产新引用写入、v5 在线接入、系统提供者或整页手机/平板人工验收完成。

`DeviceWidgetThemeTest` 须从真实私有存储冷启控制器，在不启动 MainActivity 的情况下构造、应用和
绘制 Glance RemoteViews，核对显式浅/深模式与系统相反时仍固定、system 的双色板及 OLED 实际像素。
同时验证生产卡片配色解析器的个性化颜色、缓存 tick，以及失败只显示错误入口、不渲染正常内容。
这不代替生产 Hilt 冷启动、六类小组件完整业务输出和真实桌面生命周期验收。

`BuiltInThemesTest` 验证七份实际 APK 主题的固定身份/版本、模式范围、完整角色、只读结果与
一次 IO 输入关闭。36 个 Material 输出须与已有独立 CSV/OLED 基准及 9 角色字面量一致，
并比较测试专用的原渲染夹具；实际 Compose/Glance 输出不可只检查非空。新增状态值、文字配对对比度和
图表映射另有断言。安装覆盖全套预检、缺失/错误身份/损坏零安装、真实文件重开/并发幂等、
同版本冲突、第三份写入失败的实际保留集合与重试、取消关闭及旧文件不变。不得改变旧 CSV
来迎合新颜色；这些安装器测试不代替生产入口集成或人工视觉验收。

`DeviceThemeStoreTest` 使用真实文件 DataStore 与已安装主题验证整份选择的持久化、关闭重开、
独立浅深主题及三种模式到实际 Compose/Glance 颜色、卡片风格、旧/无关偏好保留。
另覆盖并发 CAS、过期/ABA、严格格式/类型/大小/序号耗尽、文件缺失/损坏（包括无操作）、
实际 DataStore 写入失败后的整份回滚与重试、排队中取消及提交后取消的真实磁盘状态。
观察者须忽略无关偏好更新，并在选择变更时传播文件错误；测试关闭 scope 并等待完成后才重开/删文件。
不启动 MainActivity，不将此内部入口当作设置页、厂商桌面、跨进程或主题功能整体验收。

`ThemeFileRepositoryTest` 使用真实私有文件验证完整主题的一次输入、关闭/取消/异常、1 MiB
边界、严格 JSON、不可变预览、原文导出、重开与并发重试/同版本冲突、身份及路径拒绝。
短写/零进展/部分写/损坏回读、fsync/rename/清理失败和发布前后取消须分别核对实际文件，
保留旧版本、旧主题目录，存储入口不写当前选择，不能假定失败即回滚。
两份精确原文解析缓存须验证输入副本不可变、淘汰，以及合法变更/损坏/缺失/链接仍被读取或拒绝。
根替换停止发布，未知暂存不被删除；
重复操作须归还 FD。共享严格 JSON 预检也须继续通过全部 IconPackArchiveTest 反例。
这是内部主题版本存储验证，不代表主题页面/当前选择或文件系统断电实验已验收。

`IconRasterTest` 检查实际 PNG/SVG 图片的等比透明留白、原色/模板双 alpha、明暗变体与回退、
PNG 采样的奇数/极细尺寸及像素存储上限、SVG 显示分辨率与视口裁剪、尺寸边界和损坏拒绝。
共享不可变位图必须到达真实 Compose ImageBitmap 像素和 Glance RemoteViews 绘制结果，
不启动 MainActivity、不回收已发布像素；这不是厂商桌面、账户缓存或全页面接线验收。

`ThemeEditDraftTest` 验证全部 104 个角色独立编辑、完整导出、不可变色板、Unicode 名称边界、
有界非法输入、单项重置和恢复身份。`ThemeEditorWorkflowTest` 使用真实 testbed 私有文件与
Preferences DataStore 验证内置分叉、自定义最高版本加一/耗尽、目录序号事务冲突、重复确认、
发布后响应丢失、冻结候选重试、日志重开以及来源删除后的已提交结果识别。发布前失败的空日志
经重启恢复后，显式重试仍须保存同一候选；出现更高版本或来源删除时不得新建替代版本。
确认前保存状态快照
和持久文件不一致时不得覆盖另一份内容；非法/未来/重复键输入须保留以供明确取消，不默认为空。
`ThemeEditorDialogTest` 实际触摸与输入覆盖验证门禁、原生颜色像素、重置、浅深/分组保存状态、
明确丢弃和只读完整预览。`SettingsThemeIntegrationTest` 经真实设置页、ViewModel、Hilt
控制器及文件完成编辑/导出，检查保存不切换当前选择、不误刷新小组件、不修改内置来源。
UI 保存状态注册表模拟与 SavedStateHandle 输入重建不代表真实 OS 杀进程、强制停止或旋转测试，
亦不代表手机/平板完整视觉及无障碍人工验收，正式 App 与用户数据均不操作。

`ThemeIdentityLabelsTest` 检查同身份多版本后缀复用、顺序/名称不影响映射，以及 128 个碰撞身份
加长至完整 UUID 才可区分。`ThemeIdentityPresentationTest` 通过真实 Compose 触摸检查同名版本、
互斥选中语义、菜单不误选、窄窗/两倍字体的完整名称语义与可达操作、浅深当前选择和损坏版本
禁止选择/编辑/导出但保留明确删除。设置 Hilt 集成在同名 v1/v2 间实际切换，验证完整持久引用、
当前版本信息、冷加载及恰好两次小组件刷新请求；不将短码视为存储身份或全页面人工验收。

`StatusAppearanceTest` 检查实际同步图标像素及本地化状态语义：success/error、上传/下载/恢复、
离线优先、Idle 和未登录，覆盖保存色板的明暗/版本变化。同步、拒绝处理、角色申请的按钮
可用性与回调按原规则验证，包括离线显式同步；不将显示色变化当作同步状态机修改。
真实 `ParentHabitCard` 验证成功/失败/进行中的标记、展开与导航；显示解析另检查 alpha 底板、
文字与图形阈值及无完整主题上下文的 Material 回退。既有整卡淡化保留；测试不代表淡化卡片、
手机/平板全页面或厂商桌面的人工可读性验收。

`ResolvedThemeTest` 从共享原始主题 JSON 和独立不同角色值核对 36 个实际 Compose 颜色、
Glance 26 个 Material 角色及明确的 widgetBackground、全部状态/图表值、明暗配置与显式模式。
它不启动 MainActivity，覆盖输入 Map 后变、不可变快照、非法字段/颜色和不执行未知生成器。
对比度检查黑白/原色的独立数值、4.5/3 临界灰色、前景/背景 alpha、实际底板及所有 256 个灰阶。
这不是主题导入持久性、所有页面视觉或厂商桌面验收，消费者接线须另有集成测试。

`ChartAppearanceTest` 通过真实 Vico Canvas 像素验证保存色板的曲线、目标和网格、浅深模式及
版本切换、对象强调色不覆盖主题、旧颜色编辑和非法旧值回退、显式覆盖与卡片实际背景。
透明背景/底板变化和曲线 3:1、文字 4.5:1 分别有显示解析断言；展开/收起不误导航。
既有 `TrendChartTest` 的 7/30 天与平均/合计/全部交互回调全部保留；不改变统计时间规则。
组件像素不代表手机/平板全页面、所有图形及厂商桌面可读性已经人工验收。

`LocalIconFilesTest` 在隔离 testbed 私有临时目录执行真实 PNG/SVG 写入、重开与读取，验证
短写/零进展/部分写失败、同句柄回读损坏、文件与目录 fsync/rename/清理失败、发布后失败重试、
已有损坏保留、路径链接/目录/FIFO 拒绝、操作身份及精确清理、输入冻结和文件描述符释放。
API 26 没有公开 Java O_CLOEXEC 常量，生产使用已核对的 Android Linux ABI 标志进行原子打开；
真机检查实际文件/目录 FD_CLOEXEC，并在 API 27+ 比较公开常量。API 26 实机仍未验收，不以新版本真机冒充。
不把 syscall 故障注入当作断电实验，也不将文件原语等同于账户授权、Room 安装提交或启动恢复。

`IconPackArchiveTest` 通过真实 PNG 解码/SVG 绘制验证只读图标包输入，正常包使用平台 ZIP writer，
ZIP64 与攻击边界使用独立原始记录夹具，不调用被测解析器生成唯一预期。覆盖 Stored/Deflate、
本地/中央 ZIP64 与结束记录、四种数据描述符、变体/去重、CRC/长度/完整压缩流、重复/缺失/额外条目、
路径/链接/特殊文件/加密与记录重叠拒绝；Unicode path 附加字段须保持原名。
实际运行 32 MiB 压缩读取、1 MiB 清单、16 层 JSON、128 素材/256 角色/257 条目、64 MiB
逐图展开的等于上限与超限验证，检查 hash/尺寸/像素和不可变结果。短读/零进展、真实协程取消、
源关闭及 I/O 异常保持各自含义；从未重开输入，也不把成功校验当作安装/账户授权/线上传输。

`ConfigBundleArchiveTest` 使用共享原始配置正反例和平台 ZIP writer，验证完整七个示例节点、
计划/目标窗口/指标聚合与关联保留，真实 PNG/SVG/明暗字节及去重依赖闭包。另检查空配置、
缺图角色、深度不可变列表/周计划/色板、一次打开关闭/来源后变/副本隔离、严格类型/历史与未知字段
拒绝、非法父引用、JSON 重复键/UTF-8/Unicode/深度、缺失/额外/路径/尾随记录和实际图像错误。
实际 8 MiB 清单经解压和模型校验，超限明确拒绝；单独图标包仍拒绝超过 1 MiB 清单。
源 I/O/关闭异常与取消原样传播，不包装成成功或元数据错误。共享 ZIP64/32 MiB/64 MiB
及图像 profile 矩阵继续由原图标包测试执行，不删减或扩大旧预算。此入口不持久安装，
不代表系统选择器、账户授权、替换事务、稳定身份或同步/人工验收已通过。

自定义 SVG 必须执行真实 `SvgRendererTest` 位图断言，不能只运行 XML/path 检查器。
共享绘制样例覆盖 viewBox、变换顺序、继承、填充/描边、组透明度与模板 alpha 合成，
另验证流入口、实际 Float 精度下的虚线工作预算以及资源边界。后端消费同一组文档验证
静态预算与拒绝原因，但不把它表述为后端 SVG 像素测试。精确支持范围见 [SVG profile](SVG_PROFILE.md)。

主题 CSV 基准从 instrumentation assets 读取，移动时须校验原始字节不变。OLED 使用固定 27 角色
设计值，禁止调用被测颜色生成器生成导出预期；非空断言不能代替实际颜色相等。
格式化资源使用固定结果检查参数顺序与补零。详细记录见
[主题与资源报告](reviews/2026-09-20-theme-resource-device.md)。

## ViewModel 真机回归（Issue #141）

登录回归使用真实 DataStore，覆盖跳过首次同步后的数据归属、未同步数据不可跨账户接管、
同账户重登保留 replica/权限、旧无归属数据显式接管，以及加密写入失败后的整体回滚和重试。
`TokenManagerTest` 还在第二个凭据加密时注入故障，比较整个存储快照，防止 owner、会话或
设备进度先行提交。仓库/网络边界替身不代表已完成真实服务器登录或人工页面验收。

ViewModel 的真实存储测试使用生产 Room 入口；唯一 DataStore 文件及其 scope 必须在清理前取消并等待结束。
UI 保存成功必须同时检查持久化字段/outbox；失败必须检查无部分数据、无成功状态且可重试。
测试夹具遵守 D-003，不用已废弃的普通父节点/孙节点替代当前删除行为。会显示 Toast 的 suspend UI 操作
应在真实 Android 主 Looper 上测试，不能依靠 Robolectric 宽松线程行为。详细记录见
[ViewModel 报告](reviews/2026-09-20-viewmodel-device.md)。

### Compose 与系统栏真机回归

窗口几何与系统栏标记由真实 Activity 验证；可重复的注入 Insets 不等同于真实键盘/旋转验收。
趋势图必须检查实际默认选中、双向切换与聚合回调，不能仅验证枚举；指标详情必须从页面点击到真实
Room/outbox，并检查数据库重开、取消/确认删除和回调次数。数据类结构断言保留但不计为页面交互覆盖。
证据见 [Compose 审查报告](reviews/2026-09-20-compose-device.md)。

### 桌面组件、计时入口与测试收尾

进度组件测试必须调用生产刷新逻辑并读取真实 Glance 状态，不能在测试里重复计算结果。
组件配置过滤需启动真实页面；打卡回调检查事实/outbox 和刷新排队，缺少 ID 时检查无写入/无排队。
账户清理检查调用当时的绑定状态及失败传播，不能只断言最终空值。Glance 状态、派发边界与 Worker
替身断言不代表实际 launcher、后台调度或设备重启验收。完整迁移和豁免见
[组件审查报告](reviews/2026-09-20-widget-timer-device.md) 与 [最终验收](reviews/2026-09-20-test-confidence-final.md)。

`WidgetTimerReadTest`/`WidgetTimerActionTest` 检查原开始规则、明确无活动会话、原切换身份、
账户/命令序号变化、冷重开及有界严格声明解码；声明不能充当授权。
`ProductionWorkflowInjectionTest` 调用计时/焦点组件的真实 Hilt 刷新并检查 Glance 中的同一会话声明。
`TimerServicePersistenceTest` 从非导出的实际计时 Activity 开始/暂停/恢复，实际点击放弃和切换确认；
过期确认不能改写新的会话。沿用真实一分钟计时，检查提交后指标票据、元数据/关联变更拒绝、
原业务/outbox 同生、重复点击不追加第二批以及账户切换后拒绝旧弹窗。
这些完整类的定向结果只证明所选链路，不代表厂商桌面 PendingIntent、所有组件外观或正式协议联合验收。

### 测试迁移后的独立复审（Issue #149）

计时服务测试必须从命令调用前后的真实单调时钟获得有效时长上下界，排除暂停/重建时间。
仅检查 `durationSeconds >= 60`，或把实际记录重读后与自己比较，都不能拒绝时长被错误放大的实现。
完成后还须核对记录、stop 命令、运行区间、日分摊及重开数据库的一致性；不得修改系统时钟或手造完成记录。
错误放大时长、计入暂停时间的可编译变体都须被断言拒绝。复审范围和证据见
[二次审查报告](reviews/2026-09-20-test-confidence-followup.md)。

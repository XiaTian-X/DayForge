# #160 整合审计与继续开发基线（2026-10-12）

## 当前续行状态（2026-10-14）

再次核实实际 Git/GitHub：main 为 `af3aa8dce98bd92a1a480983e1319f05ed2461e5`，
功能基线仍为 #330。冻结候选 `ac56be9` 已建立唯一 main-based 整合草稿 PR #391，
60个开放 PR 包含本整合 PR、57个原业务链 PR 和2个依赖升级；仍为2个 worktree。
候选包含恢复联合修复 `2ecba77`、计时目标/指标跟进 `95c3524` 和已批准的门禁容量配置。
原 `ac56be9` 的 GitHub repository/backend/android 及双架构容器检查均已通过；本机未运行 Docker。
本地后端3210项通过。Android首片948项为947通过、1项测试清理失败，后两片尚未执行；
仅修虚拟调度器的主线程清理后，完整小组件配置类6/6通过，生产代码未变。
原失败和剩余验证见TESTING；结果未齐前不合并或关闭原业务 PR。
后续 `409facd` CI后端3209通过、1项重复SIGINT退出失败，并非超时；其余检查通过。
已确定性复现测试后端收尾恢复默认处理器的窗口，修正两个CLI后相关完整模块5/5通过。
`ad75903` 增加只输出部分证据的固定分片入口；其run已结束，第二片974项为972通过、
2失败，第三片未执行。失败为恢复续行60秒阶段超时和保存测试约2秒短轮询；恢复后的
普通同步未执行，恢复测试后端终态审计也因缺少指标失败。原证据已独立保存，详见TESTING。
两个完整类复验17项为16通过1失败：保存测试类全部通过，恢复仍超60秒；失败栈定位到
状态解码反复编译固定哈希正则。现仅预编译复用同一模式，不缓存权限或省略校验，新增
格式拒绝回归后StageStore14项及真实恢复1项全部通过；恢复后普通同步及后端终态审计
通过。续行59.268秒，距离60秒仍不足1秒，不能宣称稳定性能余量或完整性能归因。
`170d8ed`的精确CI及托管双架构容器检查已全部通过，托管后端3212项通过。
第三片已结束：1024项为1021通过、3失败，分别是32层链150秒中止、小组件恢复夹具
误判当前显示标记，以及配置页夹具收尾的非Main生命周期取消。两项小组件修正后两个
完整类3/3通过；原失败及首次Pair类型编译失败均保留。用户批准仅32层链180秒、真实
恢复续行/UI等待90秒，整段300秒及其他阶段60秒不变；仅复验两个相关完整类。
这两个完整类最终29/29通过，32层118.139秒、真实恢复229.182秒（续行57.153秒），
恢复后的普通同步及后端终态/清理均通过。组合身份核对当前2947项无漏测、每项有通过
用例证据；覆盖采集的三个校准项通过，APK哈希不变。原分片失败全部保留，未伪造
单轮全量绿色或`complete.json`，详细各次源码/范围/哈希见TESTING。
本批最终diff仅测试夹具、批准容量、工具回归和文档；产品相对冻结门禁的唯一改动为
已验证的等价哈希模式复用。按既有夹具修复后仅验受影响类的规则，必要组合核验已齐，
不重复未变全量。保存候选并完成新提交CI后可进入完整依赖批次合并，再核实main实际
树和原PR实时head，无独有修改/依赖才清理。当前尚未合并，也未把候选记作main交付。
详情与各run范围见TESTING。
以下历史盘点保留当时引用，不将新增分支实现算作 main 交付。

冻结时只读复核：57个业务PR的实时head全部为`ac56be9`祖先，全部60个本地分支head
无候选外独有成果；61个实时远端heads中仅独立依赖#261/#265不在候选。#390的main
squash提交已进入候选，原PR head不是祖先不代表遗漏。仓库只允许squash；未来整合
后须用批准候选与实际合并提交的tree一致、旧head在候选内且无新独有提交证明成果，
不能按旧head不是main祖先强删分支。依赖锁、版本表、wrapper与main无变化。

上一轮定向72项为69通过/3失败：旧五项夹具失败已通过；当时两个新增夹具错误待修正复验，
真实恢复在“确认+续行”组合60秒诊断边界中止。同事务只读复用实测36→8次Keystore
解密、2037→517ms；完整归档58999→43345ms，仍不等于恢复通过。已细分两显式动作，
当时保持150秒方法限制，未重复运行同一已知不足边界。用户于2026-10-13随后明确批准
仅此完整恢复旅程300秒例外；确认/续行分别60秒、普通用例150秒及产品/HTTP超时不变。
现已应用方法注解，完整事实、档案、素材及终态断言保留。后续真实联合未超预算，
但恢复材料仍WAITING；随后定位目录HTTP读响应头EOF。恢复专用新连接修复已完成：
精确 `2ecba77` 的3完整类32/32通过，真实恢复、素材下载/最终放行及恢复后的新指标普通
同步均通过，源端/恢复端终态由独立后端审计核验。原失败保留在TESTING，不拼称全量。

上一批修正后run `0f8135724e5b44fea7f6172727850976`的Archive34、BusinessProof21、
StageStore13及RecoveryReadScope5共73项已全部通过（root72、构建/lint与产物门禁通过，
connected20分36秒）。两个新增夹具问题已完成复验；该结果不覆盖实际FastAPI完整恢复，
不替代最终全量/精确CI/人工验收。当前源码和失败/待验记录一并保存为同分支安全检查点，
不新建微型PR、不合并未闭合链；Destination/Completion随后续完整候选门禁一次验证。

正式恢复入口已有分支实现，真实停止/备份/恢复的 FastAPI 联合已在上述精确源码通过。
本批补旧凭据不可读时的独立入口和缓存 widget 帧的归属检查；实际联合发现权限校验
重复硬件解密，以及失败夹具未隔离恢复指针的问题。源码修复及相应必要复验已完成，详细
失败范围见 TESTING 当前节，不把后续夹具的连锁失败标记为通过。

审查确认恢复投影会重分配本地数字 ID；`c3c4bbc` 已在候选补齐稳定 UUID 的小组件
绑定、每习惯设备偏好及提醒读取/发布冻结门禁。旧 Long 值不能被当作新对象身份，
相应代码必须随完整候选合并。这属于已确认恢复链的完整性，不重设计 UI/业务，
不修改精确候选投影或默认 v4；各批行为范围见 TESTING，最终完整门禁尚未完成。
完整候选通过审查和必要最终门禁后，统一以 main 为 base 整合依赖闭包并核验精确 CI；
只有 main 实际包含所需代码后才关闭被替代 PR、核验并清理无用分支，现不强行合并或删除。

本次入口复核还确认独立剩余项：小组件的 `IconRasterAdapter.toGlanceImage()` 只有测试调用，
六类生产 widget 已接主题/动作/发布屏障，但尚未消费账户图标素材。它是“适配器已有、
生产尚未接入”，不是已完成或仅待人工验收；后续按现有 UI 边界单列完整消费批次。
六类widget实际上均无对象图标槽位；用户已将widget UI/UX留待后续重构，本轮要求
共享主题/图标接口而非新增视觉布局。因此明确记录适配器可用、生产对象图片尚未显示，
不以隐藏Image或预加载冒充接入，也不以此强迫本次恢复整合增加UI。未来明确槽位后
再按原账户、原对象、同一主题及发布栅栏接入；此项不以恢复链测试代替。
新增独立审查项：默认v4的旧小组件动作仍有实际账户隔离缺口。CheckIn/Timer等旧回调
只带数字ID；凭据失效但Room数据保留时，legacy写入路径仍可接受动作。显示帧门禁不能
撤回已排队的PendingIntent，也不能代替最终事务授权；不是本批UUID偏好修复已经解决。
后续必要修复只覆盖widget-origin链：展示时捕获原会话/设备/对象UUID，穿透回调、确认、
计时Service及关联指标入口，在账户锁和实际写事务中复核。声明还须绑定动作：打卡/计数
保留原业务日及时区，计时控制保留原session UUID/sequence、start保留原incumbent或明确none；
不能阻断合法跨午夜计时控制，也不能用同账户/同习惯身份授权另一场计时。旧无声明缓存动作拒绝并刷新；
合法已登录离线操作保留，typed/v5、普通App入口及已确认UI不重设计。须覆盖失效凭据、
重新登录、跨账户、数字ID复用及延迟Service/弹窗。当前候选已补共享原身份校验和四类
widget 生产声明，事实/指标最终事务、计时切换/Service、目标确认及共享放弃弹窗继续原
声明；独立复核已修冷服务拒绝旧命令后重新认领计时的退路，只允许保留原合法运行
状态；真正系统恢复独立校验。22类159项实际157通过2失败：真实恢复材料仍WAITING，
一个新弹窗fixture未推进Compose调度。随后两个相关完整类8项为7通过1失败：弹窗
7项全部通过；真实恢复目录仍0/0、无下载任务，材料返回RETRY，HTTP边界定位中。
详情见TESTING，不声称原159项整批通过或进入 main。静态审查修正了
Counting 跨日显示/声明不一致，并要求只读计时显示与控制权限分离；未扩大普通 App
直接计时写路径，仅为它调用的共享放弃确认在展示前捕获合法原身份。
另确认的 v5 TIMER 目标提示缺口已在本批接入：TimerService 将原完成命令身份交给
目标 Activity，展示、实际编辑和最终指标写入均复核原完成证明，不伪装成 legacy。
目标优先于自动指标提示，显式目标选择后的指标跟进仍使用原身份，历史和布局不变。
小门禁发现并修正完成校验对既有命令设备字段的误读；后续单项测试的 ACK 修改时间
等价断言亦已修正。最终完整FollowUp8/8通过，另三个完整类22项（含实际Service旅程）
保持前轮独立结果；完整范围及真实失败见 TESTING，不拼作30项最终整批或 main 交付。
最终整合验证须覆盖真实业务、配置/素材、调度及恢复四组联合；完整 Android 门禁已覆盖
时不重复运行相同联合。用户现已批准仅完整三片每片10800秒，定向批次2700秒、普通
用例150秒和配置/恢复两项300秒例外保持不变；保留下方历史流水，不使用旧容量或
持续独立堆叠PR的历史描述作为当前执行规则。

## 结论与边界

### 最终门禁的已确认条件（2026-10-13）

不盲目启动已明显有容量风险的全量。以`ba137aa`源码注解及现有参数化展开估算2936项，
按照锁定runner实际分片哈希，将10月10日至13日已有XML每个仍存在方法的最近一次耗时
归入固定三片；这不是当前候选通过证明，最终发现数仍以同一APK真机发现为准。

| 分片 | 静态估算项数 | 有最近耗时的项数 | 已知部分历史耗时 | 无历史耗时项数 |
| --- | ---: | ---: | ---: | ---: |
| 0 | 945 | 536 | 55.34分钟 | 409 |
| 1 | 972 | 541 | 69.55分钟 | 431 |
| 2 | 1019 | 607 | 75.29分钟 | 412 |

三片各自的已知部分均已超过现有45分钟；新计时批次另有10项。未知1252项不算通过，
也不抹除历史失败。按已知均值外推最慢片约126分钟，仅作容量规划，不是当前性能承诺。
用户已确认仅将完整三片上限设为每片180分钟，保留targeted45分钟、普通方法150秒、
两个已批准的300秒方法、固定分片/全部断言/取消清理与完整发现。主机watchdog和Gradle
任务两层同步且明确区分full/targeted；默认仍为targeted，未知档位拒绝。
极端实际SQL容量三新类和诊断工具仍仅在原stash中，不为全量应用该stash或删选当前测试。

创建整合PR会匹配现有`container-verify.yml`的backend/contracts/tools路径，自动在
GitHub运行amd64/arm64 Docker构建与恢复验证；本机不运行，不发布镜像或部署。
用户本次明确允许托管CI的Docker验证，本机仍禁止；不删除/绕过工作流，授权不等于
验证通过。Android托管CI仍只编译/lint，不替代真机；后端仍是完整门禁。
计时小门禁已收尾并保存`95c3524`，两项执行条件已解除；继续容量工具验证、冻结候选、
完整门禁、精确CI/依赖整合，原目标及其他人工验收限制不变。

本报告以 `XiaTian-X/DayForge` 新仓库的实际 Git/GitHub 状态为准，不管理旧仓库。
审计时远端 main 是 `4341408d0a2ce5664d8f380fb4d2a42690e29611`（#330）。
本地主 checkout 的 main 是 `6a54d25a66e42dbf1fc025c8acc4b468b38be9b4`，落后 8 个 squash 提交，
并非功能丢失。本轮没有把“提交 / 测试 / PR ready”计作已进入主分支或完成产品交付。

初始盘点：59 个未合并 PR（57 个业务链、2 个独立依赖升级），128 个本地分支、
60 个实际远端跟踪分支（不含 origin/HEAD），25 个 worktree。审计分支创建后本地为129个。
业务 PR 构成单条堆叠链，末端 #389 相对 main 有83个提交、515个变更文件。
`MERGEABLE` 只表示各自 base 上无文本冲突，不代表这些改动已集成到 main。
本报告是整合盘点与门槛审计，不宣称515个文件已经逐行独立复审。

用户本轮明确暂停新增功能；运行中的后端和 Android 验证已结束，未中断、未遗留活跃构建。
此前唯一未提交批次已保存为 `codex/df-160-recovery-snapshot` 的
`b173cc2d6488d7645be34c4a1cf5428b76a6f904`；其父是 #389 的 `73c84a9`。
该 WIP **不在任何合并候选内**，不覆盖其他会话的修改。
用户随后允许：依赖链不满足合并条件时，可保留阻碍项后继续原目标，不为减少积压强行合并。

不操作正式数据、不部署、不安装正式 APK、不启用默认 v5、不在本机验证 Docker。
现有 UI、手机/平板布局、业务规则与 D-011～D-022 的已确认决定不在本轮重设计。
目标暂停不是取消或完成；恢复开发不等于取消合并门槛。

## 目标功能状态：主分支与候选不能混写

| 功能链 | main 中可核实的状态 | 分支中的状态 / 尚未接入 / 未完成 |
|---|---|---|
| 主题目录、文件导入导出、设备选择、编辑器、主要 App 外观消费者 | 已合并：Settings 的主题入口、DeviceAppearanceTheme、ThemeCatalogRepository、ObjectIcon 等实际生产路径 | 小组件统一发布、账户生命周期与配置窗口等追加接线仅在 #354～#371；未合并 |
| SVG/PNG 安全解析、本地图标库、安装恢复、缓存、账户素材传输 | 已合并：AccountIconImport/Files/Renderer/Transfers，设置图标库入口及主要消费者 | 实际习惯/目标/指标创建编辑选择与原子保存仅在 #331；配置携带素材与同步屏障仅在 #363、#386～#387 |
| 一次性事项持久化、CAS/撤销、收据及同步基础层 | 已合并基础层，#327/#330 含 HTTP 与统一内部 runtime | 完成后保留、跨日撤销、指标跟进、手机/平板/详情真实消费仅在 #331；当前默认 v4 不能被称为新事项交付 |
| 普通结构因果、普通结果/删除、计时结果接收与幂等恢复 | 已合并 #322/#323/#325/#330；NextSyncRuntime 为可注入内部入口 | 正式业务写入、删除协调、计时开始政策与完整消费者仅在 #331 及后续轮次链 |
| 计数目标增减与当日规则冻结 | main 尚无 CountDayMigration/NextCountDayStore | 仅在 #331；后续 #346/#364/#385 组合依赖不可遗漏 |
| 未结算跨午夜严格失败读取 | main 尚无共享 UnsettledTimerFailure/StrictFailureState | 仅在 #333；不得先单独合并会丢失此修复的候选树 |
| 重新挑战保留历史及轮次因果 | main 仍保留旧重启路径；新契约、Room/Alembic 与轮次消费者未合并 | #334～#351 存储/HTTP/ACK/全部业务消费者；#373 正式路由、#381/#382 App/widget 重启入口仅分支实现 |
| 小组件动作、统一主题、失效声明、显示串行化 | main 有既有小组件及已合并共享基础；不等于新链完成 | #331、#352～#361、#382；自动更新与素材链 #387。系统桌面/手机/平板人工验收未完成 |
| 配置 v2：文件→冻结身份→素材→预览→持久替换→ACK→设置恢复 | main 有旧配置功能与新内部契约/解析基础，不具备完整新导入替换入口 | 完整追加链仅在 #362～#371，#386 为素材/业务联合；不能仅合并 ZIP 或 UI 文案宣称完整 |
| 正式注册、手动/自动同步与新建统一 challenge_contract=1 | main 的 SyncManager 仍调用 IncrementalSyncRepository，导航未取得新 creation authority | BusinessSyncRepository、ObjectCreationEntry、共享 worker/settings 恢复仅在 #372～#378；#374 设备权限操作不得遗漏 |
| 永久拒绝的完整依赖预览与显式人工放弃 | main 未接入该链 | #378 分支实现可支持的类别；未支持类别必须保持明确阻碍，不扩大既有许可 |
| JWT 稳定账户 UUID / 实例 / epoch 与完整多账户恢复 | main 尚无 resolve_token_account 与绑定 uid/sid/epoch 的完整认证链 | 仅在 #383/#384；允许一次重新登录已确认，不能据批准称为已交付 |
| 真实后端、素材/配置、WorkManager 组合验证 | main 没有这些追加联合测试 | #385/#386/#387 提供候选源码上的证据；不代替 main 接入/人工验收/默认协议激活 |
| v5 只读启用前检查 | main 尚无 storage/v5_preflight.py | #388 分支实现，只读预检不授权启用 |
| 跨 epoch 只读诊断 | main 尚无诊断；不是自动恢复 | #389 仅分支只读诊断，不等于正式恢复完成 |
| 跨 epoch 隔离快照、完整差异、素材证明、私有档案、持久阶段、写栅栏及活动切换 | main未接入；恢复链仅分支实现 | recovery-snapshot 分支已有完整旧输入/档案、Room18栅栏、确认重验、35表COMMIT/冷续行、DS CAS发布、新范围原生素材/精确选包证明、App/后台/小组件屏障、素材预检、持久追赶及终态放行；MainActivity根恢复页和next设置入口已接。精确2ecba77真实FastAPI跨epoch、素材下载及恢复后普通同步通过；最终门禁仍未完成。只有重建完整证明后终态CAS可放行，旧档案/素材/主题/选包意图保留。不是main交付或默认v5启用。精确证据与剩余链见TESTING |
| 默认 v5、正式 APK / 后端发布、最终联合与人工验收 | 尚未执行 | 是明确发布门槛，不通过合并/CI 自动触发；外网、NAS、桌面及手机/平板人工项仍待用户 |

核实依据：main 的 SettingsScreen、ObjectIcon、HabitNavGraph、SyncManager、NextSyncRuntime；
候选 #388 的 BusinessSyncRepository、ObjectCreationEntry、backend main/router 与协议准入，
#389 的诊断入口和 b173cc2 的暂停记录。协议门禁要求真实服务器/设备副本和显式5请求；
默认副本仍是v4，不从能力发现或已合并内部类推断激活。

## 整合顺序与风险（先审查后合并）

1. **安全基线**：WIP 保存；旧已合并分支/干净 worktree 核验与恢复档案；审计/闭环文档独立 PR。
2. **生产业务基础联合批次 #331 + #333**：创建/编辑/事实/事项/计数/计时/提醒/widget 与严格待结算修复一起审查。
   Room13、后端 count-day migration、导出恢复和同步契约共同生效；不把 App UI 先合并而遗漏后端及恢复。
   #331 有243文件，合入#333后249文件；不是一个小 UI 修改。还需冻结候选、完整关联模块组合验证和 v4 回归。
   两 PR 均 draft，历史 scoped 结果不是最终 Android 全量/联合交付；本轮暂不改为 ready。
3. **轮次与全部消费者 #334～#361 + 正式接线 #373/#381/#382**：
   契约、迁移、出生归属、普通/计时/事项 ACK、目标子项前沿、离线重启、全部录入与读取、统计/widget 共同核实。
   UI 入口与恢复/归档依赖不能按 PR 数字拆断；是否可在关闭的生产档位下分批进入 main，
   必须验证代码隔离与 v4 回归，不能把当前链当作已批准的独立可发布批次。
4. **配置/恢复/调度 #362～#378**：
   文件解析、资源复制与新身份、非空替换、持久阶段、部分接受、手动/后台串行、问题预览确认一起审查。
   其中 #375 编译容量与 #380 CI 容量是已有验证支持修正，不因审计再扩容或升级工具链。
5. **安全及联合收尾 #383～#388**：
   账户绑定与完整恢复先于新档位实际恢复验收；真实后端、素材/配置与 worker 组合验证后再开放。
   上述依赖可合组审查，但不能盲合并累计515文件，也不能把后端旧测试绿灯拼接到新源码。
6. **#389 + 未完成恢复批次**：诊断可单独评估闭合范围，正式隔离恢复仍未完成；
   b173cc2 先修复夹具/选择错误，再完成已批准剩余链。与默认激活、正式部署分离。
7. **依赖 PR #261/#265 单独处理**：不混入业务合并或为了减少积压升级；均保持未合并。

以上是依赖与审核顺序，不是自动合并清单。边界不能证明可独立生效时合成完整候选；
严禁切出“CI已绿”的小片段忽略未接入的后继。
主分支保护为 strict repository/backend/android、线性历史、PR 与已解决讨论；不使用管理员绕过。
没有证据证明满足条件的业务批次本轮不强行合并，按用户补充许可继续原目标解决实质阻碍。

## 发现的遗漏、覆盖及文档漂移

- **实际交付滞后**：57个堆叠 PR 都尚未进入 main。长链上的“implemented/ready/tested”
  只能描述该分支源码，不能成为#160交付百分比或主分支成果。
- **本地 main 滞后8提交**：仅更新引用不足以更新 checkout；本轮需在清洁检查后 fast-forward，
  不 reset、不改写历史、不处理主目录被忽略的测试数据库。
- **重复引用而非重复实现**：`codex/df-160-recovery-capacity` 与#380头相同，仍是开放依赖，暂保留。
  68条历史分支的完整 patch 与main对应squash逐字节相等，不能重复合入。
- **堆叠一致性已核实**：57个业务PR的本地head与GitHub精确head全部一致，逐级base也全部吻合。
  HabitRepository、NextCoreLocalIntentStore、NextCoreRequestStore、NextSyncRuntime、NextTimerWriter、
  SettingsViewModel及双语字符串反复被后继修改；这是组合审查重点，不自动解释为错误覆盖或重复实现。
- **历史计划仍声称未接入**：DESIGN/Issue/PR 的早期章节及 SYNC_PROTOCOL 的分阶段文字
  与后续分支接线时间不同；保留历史但必须标明日期/候选状态，最新功能状态由本表加精确 main SHA 判定。
  “规划已确认”不等于“生产接入已合并”。
- **#265锁文件遗漏**：仅pyproject.toml修改7个精确版本，uv.lock未变；
  带 `--frozen` 的历史 CI 不能证明新声明版本被验证。另有Ruff工具版本与required-version需核实。
  不合并，不在此轮擅自处理框架升级。
- **#261 Android升级 CI失败**：独立工具链主题，保持未合并；既有CI绿色不能替代新兼容组验证。
- **后续修复可能覆盖旧候选**：#333严格待结算、#375编译容量、后续素材/恢复/路由修正
  必须留在组合候选。对旧头做孤立合并会引入已知缺陷或丢失依赖；
  source tree核验及组合验证未完成，不把“MERGEABLE”解释为不存在功能覆盖。
- **尚无穷尽源码审查结论**：本轮完成引用/依赖/生产入口差异与关键阻碍核查，
  未发现独立重复业务链，但未逐行复核全部515文件，不宣称“无bug、无覆盖、全部可合并”。

## 失败与未验证项（保留真实范围）

| 项目 | 实际证据 | 本轮判断 |
|---|---|---|
| #352 CI 37887144771 / job113679610258 | packageDeviceTestAndroidTest失败；未提供stacktrace根因 | 后续构建成功不改变该历史失败；#353加诊断，不称为已证实业务修复 |
| #373 CI 38012840531 / job114096527096 | Kotlin OOMErrorException / Not enough memory to run compilation | #375已有编译容量修正及后续绿灯；组合头仍须满足保护门禁 |
| #336/#344/#378 backend checks | CANCELLED，不是通过 | #380的后续容量/CI结果只针对对应候选，不回填旧结果 |
| b173cc2 后端 | 八完整模块326项，324通过2失败，194.57秒；关联夹具用了不存在的required_on_completion | 当前快照批次禁止合并；修复实际prompt_on_complete夹具后重验完整新模块 |
| b173cc2 Android | build/lint通过；ChallengeSyncContractTest完整类名选择错误导致发现ClassNotFound | 没有行为执行通过，没有targeted.json；不把log=true发现事件当通过 |
| 原目标联合及人工验收 | 分支定向/组合结果已有，正式手机/平板/桌面/外网/NAS未完整验收 | 明确待执行，不能据CI或PR关闭代替 |
| 本机Docker | 用户禁止 | 未运行；不使用历史托管container成功称为本轮Docker验证 |

暂停批次完整过程保存在b173cc2的 `docs/reviews/2026-10-12-recovery-snapshot-pause.md`；
报告目录保留在当前开发worktree中，不进入公开Git。
本轮只对文档/流程运行root及diff检查；不重复未受影响业务全量或极端容量测试。

## 后续任务与闭环

- 2026-10-13正式恢复入口已接入原恢复分支：Activity独立VM、根级中性页面与next设置
  回调使用同一生产服务组合，全部差异/原表记录/精确素材缺口可查看；保存档案/冻结
  后仍需新证据和独立勾选，冷启动不联网或自动续行。账户变更撤销页面/弹窗/旧提交，
  密码/同意不持久化；无凭据UI代次不授予HTTP访问，持久账户错配不得展示档案。
  九完整类57项真机定向通过，真实MainActivity/Hilt冷启动和自有TCP的完整UI恢复组合
  层级、两次构建/lint失败及精确回执见TESTING。main仍af3aa8d、59开放PR、2worktree；
  未新增微型PR或合并未闭合链。下一批为实际FastAPI跨epoch恢复联合→必要最终门禁→
  精确CI/完整依赖合并→main接入核实→人工验收；#160保持ACTIVE，不启用默认v5。
- 2026-10-13显式恢复连接与可注入生产服务组合已在原恢复分支闭合：只读配置路由、
  同账户完整快照CAS重新认证，以及规范拥有者上的预览/归档/确认/有界续行/离线取消。
  普通路由不获恢复豁免，孤立阶段不启动新操作；旧确认不能跨认证变化继续使用。
  最终八完整类128项通过，首轮编译失败及133项中的3项失败/修正保留在TESTING。
  主分支仍af3aa8d、59开放PR、2worktree；未新建零散PR或强合未闭合链。下一批为
  根级独立恢复页面/设置入口、完整差异/阻碍与显式确认，再进行真实FastAPI恢复联合、
  必要组合/最终门禁、完整候选精确CI/依赖合并及main接入核实；#160保持ACTIVE。
- 2026-10-13内部终态工作流已闭合：同一业务/素材拥有者内重验当前服务端、实际35表、
  档案及全部素材，保存独立RELEASING意图后收尾SQL，再以新阶段完整证明CAS发布
  COMPLETED/正文见证并清指针。中断保持阻断、仅向前续行；服务端又变化时追加追赶，
  不换epoch重发原请求，不回退身份。完成重复调用不联网/重替换，日常进度不锁定首次
  投影。最终同APK六完整类95项（41/20/34三个有界批次）通过，首次失败/夹具修正及
  中间版本结果见TESTING。本批仍在原恢复分支，不新建微型PR或强合未闭合
  链。后续为正式入口/缺口/显式确认/续行→真实FastAPI跨epoch联合→最终门禁及完整
  候选精确CI/依赖合并→main实际接入核实→人工验收；#160保持ACTIVE，默认v5不变。
- 2026-10-13持久追赶已在恢复分支闭合：不可覆盖意图CAS、完整35表SQL事务、当前
  游标/权限CAS及实际SQL命运冷续行；无旧请求换epoch重发，原目标/档案保留。
  新服务端快照改变时重新规划未提交意图；已提交只向前绑定，最新素材证明仍保持冻结。
  两个同APK完整批次20+69项通过；首轮2项新增夹具失败及修正完整记录在TESTING，
  不宣称全量/真实FastAPI联合或人工通过。继续最终放行→正式恢复入口→真实联合与
  受影响验证→完整候选精确CI/依赖合并；本批不新建零散PR，不强合未闭合链。
- [ ] 审查并冻结上述完整业务候选，明确允许合并的安全档位边界；尚未通过不改ready。
- [ ] 对边界明确的候选运行必要的完整受影响模块与组合验证，记录精确head、失败、未验证项。
- [ ] 依赖顺序通过保护门禁合并；核实 main 的生产入口/迁移/契约，不因 squash丢失后继base。
- [ ] 对未完成跨epoch链继续原目标；优先必要功能与联合验证，不再新增无关容量测试。
  恢复分支已完成实际SQL提交及原子新范围发布，但不单独放行。剩余依赖顺序为：
  新范围已具备专用目录/下载与持久安装续接、精确归档选包及完整素材核验；App根显示/导航生命周期、后台/小组件SQL孤儿读取发布防线及独立Activity屏障已接入候选，继续
  确认前素材预检、终态放行及正式缺口提示/差异/显式确认/中断恢复入口已接入分支，继续
  真实后端跨epoch联合与完整受影响验证→完整候选精确CI及依赖合并→main接入核实。
  素材阶段不得通过全局豁免指针、借用旧ready/像素或换epoch重发旧写请求来完成。
- 2026-10-12新范围素材恢复已在分支接入实际工作流和原运行时，不再仅有权限辅助对象。
  每轮前后核验真实SQL/旧档案/新DS，短期授权绑定当前认证，离开本轮关闭；只准入
  目录GET、内容GET、download日志及下载ready，普通声明/上传/选包/发布仍拒绝。
  原HTTP边界的登录/刷新免鉴权判断不能绕过恢复请求校验，恢复客户端禁止自动刷新。
  401/503和实际sending断点冷续行保留原新范围操作身份；旧ready/同hash字节不被采用。
  七完整类最终118/118通过，首轮新增夹具告警被门禁拒绝、未跑行为，细节见TESTING。
  当批阶段仍DESTINATION_BOUND，未接入正式消费者/入口、精确选包证明或实际FastAPI
  恢复联合；main仍af3aa8d、59开放PR、2worktree，无新增零散PR或强合未闭合链。
- 2026-10-13已补实际完整档案中的选包意图、新范围精确选择事务和全部素材证明，
  包含原始八表/schema/确认/目录、所有自有文件及当前/保留权威引用；默认未选包与
  明确不选包分别保留。精确版本/完整元数据/必需asset/文件缺失仍阻塞，不自动换包。
  源码未改期间，两轮同APK的六个完整类109+32项均通过，详细范围和回执见TESTING。
  全部正式消费者屏障、确认前缺口预检、正式恢复入口/最终放行及真实FastAPI联合
  仍待完成；阶段与指针/SQL仍冻结，返回素材证据不是启用许可。本批只保存原分支，
  main/59开放PR/2worktree未改变，不新建零散PR，不计作主分支交付或默认v5启用。
- [ ] 必要人工验收前停止并给用户可合并操作的步骤；不自动发布或启用v5。
- 2026-10-13已接入候选App根显示屏障、指针持久化前的同步绘制撤销及认证导航图生命周期。
  丢失指针不能打开冻结SQL/孤儿阶段，坏状态保持诊断；登录不预创建业务VM，冻结/退出账户/
  普通导航移除清理旧VM，正常游标更新不重置详情页。六完整类52项最终通过，首轮夹具发现
  失败及验证限制见TESTING。尚未完成后台/小组件SQL孤儿读取防线、确认前素材缺口预检、
  正式恢复入口/最终放行与真实FastAPI联合；存储注册表测试不代替正式旋转/Hilt页面旅程。
  本批保留在原恢复分支，不新建微型PR、不强行合并未闭合链、不计作main交付或v5启用。
- [ ] 独立处理依赖升级；#261/#265 不阻止有完整证据的非依赖升级业务批次。
- 2026-10-13已补普通同步/设备/素材Worker/午夜刷新及小组件事实、计时快照、逐秒与
  最终发布的实际Room/DataStore门禁；丢失指针、孤儿/损坏状态不进行发现、素材初始化、
  缓存改写或网络重试。持锁读取不重复进入账户mutex，合法取消仅开放新许可。
  19完整类134项通过，验证范围/限制见TESTING；尚需独立小组件Activity屏障、确认前
  素材预检、正式入口/最终放行及真实后端联合。主分支仍af3aa8d，59开放PR、2worktree；
  本批不新建微型PR，不强合未闭合链，不计作主分支交付或默认v5启用。
- 2026-10-13已将独立小组件Activity接入根显示屏障，页面只接受最初的显示许可；
  快速冻结/取消跳过Pending也不复活旧意图，关闭仅结束Activity，不触发业务撤销。
  计时捕获/策略/动作与切换确认补实际读取门禁。首轮34项有1项新夹具失败，修正夹具
  并补生命周期边界后4完整类18项通过；范围与未验证项见TESTING，不拼称35项全绿。
  只保存原恢复分支，不新增微型PR；确认前素材预检、正式入口/最终放行、真实后端联合、
  精确CI与人工验收仍待完成，main没有新恢复链交付，默认v5不变。
- 2026-10-13已在原恢复分支补确认前所需素材元数据预检：同事务只读新范围业务/精确包/
  直接引用，冻结前及确认/提交前冷续行重新核验，不采用缓存结果或其他版本。实际原生
  文件、全部目录及保留引用仍由提交后完整证明负责，不称为全部资源已经可用。后端三
  完整文件最终113通过；Android相同APK的完整HTTP/目的地68与档案34项通过，首轮
  编译失败、37项中的3项实际MAP解析失败及完整限制见TESTING。main仍af3aa8d、59开放
  PR、2worktree；没有新增微型PR或强合未闭合链。正式入口/缺口提示/最终放行、真实
  后端恢复联合、精确CI及人工验收仍未完成，#160继续ACTIVE，不计作主分支交付。
- 2026-10-13已补DESTINATION_BOUND后实际SQL/旧档案/新DS约束的短期业务GET重验，
  不放宽旧预览的同epoch拒绝或普通同步。服务端已有变化只返回待追赶证据，不修改业务
  或提前放行；原始控制类型与完整SQL命运在同一事务核对，禁止Room转换掩盖异常。
  三完整类34项通过后，控制补强最终仅重验完整目的地15项并通过；首次选择错误、精确
  证据及未验证项见TESTING，不拼称最终34项或全量通过。本批仍保存原恢复分支，未增
  微型PR/强合未闭合链。待完成持久追赶/最终放行、正式入口/缺口提示、真实后端恢复
  联合、精确CI及人工验收；main仍af3aa8d、59开放PR、2worktree，#160保持ACTIVE。
- [ ] 最终目标完成仍须已合并源码、正式入口可用、联合/人工验收和契约一致；
  当前不报告任意估算百分比，#160继续OPEN。

每一批闭环：范围/依赖→主分支对照→冻结候选→代码审查→定向及必要组合验证→精确head CI→
合并→main实际接入核实→状态更新→确认无独有修改/剩余引用后清理。
连续堆叠不作为默认开发方法；已有堆叠不做rebase/force-push式“整理”。

## 全部开放 PR 清单

快照中每个head及其base列出如下；非main base代表尚有堆叠依赖。
Checks为该head的GitHub状态，不代表Android行为/人工验收。
除#261/#265外，数字递增的业务PR各自base均为前一业务PR的head；不存在漏号#379的PR。

| PR | head | base | 精确head | 状态及必要checks |
|---|---|---|---|---|
| [#261](https://github.com/XiaTian-X/DayForge/pull/261) | dependabot/gradle/android/android-compatible-updates-22951eee10 | main | cf1550e9acd67c855d29f5227145eb2434b2b5d8 | ready; repository=SUCCESS, backend=SKIPPED, android=FAILURE |
| [#265](https://github.com/XiaTian-X/DayForge/pull/265) | dependabot/pip/backend/backend-compatible-updates-2a409279e4 | main | b29a136263309988d37c37acc94092105a549b6e | ready; repository=SUCCESS, backend=SUCCESS, android=SKIPPED |
| [#331](https://github.com/XiaTian-X/DayForge/pull/331) | codex/df-329-production-workflows | main | 37fd498b5e65565cd8342a0567a3660fe31b3d87 | draft; repository=SUCCESS, backend=SUCCESS, android=SUCCESS |
| [#333](https://github.com/XiaTian-X/DayForge/pull/333) | codex/df-332-challenge-rounds | codex/df-329-production-workflows | 5efdb054e801cb1c6919503bdd0fc946d7ea2d14 | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#334](https://github.com/XiaTian-X/DayForge/pull/334) | codex/df-332-round-contract | codex/df-332-challenge-rounds | b486303dd682fa4d16f6b671331b8c3505170b01 | draft; repository=SUCCESS, backend=SUCCESS, android=SUCCESS |
| [#335](https://github.com/XiaTian-X/DayForge/pull/335) | codex/df-332-round-storage | codex/df-332-round-contract | 890e0de15b5e0435c7f5b821e1156f166ee5da78 | draft; repository=SUCCESS, backend=SUCCESS, android=SKIPPED |
| [#336](https://github.com/XiaTian-X/DayForge/pull/336) | codex/df-332-round-sync | codex/df-332-round-storage | 868f6d8b1a8b6a009e5e7a79e08440370e75054d | draft; repository=SUCCESS, backend=CANCELLED, android=SUCCESS |
| [#337](https://github.com/XiaTian-X/DayForge/pull/337) | codex/df-332-round-http | codex/df-332-round-sync | d13a7b2fdd19c20b3fdf6818ee2da3c8dd47f0f5 | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#338](https://github.com/XiaTian-X/DayForge/pull/338) | codex/df-332-round-room | codex/df-332-round-http | dc31e3b914015699d9db5f9b9f7293a379f40b49 | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#339](https://github.com/XiaTian-X/DayForge/pull/339) | codex/df-332-round-writes | codex/df-332-round-room | 29821ccc3d509847b3b8508d7fa147e378b5c43c | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#340](https://github.com/XiaTian-X/DayForge/pull/340) | codex/df-332-round-timers | codex/df-332-round-writes | 0696c886b7f04c5b4723ac765c6e80e075e7423e | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#341](https://github.com/XiaTian-X/DayForge/pull/341) | codex/df-332-round-once | codex/df-332-round-timers | 7315fff9ebe79fa8902c8b99efccdedd88776ab8 | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#342](https://github.com/XiaTian-X/DayForge/pull/342) | codex/df-332-round-runtime | codex/df-332-round-once | 2ef8453e91d5bec04dfd32d8674ca7ef9d22dc67 | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#343](https://github.com/XiaTian-X/DayForge/pull/343) | codex/df-332-round-initial | codex/df-332-round-runtime | c016d895699974fe20acbe1e3d10b37a33d288fc | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#344](https://github.com/XiaTian-X/DayForge/pull/344) | codex/df-332-round-goal-frontier | codex/df-332-round-initial | 8253e6b865daf5cbdbe0fc3b97b9252700a499a0 | draft; repository=SUCCESS, backend=CANCELLED, android=SUCCESS |
| [#345](https://github.com/XiaTian-X/DayForge/pull/345) | codex/df-332-round-writers | codex/df-332-round-goal-frontier | fb596047b9aec4a04f3139f24965f62d97a1caca | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#346](https://github.com/XiaTian-X/DayForge/pull/346) | codex/df-332-count-round-read | codex/df-332-round-writers | 85762e6b42561ebc9190811234a187ac8421a5eb | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#347](https://github.com/XiaTian-X/DayForge/pull/347) | codex/df-332-offline-restart | codex/df-332-count-round-read | 13d63100bb816df135df22023688c43ecb7c4748 | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#348](https://github.com/XiaTian-X/DayForge/pull/348) | codex/df-332-restart-recording | codex/df-332-offline-restart | a9db08c14085ddb87eccb6b3824ce2a8353f1a9b | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#349](https://github.com/XiaTian-X/DayForge/pull/349) | codex/df-332-check-round-read | codex/df-332-restart-recording | b3cee805eaeabd1d5b24e2979eed466f9541c286 | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#350](https://github.com/XiaTian-X/DayForge/pull/350) | codex/df-332-timer-round-read | codex/df-332-check-round-read | a17d3273b0dd4928e12aa2894472fc9772f11c29 | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#351](https://github.com/XiaTian-X/DayForge/pull/351) | codex/df-332-round-consumer-read | codex/df-332-timer-round-read | 9c22a2de3512aa511af403dff51d72ea0cb2434b | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#352](https://github.com/XiaTian-X/DayForge/pull/352) | codex/df-332-midnight-activity-read | codex/df-332-round-consumer-read | 737d50fc60d1a17177740a963fea032213d0e40c | draft; repository=SUCCESS, backend=SKIPPED, android=FAILURE |
| [#353](https://github.com/XiaTian-X/DayForge/pull/353) | codex/df-332-apk-failure-diagnostics | codex/df-332-midnight-activity-read | 78fe087d649ab1295370f6bc0610ad5d4a2f8595 | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#354](https://github.com/XiaTian-X/DayForge/pull/354) | codex/df-332-timer-widget-publication | codex/df-332-apk-failure-diagnostics | e153f8c03a747cb5a061bb99b461bf8f822342d5 | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#355](https://github.com/XiaTian-X/DayForge/pull/355) | codex/df-332-timer-widget-ticks | codex/df-332-timer-widget-publication | b042c27169c9d2fea03ca7018855aaaf2794261e | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#356](https://github.com/XiaTian-X/DayForge/pull/356) | codex/df-332-widget-display-publication | codex/df-332-timer-widget-ticks | 3e78574a7af5071a3302840aed732dedc52d9855 | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#357](https://github.com/XiaTian-X/DayForge/pull/357) | codex/df-332-summary-widget-publication | codex/df-332-widget-display-publication | 210779a3e7bb978477969cd42badb6a05f9619ef | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#358](https://github.com/XiaTian-X/DayForge/pull/358) | codex/df-332-widget-account-lifecycle | codex/df-332-summary-widget-publication | 6b347ef975263c247dd657b1136babc58ab36c85 | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#359](https://github.com/XiaTian-X/DayForge/pull/359) | codex/df-332-widget-configuration-lifecycle | codex/df-332-widget-account-lifecycle | 3259fa2fcaeffa0b0447cfc651a9a74def8b5949 | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#360](https://github.com/XiaTian-X/DayForge/pull/360) | codex/df-160-widget-activity-theme | codex/df-332-widget-configuration-lifecycle | c291e96076d7efa89cc8d29b391477a8d3b38e4c | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#361](https://github.com/XiaTian-X/DayForge/pull/361) | codex/df-160-widget-entry-cleanup | codex/df-160-widget-activity-theme | e1711252edf73a64c1fa66795c4082cf3c5a92d8 | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#362](https://github.com/XiaTian-X/DayForge/pull/362) | codex/df-160-config-bundle-files | codex/df-160-widget-entry-cleanup | ce292f3ebd2430a5426666a831adfbc5b2e8377e | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#363](https://github.com/XiaTian-X/DayForge/pull/363) | codex/df-160-config-owned-assets | codex/df-160-config-bundle-files | 515c1a666ae940725c8f1ceeb7c76e38de3f068e | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#364](https://github.com/XiaTian-X/DayForge/pull/364) | codex/df-160-config-business-snapshot | codex/df-160-config-owned-assets | 21d1fe6359f724bd8b335eb46c95c7410c0910cd | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#365](https://github.com/XiaTian-X/DayForge/pull/365) | codex/df-160-config-import-identities | codex/df-160-config-business-snapshot | b0b88ad9bb1546f1c492a54191a42893689a86f3 | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#366](https://github.com/XiaTian-X/DayForge/pull/366) | codex/df-160-config-import-journal | codex/df-160-config-import-identities | 885a056f680a0bc5aa778074ed36fef4a98577a7 | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#367](https://github.com/XiaTian-X/DayForge/pull/367) | codex/df-160-config-replacement-preview | codex/df-160-config-import-journal | 1cea500b21a7bfafaa8dc83c019b6fffeb1a6e92 | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#368](https://github.com/XiaTian-X/DayForge/pull/368) | codex/df-160-config-replacement | codex/df-160-config-replacement-preview | a425d0179f8755f8be8e8bcf0074f578188ee5e8 | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#369](https://github.com/XiaTian-X/DayForge/pull/369) | codex/df-160-config-history | codex/df-160-config-replacement | 90678bceaf00b1aee0a43e490b9990337dc2c774 | draft; repository=SUCCESS, backend=SUCCESS, android=SUCCESS |
| [#370](https://github.com/XiaTian-X/DayForge/pull/370) | codex/df-160-config-settings | codex/df-160-config-history | 02119573dca95b7389928b080c438a586c76ea79 | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#371](https://github.com/XiaTian-X/DayForge/pull/371) | codex/df-160-config-choices | codex/df-160-config-settings | 77c5bd220eb060bd3ecb418cb24b13abfd1d5f2c | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#372](https://github.com/XiaTian-X/DayForge/pull/372) | codex/df-160-sync-worker | codex/df-160-config-choices | 14e8c385f33e017244896cdbd24063554fa30ab6 | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#373](https://github.com/XiaTian-X/DayForge/pull/373) | codex/df-160-sync-routing | codex/df-160-sync-worker | f436c726a3ddce7c9335934eb1acb10d412f5182 | draft; repository=SUCCESS, backend=SKIPPED, android=FAILURE |
| [#374](https://github.com/XiaTian-X/DayForge/pull/374) | codex/df-160-device-controls | codex/df-160-sync-routing | a2e1708f11ac042af8289a7939ca20f9527848f6 | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#375](https://github.com/XiaTian-X/DayForge/pull/375) | codex/df-160-compiler-capacity | codex/df-160-device-controls | e4021a3b150cae53b39bb92a93e61383017dd2ee | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#376](https://github.com/XiaTian-X/DayForge/pull/376) | codex/df-160-sync-retry | codex/df-160-compiler-capacity | 748efa9abc017ce58a4d280fac415dbf444d7ecd | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#377](https://github.com/XiaTian-X/DayForge/pull/377) | codex/df-160-sync-problems | codex/df-160-sync-retry | 667c0a5ff844645ab70a6f53786de298646fa873 | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#378](https://github.com/XiaTian-X/DayForge/pull/378) | codex/df-160-sync-recovery | codex/df-160-sync-problems | fd242204aec6b78858484f6460182a1971a4c136 | draft; repository=SUCCESS, backend=CANCELLED, android=SUCCESS |
| [#380](https://github.com/XiaTian-X/DayForge/pull/380) | codex/df-379-backend-ci-capacity | codex/df-160-sync-recovery | 6c5885705d99bf5b15c9c957bba6529ea99605bd | ready; repository=SUCCESS, backend=SUCCESS, android=SUCCESS |
| [#381](https://github.com/XiaTian-X/DayForge/pull/381) | codex/df-160-restart-entry | codex/df-379-backend-ci-capacity | fd3d5c366cda37b2ccde756c6031b46e697334ae | ready; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#382](https://github.com/XiaTian-X/DayForge/pull/382) | codex/df-160-widget-restart | codex/df-160-restart-entry | 49dc7fad0d1176ce1e98266302389fb53927bf67 | ready; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |
| [#383](https://github.com/XiaTian-X/DayForge/pull/383) | codex/df-160-auth-restore | codex/df-160-widget-restart | 9996d95d5571147bbfd1695fb2238b8fc335232d | ready; repository=SUCCESS, backend=SUCCESS, android=SKIPPED |
| [#384](https://github.com/XiaTian-X/DayForge/pull/384) | codex/df-160-product-restore | codex/df-160-auth-restore | 8c78722fa4c5b851d41ab8066dbfd8ee03e5c1a2 | ready; repository=SUCCESS, backend=SUCCESS, android=SKIPPED |
| [#385](https://github.com/XiaTian-X/DayForge/pull/385) | codex/df-160-real-backend-joint | codex/df-160-product-restore | 42ee9e549024f1559cf19db1792a72ae6081886c | ready; repository=SUCCESS, backend=SUCCESS, android=SUCCESS |
| [#386](https://github.com/XiaTian-X/DayForge/pull/386) | codex/df-160-material-config-joint | codex/df-160-real-backend-joint | 770652bd979e1e61c59cb978210f1d604f7bb00c | ready; repository=SUCCESS, backend=SUCCESS, android=SUCCESS |
| [#387](https://github.com/XiaTian-X/DayForge/pull/387) | codex/df-160-scheduler-joint | codex/df-160-material-config-joint | 5c73685a6d25505299fee3d203ae0693ebf9400b | ready; repository=SUCCESS, backend=SUCCESS, android=SUCCESS |
| [#388](https://github.com/XiaTian-X/DayForge/pull/388) | codex/df-160-v5-preflight | codex/df-160-scheduler-joint | d7a1b2dc17e4b66de7e4bb3828ab52c04063ca29 | ready; repository=SUCCESS, backend=SUCCESS, android=SKIPPED |
| [#389](https://github.com/XiaTian-X/DayForge/pull/389) | codex/df-160-replica-recovery | codex/df-160-v5-preflight | 73c84a931f4ba1280bc8d7a10bba0aa5532ac7be | draft; repository=SUCCESS, backend=SKIPPED, android=SUCCESS |

## 全部本地及远端引用

以下是审计分支建立后的引用快照，含WIP最新检查点；origin/HEAD不计作实际远端分支。
公开清单不含凭据、数据库或设备配置。

```text
codex/df-160-auth-restore 9996d95d5571147bbfd1695fb2238b8fc335232d
codex/df-160-compiler-capacity e4021a3b150cae53b39bb92a93e61383017dd2ee
codex/df-160-config-bundle-files ce292f3ebd2430a5426666a831adfbc5b2e8377e
codex/df-160-config-business-snapshot 21d1fe6359f724bd8b335eb46c95c7410c0910cd
codex/df-160-config-choices 77c5bd220eb060bd3ecb418cb24b13abfd1d5f2c
codex/df-160-config-history 90678bceaf00b1aee0a43e490b9990337dc2c774
codex/df-160-config-import-identities b0b88ad9bb1546f1c492a54191a42893689a86f3
codex/df-160-config-import-journal 885a056f680a0bc5aa778074ed36fef4a98577a7
codex/df-160-config-owned-assets 515c1a666ae940725c8f1ceeb7c76e38de3f068e
codex/df-160-config-replacement a425d0179f8755f8be8e8bcf0074f578188ee5e8
codex/df-160-config-replacement-preview 1cea500b21a7bfafaa8dc83c019b6fffeb1a6e92
codex/df-160-config-settings 02119573dca95b7389928b080c438a586c76ea79
codex/df-160-device-controls a2e1708f11ac042af8289a7939ca20f9527848f6
codex/df-160-integration-audit 4341408d0a2ce5664d8f380fb4d2a42690e29611
codex/df-160-material-config-joint 770652bd979e1e61c59cb978210f1d604f7bb00c
codex/df-160-product-restore 8c78722fa4c5b851d41ab8066dbfd8ee03e5c1a2
codex/df-160-real-backend-joint 42ee9e549024f1559cf19db1792a72ae6081886c
codex/df-160-recovery-capacity 6c5885705d99bf5b15c9c957bba6529ea99605bd
codex/df-160-recovery-snapshot b173cc2d6488d7645be34c4a1cf5428b76a6f904
codex/df-160-replica-recovery 73c84a931f4ba1280bc8d7a10bba0aa5532ac7be
codex/df-160-restart-entry fd3d5c366cda37b2ccde756c6031b46e697334ae
codex/df-160-scheduler-joint 5c73685a6d25505299fee3d203ae0693ebf9400b
codex/df-160-sync-problems 667c0a5ff844645ab70a6f53786de298646fa873
codex/df-160-sync-recovery fd242204aec6b78858484f6460182a1971a4c136
codex/df-160-sync-retry 748efa9abc017ce58a4d280fac415dbf444d7ecd
codex/df-160-sync-routing f436c726a3ddce7c9335934eb1acb10d412f5182
codex/df-160-sync-worker 14e8c385f33e017244896cdbd24063554fa30ab6
codex/df-160-v5-preflight d7a1b2dc17e4b66de7e4bb3828ab52c04063ca29
codex/df-160-widget-activity-theme c291e96076d7efa89cc8d29b391477a8d3b38e4c
codex/df-160-widget-entry-cleanup e1711252edf73a64c1fa66795c4082cf3c5a92d8
codex/df-160-widget-restart 49dc7fad0d1176ce1e98266302389fb53927bf67
codex/df-161-ui-regressions d37090cfef201807580cc54c0c5f2b69c2ef5b56
codex/df-178-test-database-lifetimes 242540317632d77916fe24c774b9ab232ca0c8b2
codex/df-180-theme-storage 5094e9411ad6c75cd412e3ee779d87639847852f
codex/df-181-api-integration 52332390a899ddc0787821f97d647653eed0c46c
codex/df-181-contracts 4aae8c4ec688557e705a425bd1bd64067112b2df
codex/df-181-one-time-wire 80abe05b06f17500b2464f21543e70ac9ecd34b4
codex/df-181-theme-config eb0131f28d610d023099fe0f070de9a3edf03773
codex/df-185-sqlite-snapshot 2162d04c1dc0d4b8e3963a3e2d7ac86cf35a40b0
codex/df-188-one-time-recovery 82268377b41bcb97b37f895272996d46b9560beb
codex/df-188-one-time-storage d462136e38a67ccdd0f2f308f9adda1f17ad6514
codex/df-191-account-asset-storage 13a5be285bd731b1fe649d1fe5dcd1df9824a4dd
codex/df-191-asset-declarations b64b49290a5b2b5085166a9c69616e415c4f1ace
codex/df-191-object-appearance 9292f14e7174dee28f5d374f5b472b8da5b883e8
codex/df-191-structural-sync 2108b3fe24c821c08062bc2aa6adc7891a22abd0
codex/df-196-bounded-input c8753865643aec2f5f255b027a226ce7192e4593
codex/df-196-image-validation ad3b28b40f94d0d1ebd942522cfa720e74ac6539
codex/df-196-png-validation 63a26d20a792024b08cc56e423e8ae00f016461e
codex/df-196-svg-rendering 79d22158389179c76d92d80da6fcb478e0f08309
codex/df-202-asset-backup-closure a86ad7b993dd53a05177c8aa0d3e1342ffc262aa
codex/df-202-asset-backups c1bc29599a27bedc701321eb7cb07112d5c8172f
codex/df-202-asset-recovery e527b3e23075f46f11fe2b37aec1d86bdefb9df0
codex/df-202-asset-storage ab439e7834f31ce7847308b6eadd11b41d6192fc
codex/df-202-asset-transactions 91ac89d96cace0c5187afbcb2d744ea4370531e2
codex/df-202-asset-worker a218766ed3663db432f7cfaaa7afa1f20e46a7fe
codex/df-202-logical-asset-closure a21b65643e0bcbac782c78d0e2fd7692d7411bf2
codex/df-202-maintenance-entrypoints a3067046e8ebd668af5be066368b95835a9d416f
codex/df-211-authoritative-results 1be10f6f83ef160ab364ecf10f2aaef1458fc26e
codex/df-211-common-facts d18d1b76e77a0c57bf4d46ae16d24f76ddf32c96
codex/df-211-common-restore 91a94108d9ca55f2be078ade1f2ba727f845cd7d
codex/df-211-completion-prompts 0114a3d1f3289b715d5244fb38498f295eb4a1d2
codex/df-211-history-restore 6b8af533156ffe9b3717d5e9333cb7e9e3c75783
codex/df-211-local-intent-session 30419fb7e52169c6669043101f0d103c66825680
codex/df-211-next-structure 338c523e6e7e0fb5be96822aebd8c83b386501b5
codex/df-211-one-time-local-store 07d371dc69682a93405b61468488bcdde37f1e72
codex/df-211-one-time-room dc8f0d25eb68d28dc73bbb9f4332c5d48e44a416
codex/df-211-structure-mapping 16b92bbb3407fbedd5ca9dcbac966d57d3a31fa7
codex/df-211-structure-restore 38db10c642b47cb86045c7ed80647dd7985432bb
codex/df-211-timer-precision ac6b1ad911ed4912f3c9c412d47143604dcb1568
codex/df-211-transmission-binding 2ffad7162019052dd28f5e155271b0b4009aa943
codex/df-214-svg-scanner-cost 3f23de3c439897ad8fd01008c7fbf926ba1372fd
codex/df-219-network-identities 2c2c5d588acfb3f49e55c622726e72b63665581c
codex/df-229-icon-pack-input b215de822dc5523d3e67d6533b24f0fc9a5f00ae
codex/df-231-local-blob-files 46fa87be707cf240dc0e25f6df903a9c282d9cd2
codex/df-233-shared-theme-output 8234e3aae4985a99508280f9bca209469d06ebbc
codex/df-235-shared-icon-render 30b76f9e7978dc9f243bc51c6e8fa577b5141951
codex/df-237-theme-files e344667d0c7edd66804362bee0454fbdf2215560
codex/df-239-device-theme-selection 35990f6eb53c869e6e15b5c6bea9cb8d9f11e16e
codex/df-241-builtin-themes 4d8c5d3eed70c08bc9abd8ef38db85d2c9a4c45e
codex/df-243-theme-management 8846e71f078d94e2e508dacf39a30fcd01e0765c
codex/df-244-android-test-gate 9ed78fa15ed26445181d836738827fedaca75828
codex/df-247-config-archive 45e21c2bf11aaef3434737e9cf8ef333f2c1052d
codex/df-249-chart-appearance 741f333f9b398c45d6f51e846f32f15a996d80f7
codex/df-251-status-appearance fa76203f55886f5ca10ba1d9580c9c186e8c19d8
codex/df-253-theme-editor 09f514d172a9dc5b7415d02d687f2d79838f1a5d
codex/df-255-theme-identity b7b666b3d42d8d0368881cec02c174074345dafa
codex/df-257-recovery-checkpoint 5c2d6bc1bde7a9e0148102ab6d130201a6bf8f03
codex/df-259-account-icon-store 5a020cbc90cf62bc96a7eeefb5c94ff5eae1e5e4
codex/df-259-icon-install-recovery cc6f3bfc17e5c749b67768687d183965d48d3390
codex/df-259-icon-lifecycle 8a85be3168b7e39dbfb11622c33183f2187ee68a
codex/df-259-icon-render-cache eb2ec16013fc26d0d4aa748e68aa44041445b5c4
codex/df-263-jwt-security ce50b260a32be8815d8c6632f840cec9a44200d0
codex/df-269-active-icon-pack 624cba41847dfbe44f90d9c8c5a6e24366917c79
codex/df-314-structural-causality 2310d7f8b7dd157eec02a6617498f8483efad1be
codex/df-319-scoped-native-checks 1405d98bb57e67e106110c1a507675fa97cd4843
codex/df-321-ordinary-result-closure d96303a6957bc729cfd36c5076206c62bbb5b4ed
codex/df-324-timer-result-integration 5a3195b3fb09556512616df5a5b0b4118307c840
codex/df-326-once-http-integration 6786396696d68c8eabc40b6a28adb5bf7165f47d
codex/df-328-unified-sync-runtime 5d0d0e32d5ac50e99344a19630fa5caa265cfef0
codex/df-329-production-workflows 37fd498b5e65565cd8342a0567a3660fe31b3d87
codex/df-332-apk-failure-diagnostics 78fe087d649ab1295370f6bc0610ad5d4a2f8595
codex/df-332-challenge-rounds 5efdb054e801cb1c6919503bdd0fc946d7ea2d14
codex/df-332-check-round-read b3cee805eaeabd1d5b24e2979eed466f9541c286
codex/df-332-count-round-read 85762e6b42561ebc9190811234a187ac8421a5eb
codex/df-332-midnight-activity-read 737d50fc60d1a17177740a963fea032213d0e40c
codex/df-332-offline-restart 13d63100bb816df135df22023688c43ecb7c4748
codex/df-332-restart-recording a9db08c14085ddb87eccb6b3824ce2a8353f1a9b
codex/df-332-round-consumer-read 9c22a2de3512aa511af403dff51d72ea0cb2434b
codex/df-332-round-contract b486303dd682fa4d16f6b671331b8c3505170b01
codex/df-332-round-goal-frontier 8253e6b865daf5cbdbe0fc3b97b9252700a499a0
codex/df-332-round-http d13a7b2fdd19c20b3fdf6818ee2da3c8dd47f0f5
codex/df-332-round-initial c016d895699974fe20acbe1e3d10b37a33d288fc
codex/df-332-round-once 7315fff9ebe79fa8902c8b99efccdedd88776ab8
codex/df-332-round-room dc31e3b914015699d9db5f9b9f7293a379f40b49
codex/df-332-round-runtime 2ef8453e91d5bec04dfd32d8674ca7ef9d22dc67
codex/df-332-round-storage 890e0de15b5e0435c7f5b821e1156f166ee5da78
codex/df-332-round-sync 868f6d8b1a8b6a009e5e7a79e08440370e75054d
codex/df-332-round-timers 0696c886b7f04c5b4723ac765c6e80e075e7423e
codex/df-332-round-writers fb596047b9aec4a04f3139f24965f62d97a1caca
codex/df-332-round-writes 29821ccc3d509847b3b8508d7fa147e378b5c43c
codex/df-332-summary-widget-publication 210779a3e7bb978477969cd42badb6a05f9619ef
codex/df-332-timer-round-read a17d3273b0dd4928e12aa2894472fc9772f11c29
codex/df-332-timer-widget-publication e153f8c03a747cb5a061bb99b461bf8f822342d5
codex/df-332-timer-widget-ticks b042c27169c9d2fea03ca7018855aaaf2794261e
codex/df-332-widget-account-lifecycle 6b347ef975263c247dd657b1136babc58ab36c85
codex/df-332-widget-configuration-lifecycle 3259fa2fcaeffa0b0447cfc651a9a74def8b5949
codex/df-332-widget-display-publication 3e78574a7af5071a3302840aed732dedc52d9855
codex/df-379-backend-ci-capacity 6c5885705d99bf5b15c9c957bba6529ea99605bd
main 6a54d25a66e42dbf1fc025c8acc4b468b38be9b4
origin/codex/df-160-auth-restore 9996d95d5571147bbfd1695fb2238b8fc335232d
origin/codex/df-160-compiler-capacity e4021a3b150cae53b39bb92a93e61383017dd2ee
origin/codex/df-160-config-bundle-files ce292f3ebd2430a5426666a831adfbc5b2e8377e
origin/codex/df-160-config-business-snapshot 21d1fe6359f724bd8b335eb46c95c7410c0910cd
origin/codex/df-160-config-choices 77c5bd220eb060bd3ecb418cb24b13abfd1d5f2c
origin/codex/df-160-config-history 90678bceaf00b1aee0a43e490b9990337dc2c774
origin/codex/df-160-config-import-identities b0b88ad9bb1546f1c492a54191a42893689a86f3
origin/codex/df-160-config-import-journal 885a056f680a0bc5aa778074ed36fef4a98577a7
origin/codex/df-160-config-owned-assets 515c1a666ae940725c8f1ceeb7c76e38de3f068e
origin/codex/df-160-config-replacement a425d0179f8755f8be8e8bcf0074f578188ee5e8
origin/codex/df-160-config-replacement-preview 1cea500b21a7bfafaa8dc83c019b6fffeb1a6e92
origin/codex/df-160-config-settings 02119573dca95b7389928b080c438a586c76ea79
origin/codex/df-160-device-controls a2e1708f11ac042af8289a7939ca20f9527848f6
origin/codex/df-160-material-config-joint 770652bd979e1e61c59cb978210f1d604f7bb00c
origin/codex/df-160-product-restore 8c78722fa4c5b851d41ab8066dbfd8ee03e5c1a2
origin/codex/df-160-real-backend-joint 42ee9e549024f1559cf19db1792a72ae6081886c
origin/codex/df-160-replica-recovery 73c84a931f4ba1280bc8d7a10bba0aa5532ac7be
origin/codex/df-160-restart-entry fd3d5c366cda37b2ccde756c6031b46e697334ae
origin/codex/df-160-scheduler-joint 5c73685a6d25505299fee3d203ae0693ebf9400b
origin/codex/df-160-sync-problems 667c0a5ff844645ab70a6f53786de298646fa873
origin/codex/df-160-sync-recovery fd242204aec6b78858484f6460182a1971a4c136
origin/codex/df-160-sync-retry 748efa9abc017ce58a4d280fac415dbf444d7ecd
origin/codex/df-160-sync-routing f436c726a3ddce7c9335934eb1acb10d412f5182
origin/codex/df-160-sync-worker 14e8c385f33e017244896cdbd24063554fa30ab6
origin/codex/df-160-v5-preflight d7a1b2dc17e4b66de7e4bb3828ab52c04063ca29
origin/codex/df-160-widget-activity-theme c291e96076d7efa89cc8d29b391477a8d3b38e4c
origin/codex/df-160-widget-entry-cleanup e1711252edf73a64c1fa66795c4082cf3c5a92d8
origin/codex/df-160-widget-restart 49dc7fad0d1176ce1e98266302389fb53927bf67
origin/codex/df-329-production-workflows 37fd498b5e65565cd8342a0567a3660fe31b3d87
origin/codex/df-332-apk-failure-diagnostics 78fe087d649ab1295370f6bc0610ad5d4a2f8595
origin/codex/df-332-challenge-rounds 5efdb054e801cb1c6919503bdd0fc946d7ea2d14
origin/codex/df-332-check-round-read b3cee805eaeabd1d5b24e2979eed466f9541c286
origin/codex/df-332-count-round-read 85762e6b42561ebc9190811234a187ac8421a5eb
origin/codex/df-332-midnight-activity-read 737d50fc60d1a17177740a963fea032213d0e40c
origin/codex/df-332-offline-restart 13d63100bb816df135df22023688c43ecb7c4748
origin/codex/df-332-restart-recording a9db08c14085ddb87eccb6b3824ce2a8353f1a9b
origin/codex/df-332-round-consumer-read 9c22a2de3512aa511af403dff51d72ea0cb2434b
origin/codex/df-332-round-contract b486303dd682fa4d16f6b671331b8c3505170b01
origin/codex/df-332-round-goal-frontier 8253e6b865daf5cbdbe0fc3b97b9252700a499a0
origin/codex/df-332-round-http d13a7b2fdd19c20b3fdf6818ee2da3c8dd47f0f5
origin/codex/df-332-round-initial c016d895699974fe20acbe1e3d10b37a33d288fc
origin/codex/df-332-round-once 7315fff9ebe79fa8902c8b99efccdedd88776ab8
origin/codex/df-332-round-room dc31e3b914015699d9db5f9b9f7293a379f40b49
origin/codex/df-332-round-runtime 2ef8453e91d5bec04dfd32d8674ca7ef9d22dc67
origin/codex/df-332-round-storage 890e0de15b5e0435c7f5b821e1156f166ee5da78
origin/codex/df-332-round-sync 868f6d8b1a8b6a009e5e7a79e08440370e75054d
origin/codex/df-332-round-timers 0696c886b7f04c5b4723ac765c6e80e075e7423e
origin/codex/df-332-round-writers fb596047b9aec4a04f3139f24965f62d97a1caca
origin/codex/df-332-round-writes 29821ccc3d509847b3b8508d7fa147e378b5c43c
origin/codex/df-332-summary-widget-publication 210779a3e7bb978477969cd42badb6a05f9619ef
origin/codex/df-332-timer-round-read a17d3273b0dd4928e12aa2894472fc9772f11c29
origin/codex/df-332-timer-widget-publication e153f8c03a747cb5a061bb99b461bf8f822342d5
origin/codex/df-332-timer-widget-ticks b042c27169c9d2fea03ca7018855aaaf2794261e
origin/codex/df-332-widget-account-lifecycle 6b347ef975263c247dd657b1136babc58ab36c85
origin/codex/df-332-widget-configuration-lifecycle 3259fa2fcaeffa0b0447cfc651a9a74def8b5949
origin/codex/df-332-widget-display-publication 3e78574a7af5071a3302840aed732dedc52d9855
origin/codex/df-379-backend-ci-capacity 6c5885705d99bf5b15c9c957bba6529ea99605bd
origin/dependabot/gradle/android/android-compatible-updates-22951eee10 cf1550e9acd67c855d29f5227145eb2434b2b5d8
origin/dependabot/pip/backend/backend-compatible-updates-2a409279e4 b29a136263309988d37c37acc94092105a549b6e
origin/main 4341408d0a2ce5664d8f380fb4d2a42690e29611
```

## 历史分支可清理证明

68条分支均满足：GitHub同head已MERGED；对应mergeCommit在main历史；
从main共同祖先到分支头的完整 `git diff --binary --full-index`
与main中该squash提交前后diff的SHA256一致（不只比较patch-id）。
`git cherry` 的单commit“+”不能推翻完整squash证明。只在引用未变化、工作树清洁、
不再被开放PR作为base/head使用且恢复档案验证后清理。

| 本地引用 | 原head | 已合并PR / main提交 | 完整patch SHA256 |
|---|---|---|---|
| codex/df-161-ui-regressions | d37090cfef201807580cc54c0c5f2b69c2ef5b56 | #177 / a10a17437baca5e16254b87b3a303763ded68cf5 | dd60b3ff8b51a57a1136bf6f480475091eac0523396901149ec425d37c4f6d86 |
| codex/df-178-test-database-lifetimes | 242540317632d77916fe24c774b9ab232ca0c8b2 | #179 / 2069af8e7c681d00643ed8ce85c31f3f8b505c17 | 6c3966031279c31991f25df17ee5f49c48ff52751575cb893bb7bc3d7256eaf6 |
| codex/df-180-theme-storage | 5094e9411ad6c75cd412e3ee779d87639847852f | #182 / 7f8412bf8f6534819d972d3a3d03a9011d995b3b | 05e7cd8538ce8bf49866869599128685b9da3009cc69717431fdc7e1ec0bbb75 |
| codex/df-181-api-integration | 52332390a899ddc0787821f97d647653eed0c46c | #189 / 172988ecf6dcd749cd0bce074d4e505f5b7e61ab | 76be98bfae965e29f02b2c78e36f61a54a5faf3b541947d09a65f8e99ebfaad3 |
| codex/df-181-contracts | 4aae8c4ec688557e705a425bd1bd64067112b2df | #183 / 2ca1a42b4dcb038d2d4256253e58f433c0c0988f | cfe2cf3dbdefa760d98c0b2bdf90567d8e5a3320dbc711e50653676ffec325d0 |
| codex/df-181-one-time-wire | 80abe05b06f17500b2464f21543e70ac9ecd34b4 | #187 / 7099db13b967b95fa4306afe986a86bd160afa4d | a6f74abbc266ec05fe59a1cfc090ded0037914c36d67a63799d15b8608def10c |
| codex/df-181-theme-config | eb0131f28d610d023099fe0f070de9a3edf03773 | #184 / 0b6a3ebd9bcca13d511dfdbb2e7cd1060aa207cc | 2f9c6a819476b60cf9b08f889dc7d02c08d54491b62bba707879447e49bf53f1 |
| codex/df-185-sqlite-snapshot | 2162d04c1dc0d4b8e3963a3e2d7ac86cf35a40b0 | #186 / 57469cf47d7994ac119bcea0dd62cda61aa084cf | fd28e5b5d4f4677a723ca2a3c21f70d8392ebc42d5e5d56a33b9dd97b1f0624d |
| codex/df-188-one-time-recovery | 82268377b41bcb97b37f895272996d46b9560beb | #192 / 96fd665666b6b5af807574cfa3dfac764c1e21d8 | c6f8562574c1b3ddef7db35cc0409ddef75423848b5c98dce0c9f2ab83035478 |
| codex/df-188-one-time-storage | d462136e38a67ccdd0f2f308f9adda1f17ad6514 | #190 / 9a8f89f83fb25b9d7e9bc8c8b97c420888636b72 | 327e33a2b1dd6c61eb343ac9ec37adae6f11341c2e310af06d3832594dbe6609 |
| codex/df-191-account-asset-storage | 13a5be285bd731b1fe649d1fe5dcd1df9824a4dd | #193 / ac0bbae9d681c75cca4b02d7ce3ce52afa1f74e1 | 2ad113755584cdf3cb43b82cb5a3a9e728ec253b4782796659ca38e990344036 |
| codex/df-191-asset-declarations | b64b49290a5b2b5085166a9c69616e415c4f1ace | #194 / ac60554ffc519e5b0fd6dcbe6f316bade0f787f4 | 61b7ae47a3492c904955ed241c314a6572585b0d917cb18b4f036d5dc376ba8a |
| codex/df-191-object-appearance | 9292f14e7174dee28f5d374f5b472b8da5b883e8 | #195 / c46c40c9b53b4225e3e1d4343f8dc0c51372480c | 6251ab4c4d68405baee32ea2cef65b5d9d1d572a348c97bcaf3c25c03b3d350d |
| codex/df-191-structural-sync | 2108b3fe24c821c08062bc2aa6adc7891a22abd0 | #197 / f6b2362d7504eb96c6ceeae9224ebb3c115dc1fc | 03ee476b6ed4e3d75222a0848c4295c28cde82c6c0cd731527d65c967c4ca254 |
| codex/df-196-bounded-input | c8753865643aec2f5f255b027a226ce7192e4593 | #200 / 1b133bc8c3c64f50bedf7163379a6b7158ff673a | 38cc1d016a589b244551e62b95051985722f2b86a78aaef5a2717a8c49f9c91c |
| codex/df-196-image-validation | ad3b28b40f94d0d1ebd942522cfa720e74ac6539 | #198 / ba65cb404177613589a8ad3f1ded59523052e3a1 | e332964cda2934127bfc7d88b69e61208d8d0bf8605c13bdacebffb5f383884b |
| codex/df-196-png-validation | 63a26d20a792024b08cc56e423e8ae00f016461e | #199 / 80ea998c13a66bb54d2abd4734402805434cffae | 9e3ef2e985b1e967e4df7cad1db0f4aab449d1bf3c9bda3a18a560f9941960fa |
| codex/df-196-svg-rendering | 79d22158389179c76d92d80da6fcb478e0f08309 | #201 / c29bd1a5fc27037affeb86e3dfafe3806e051b33 | 23233acdde09f710cc3361e07fd7e7e0ac43c53e2a799488adcd851d7c6e5f26 |
| codex/df-202-asset-backup-closure | a86ad7b993dd53a05177c8aa0d3e1342ffc262aa | #208 / a2867d034abe7f6bf86857f76995fb264aaf863e | 3247a2278ac86ff11f8a3045d384d6d5f55953f10f67e335b06e746556be7938 |
| codex/df-202-asset-backups | c1bc29599a27bedc701321eb7cb07112d5c8172f | #207 / 7369fe33d9659dd66584c0b5c80c82fdb8d3c294 | 7c8c07c2463dba7175258d2b9fd470b0b671f8bde1699127cda48c864135e8de |
| codex/df-202-asset-recovery | e527b3e23075f46f11fe2b37aec1d86bdefb9df0 | #206 / a1f68e7752497540ddf8cb19a40095daf4981d2f | 7a83c7d45b385cf7e872293c124f7d15acdb931ab49b13f7a4d1086fd4305010 |
| codex/df-202-asset-storage | ab439e7834f31ce7847308b6eadd11b41d6192fc | #203 / 17214c751c288fcb2f948974c77881dcabc85434 | 81a3e2afc76aca7273470837ae2171e8d8a90e7e744c139a7bc8477b72fe1240 |
| codex/df-202-asset-transactions | 91ac89d96cace0c5187afbcb2d744ea4370531e2 | #205 / e2ba94dd87ff986939c2b8cc529cae6d7bd63b0e | 3cc38530e7475d6c455606f962d5801af5a661bc1a34222d361c3195cae07187 |
| codex/df-202-asset-worker | a218766ed3663db432f7cfaaa7afa1f20e46a7fe | #204 / 194f009f4a9f30f86b4281db76857f5be3aff102 | a47e196417a9eba7117ba4876072ecee5c2958752cc7d2579ee5fbcb2cdbefa7 |
| codex/df-202-logical-asset-closure | a21b65643e0bcbac782c78d0e2fd7692d7411bf2 | #210 / 162cde934c768370a2d09ed0e6ae448bf9bb8d06 | 4b28a9fa5480d03b10947135f11b6de1f3c01275174786ab1a10f25a08d1c6f1 |
| codex/df-202-maintenance-entrypoints | a3067046e8ebd668af5be066368b95835a9d416f | #209 / b61d1780e81662b92da703125d7a4090e5bff2b1 | a22a5b716bc882903f6575e6aa7c87949177472e72da4e6220888a0c240ddca1 |
| codex/df-211-authoritative-results | 1be10f6f83ef160ab364ecf10f2aaef1458fc26e | #218 / dfecd4282473499f169fc5fab912dfc869292c57 | 6ee1123e2f82650cffcb6a9632b609d3c522f81223da5f2c85eb69b0d7e1ac5d |
| codex/df-211-common-facts | d18d1b76e77a0c57bf4d46ae16d24f76ddf32c96 | #227 / b831fb168439001920776adfd327b3dd5ee539a3 | ffee78a1a62e474c385fe198b0e5e3fc85a2b18a63f72247ab69ad16aee2d5f9 |
| codex/df-211-common-restore | 91a94108d9ca55f2be078ade1f2ba727f845cd7d | #228 / c8f33bd4b370315a9f9b6f4e463107a7eb076c5d | 2930bd0605e5fd8f8b0462630630375aee104dcbe7e124b56125bbbb8951dd7d |
| codex/df-211-completion-prompts | 0114a3d1f3289b715d5244fb38498f295eb4a1d2 | #217 / 61d6a68514b38f28e85e9a96b8d6b4fd15c05c94 | 0d1ee03de3f34cb123a3ab0de2c5fb29200d688d27d8226f54873307bb28ae9d |
| codex/df-211-history-restore | 6b8af533156ffe9b3717d5e9333cb7e9e3c75783 | #222 / f6a319a1e1d57bde51c7474739703c7104c6cd40 | 49c2a47aa64528c8b9e3ea2647b2d732789e82cca1923ae5a71a9b205d9272eb |
| codex/df-211-local-intent-session | 30419fb7e52169c6669043101f0d103c66825680 | #213 / 4a314e01edd8873688610b39c1bb86f5986f6b70 | 36bf2fdf8fc181abdf896962aee7ea1c514f3690a459e0e99c88ce653efead78 |
| codex/df-211-next-structure | 338c523e6e7e0fb5be96822aebd8c83b386501b5 | #223 / 6a088e6b62c1402e3b6924ab96ab6ead36b1b9dc | 5f3fd45b74e1e2783b2fd19e580d783c9617075ce3d6cb282da7254d1576f088 |
| codex/df-211-one-time-local-store | 07d371dc69682a93405b61468488bcdde37f1e72 | #216 / e1402bc1c68273c9a011dbacbc6cdc5ea6180478 | 39d9089888f95f349945946eca0c89490ae3ba0ad1ef8b733d6d6cc319815256 |
| codex/df-211-one-time-room | dc8f0d25eb68d28dc73bbb9f4332c5d48e44a416 | #212 / eea8112df44b6da3b76e657ab39fefaf799ca076 | b01094a79365cbcd746519468d376a3e2c2fe1f6f43765536a76206044ddddde |
| codex/df-211-structure-mapping | 16b92bbb3407fbedd5ca9dcbac966d57d3a31fa7 | #224 / 4e707da708ebd3bc8836347fca23fc2d96496eeb | d709370b5f15ef77e4f01baba76c9e1fbe6da47e9c8875e1582d606c5346f39c |
| codex/df-211-structure-restore | 38db10c642b47cb86045c7ed80647dd7985432bb | #225 / a779fa869a15643edb4604802c3560bff64fdc31 | 1fedd0ae747d7a8efc74a1a8e4318d657431a783d72c933ca24dc61db53dc412 |
| codex/df-211-timer-precision | ac6b1ad911ed4912f3c9c412d47143604dcb1568 | #226 / 474f4e2cd1d2cb76d5ee9cdcde653e8640476135 | ab99ff7c8a1de4d264a19412d2046ef77e29e8f351f681aac70088550cd207b4 |
| codex/df-211-transmission-binding | 2ffad7162019052dd28f5e155271b0b4009aa943 | #221 / beb6e5e865a2f6557adf48ca5108cfc08c861510 | b94a56027c6d72cd98d98ce2ef42c731ceaae1024c9d0de46f3951659c3b6044 |
| codex/df-214-svg-scanner-cost | 3f23de3c439897ad8fd01008c7fbf926ba1372fd | #215 / ffd3cb866a2ca0d161b619520c435ce0a6f84c7a | 5dc905ae9d646bec245a159125eafe0981c34444fe6ea8800d4bc502e033b293 |
| codex/df-219-network-identities | 2c2c5d588acfb3f49e55c622726e72b63665581c | #220 / d6740a72fe263a52443734a6d3d2104fb0868166 | b57bb04c7d52619838c90c591191f8e38af97ae9e4053dd77421648a3d16d814 |
| codex/df-229-icon-pack-input | b215de822dc5523d3e67d6533b24f0fc9a5f00ae | #230 / da203fa22beff94651b3403fc1e75ce6b9278fa7 | d3359464edc154026c0cef5143b4ff3f9c8a176751e95ccc7bca13a26a036782 |
| codex/df-231-local-blob-files | 46fa87be707cf240dc0e25f6df903a9c282d9cd2 | #232 / 1726373c8a76bf321322dd951af70f317328c8d5 | 918e9397236d13ae9d94cac8267403a577e169b1374b88f60f4e2ed7a5675805 |
| codex/df-233-shared-theme-output | 8234e3aae4985a99508280f9bca209469d06ebbc | #234 / 44f79cc20b09408e0375dfe8d8d5b31614f3d611 | 0a40a6b61f6f8cfa749d07e22085f642ff200600fc3e16da939f3afe7778d627 |
| codex/df-235-shared-icon-render | 30b76f9e7978dc9f243bc51c6e8fa577b5141951 | #236 / de1783ca360e8dbcd5c45858fab1cd7f088a1af7 | 7984a5f99d872bd0b000594bd9f5c93393d180309d0f1895bce0b4591d0da558 |
| codex/df-237-theme-files | e344667d0c7edd66804362bee0454fbdf2215560 | #238 / f2f774a518d51fbca5b3e73c2a86cc16067d1f90 | 936ccaf0c79ab8b65339257a43abd94cbf567023d6878e4d67378e8a2babf988 |
| codex/df-239-device-theme-selection | 35990f6eb53c869e6e15b5c6bea9cb8d9f11e16e | #240 / d08098136a2ff52af31ec47561e84c51c60b1654 | 7ccbd28f55f4c1890e99cebb7f23bed1fa2f13459919a82fc76fd0dcd4b9853e |
| codex/df-241-builtin-themes | 4d8c5d3eed70c08bc9abd8ef38db85d2c9a4c45e | #242 / e60e6240fc151d3dc67ca6f66f600513ed47dd62 | 058ebd6f84994e6e34b0cda2eb4222d57972b226f9dfbc6c8a0abe09b6d7bc64 |
| codex/df-243-theme-management | 8846e71f078d94e2e508dacf39a30fcd01e0765c | #246 / 6e6ab7de2a2a5f91b68ab15175924d4da51877c6 | e3b6d325e1cf914e1e228985d533af4c2eb8cb82f7557a3d9f0189017a703d8c |
| codex/df-244-android-test-gate | 9ed78fa15ed26445181d836738827fedaca75828 | #245 / 8b1b1def1a77da7af0984f2c904c944c06e8ac49 | 9fb3fb476b4da2934a24c8a4dfdda333e0466b2133178993cf8e9b4090c80495 |
| codex/df-247-config-archive | 45e21c2bf11aaef3434737e9cf8ef333f2c1052d | #248 / 5dfd3164320b42a7fb254f5b517138930e619e0b | be619013879ba37f81acd09506ae71c3eb91f4b30654f44c12ed873702f3f7cb |
| codex/df-249-chart-appearance | 741f333f9b398c45d6f51e846f32f15a996d80f7 | #250 / 46dad85966cde3ab34774e518ada7cb7104c8d19 | 34f9280a4553c0036786cc03495d5bcb3ba3795790ed52729d526dd98da5d300 |
| codex/df-251-status-appearance | fa76203f55886f5ca10ba1d9580c9c186e8c19d8 | #252 / ed8304e257bb663a3c1b4fbd2323748e3fc11734 | 1d09d236fb3fb7be20fe18d03c9d9b3ecc346301ab164ec99d6305560c2a4628 |
| codex/df-253-theme-editor | 09f514d172a9dc5b7415d02d687f2d79838f1a5d | #254 / 9e681c97de9628dc51d61c51e80bb37b1b5ccfd1 | 6f9576bc53641f9d53d1827fa7588d11a7c6166cd364473ede18bb65e1e804c8 |
| codex/df-255-theme-identity | b7b666b3d42d8d0368881cec02c174074345dafa | #256 / 188991e0796eedcb00beccad7e68fc54cddf0c3a | e2920ee896c5cd1c4fdf17b0961ba0a7669131475345b7e55a16abaada39d893 |
| codex/df-257-recovery-checkpoint | 5c2d6bc1bde7a9e0148102ab6d130201a6bf8f03 | #258 / f06513eb2d2dd8195be35022106723fc12b4c15c | 38e87ef2db7eab23ec5d838296c0bb01af0050911717fbd4f9fcfb7ae118a407 |
| codex/df-259-account-icon-store | 5a020cbc90cf62bc96a7eeefb5c94ff5eae1e5e4 | #262 / 549e989e0c309108e62533081e0c90511ff9510b | 90c687e575bc70a21b52729e77599622194f7cf3fedcfa0a1e6e023d345606ab |
| codex/df-259-icon-install-recovery | cc6f3bfc17e5c749b67768687d183965d48d3390 | #266 / 0396c6c67ac85938c1a96d04455d38bf57fc1b7f | 3a388da2e50ec5fdb3d4c7f1a42d100968dd7e5e4252cc11f1e4fb7c333e8e4f |
| codex/df-259-icon-lifecycle | 8a85be3168b7e39dbfb11622c33183f2187ee68a | #268 / 1933c5dfb7117ddb7a921515a6c088e60155ba34 | c15809fab14f9068c73a510bc685854133431efdedd9dc946e94254b147a8699 |
| codex/df-259-icon-render-cache | eb2ec16013fc26d0d4aa748e68aa44041445b5c4 | #267 / c3a8cba5f4e9cb7e7ead7fb5e49eb6c9afdfdbd5 | da6fa46d3ab1f6f15934f156f5e460900e38518125734b13ccbe05499c28e07e |
| codex/df-263-jwt-security | ce50b260a32be8815d8c6632f840cec9a44200d0 | #264 / 670bfe8254728c1d203ecad3067d6190f8498a9d | 98e6c4bf3edaeb0cb6be132f6bd804a6fdec776ade4925f3d5118f73f926b81d |
| codex/df-269-active-icon-pack | 624cba41847dfbe44f90d9c8c5a6e24366917c79 | #270 / f56549e3455f6b56f907520816c71259a7db8ee8 | 2c3e8a4c66a3f08c8130f9de2d992aed9c15ea12bf42e1e13e55e098484614f0 |
| codex/df-314-structural-causality | 2310d7f8b7dd157eec02a6617498f8483efad1be | #322 / 2fcb214f0ca16c156c9524fc752165ebd6553120 | ae3bfe81227008acb26832b05d4caac14da579d25bd8222579ab6ffc3e454714 |
| codex/df-319-scoped-native-checks | 1405d98bb57e67e106110c1a507675fa97cd4843 | #320 / 7b9c4ad0fe988542a5776115e1bea82da2e9daea | b98f21a53561d9f929d742bb6f04d2f7f284058a3b20e7a016986cb60768d6ae |
| codex/df-321-ordinary-result-closure | d96303a6957bc729cfd36c5076206c62bbb5b4ed | #323 / 32dd2cee97d246622114e64c222f476b082ba6a7 | f10836bb41383c8d067c648c68ea46576422b0fa8a5e698176ff759fcf797fec |
| codex/df-324-timer-result-integration | 5a3195b3fb09556512616df5a5b0b4118307c840 | #325 / aa5255e646982a88fab6651ff452d4b6b9839b93 | 1fbf198d6fde69d23970e22c017851362edbdbcac3914da901ea8704ae1ebbfb |
| codex/df-326-once-http-integration | 6786396696d68c8eabc40b6a28adb5bf7165f47d | #327 / 7ca33eeaab623b44460a97cca65f6fc552ec765d | 7e7028721c6dd4902f7ef2a8adba8652ae3d8b9ad26a6acbf61d13070a5160b1 |
| codex/df-328-unified-sync-runtime | 5d0d0e32d5ac50e99344a19630fa5caa265cfef0 | #330 / 4341408d0a2ce5664d8f380fb4d2a42690e29611 | 92789b346e8c361f56c425a994377371307eeb32b4b3496b0d9556472e5a184b |

## Worktree 清单

全部25个checkout已盘点；唯一dirty批次保存后均无tracked或非ignored未提交文件。
旧worktree含构建/测试报告与缓存，必须归档证据后移除；不能据clean就无视ignored内容。
当前主目录的test.db和当前开发目录的报告/环境不操作。其他会话仅在主目录，保留主checkout。

| 目录名（新仓库或其worktrees根下） | head | 引用 | 处理条件 |
|---|---|---|---|
| DayForge-next | 6a54d25a66e42dbf1fc025c8acc4b468b38be9b4 | main | 保留；主 checkout，测试数据不操作 |
| df-161-ui-regressions | 242540317632d77916fe24c774b9ab232ca0c8b2 | codex/df-178-test-database-lifetimes | 已合并逐字节证明；清洁检查/证据归档后可清理 |
| df-166-archive-recovery | 0d57f7765bc18ffaeac391cabd813d1ab09903d3 | detached | #168 已合并；独立核验及档案后可清理 |
| df-180-theme-storage | 5094e9411ad6c75cd412e3ee779d87639847852f | codex/df-180-theme-storage | 已合并逐字节证明；清洁检查/证据归档后可清理 |
| df-181-api-integration | 52332390a899ddc0787821f97d647653eed0c46c | codex/df-181-api-integration | 已合并逐字节证明；清洁检查/证据归档后可清理 |
| df-181-contracts | 4aae8c4ec688557e705a425bd1bd64067112b2df | codex/df-181-contracts | 已合并逐字节证明；清洁检查/证据归档后可清理 |
| df-181-one-time-wire | fa76203f55886f5ca10ba1d9580c9c186e8c19d8 | codex/df-251-status-appearance | 已合并逐字节证明；清洁检查/证据归档后可清理 |
| df-181-theme-config | eb0131f28d610d023099fe0f070de9a3edf03773 | codex/df-181-theme-config | 已合并逐字节证明；清洁检查/证据归档后可清理 |
| df-185-sqlite-snapshot | 2162d04c1dc0d4b8e3963a3e2d7ac86cf35a40b0 | codex/df-185-sqlite-snapshot | 已合并逐字节证明；清洁检查/证据归档后可清理 |
| df-188-one-time-recovery | 82268377b41bcb97b37f895272996d46b9560beb | codex/df-188-one-time-recovery | 已合并逐字节证明；清洁检查/证据归档后可清理 |
| df-188-one-time-storage | d462136e38a67ccdd0f2f308f9adda1f17ad6514 | codex/df-188-one-time-storage | 已合并逐字节证明；清洁检查/证据归档后可清理 |
| df-191-account-asset-storage | 13a5be285bd731b1fe649d1fe5dcd1df9824a4dd | codex/df-191-account-asset-storage | 已合并逐字节证明；清洁检查/证据归档后可清理 |
| df-191-asset-declarations | b64b49290a5b2b5085166a9c69616e415c4f1ace | codex/df-191-asset-declarations | 已合并逐字节证明；清洁检查/证据归档后可清理 |
| df-191-object-appearance | 9292f14e7174dee28f5d374f5b472b8da5b883e8 | codex/df-191-object-appearance | 已合并逐字节证明；清洁检查/证据归档后可清理 |
| df-191-structural-sync | 2108b3fe24c821c08062bc2aa6adc7891a22abd0 | codex/df-191-structural-sync | 已合并逐字节证明；清洁检查/证据归档后可清理 |
| df-196-bounded-input | c8753865643aec2f5f255b027a226ce7192e4593 | codex/df-196-bounded-input | 已合并逐字节证明；清洁检查/证据归档后可清理 |
| df-196-image-validation | ad3b28b40f94d0d1ebd942522cfa720e74ac6539 | codex/df-196-image-validation | 已合并逐字节证明；清洁检查/证据归档后可清理 |
| df-196-png-validation | 63a26d20a792024b08cc56e423e8ae00f016461e | codex/df-196-png-validation | 已合并逐字节证明；清洁检查/证据归档后可清理 |
| df-196-svg-rendering | 79d22158389179c76d92d80da6fcb478e0f08309 | codex/df-196-svg-rendering | 已合并逐字节证明；清洁检查/证据归档后可清理 |
| df-202-asset-backups | 4341408d0a2ce5664d8f380fb4d2a42690e29611 | codex/df-160-integration-audit | 保留；审计与后续开发 checkout |
| df-202-asset-recovery | 2c2c5d588acfb3f49e55c622726e72b63665581c | codex/df-219-network-identities | 已合并逐字节证明；清洁检查/证据归档后可清理 |
| df-202-asset-storage | ab439e7834f31ce7847308b6eadd11b41d6192fc | codex/df-202-asset-storage | 已合并逐字节证明；清洁检查/证据归档后可清理 |
| df-202-asset-transactions | 91ac89d96cace0c5187afbcb2d744ea4370531e2 | codex/df-202-asset-transactions | 已合并逐字节证明；清洁检查/证据归档后可清理 |
| df-202-asset-worker | a218766ed3663db432f7cfaaa7afa1f20e46a7fe | codex/df-202-asset-worker | 已合并逐字节证明；清洁检查/证据归档后可清理 |
| df-301-backend-ci-budget | 1405d98bb57e67e106110c1a507675fa97cd4843 | codex/df-319-scoped-native-checks | 已合并逐字节证明；清洁检查/证据归档后可清理 |

## 执行回执

恢复开发进度（当前分支，尚未合并）：

- 隔离候选和后端只读运输、原始内容/差异读取、旧 Room 结构校验已提交保存；具体范围
  与失败/最终结果见 TESTING 对应章节，不把不同源码上的结果拼接成全量绿色。
- 已增加完整保留请求账本校验，包含空队列下真实 ACK、未决工作、结构替代和完整
  封存审计；与实际结构/计时/计数/指标/事项同步运行时两个完整类最终21/21通过。
  同步发送器、既有业务、UI、schema、默认 v4 均未改变，不宣称允许活动切换。
- 本批补齐全部历史配置日志与原请求/真实接受的绑定，保留准备分配而不补造原请求，
  拒绝遗漏/重复来源、历史损坏及重哈希后错误阶段；合法零操作替换沿用原服务终态。
  新完整类最终13/13通过，失败/诊断及最终范围见TESTING；读取不修改数据、安装素材、
  应用主题或结束日志，仍不是完整恢复或主分支交付。
- 已补全部本地提交回执及指标提示历史，逐字核验原请求与真实提交正文，保留未完成
  草稿输入及已删除父实体的冻结历史；saved 不冒充远端 ACK，pending 仍阻止切换。
  修复只读请求证明对合法预填指标 payload 的误拒绝，任意无真实回执来源仍拒绝。
  新核验/完整请求账本/实际捕获依赖三个完整类最终45/45通过，失败及单独已通过的原
  提示类记录见 TESTING；未重复无关纯图容量类。全业务投影/事实核验尚未完成。
- 已串联六类全部当前投影与原历史读取，保留非活跃关联、旧轮次及旧 tracking mode，
  拒绝原始值损坏，显式列出同身份未确认内容及无法证明的缺行。完整撤销/父墓碑不误判
  为缺行，保留原 authority/字节；恢复专用计时输出不按当前目标给出完成状态。
  四完整类60/60通过后，仅补新恢复读取及图覆盖，新完整类18/18、最终20/20分别通过；
  没有重复相邻正常读取45项或极端容量。各次范围和未覆盖联合边界详见 TESTING。
  该分支基础尚未接入素材/档案/切换，不能称完整恢复或主分支交付。
- 已增加旧范围完整图标文件只读归档输入，包含未选包/未引用素材和原日志的部分临时
  写入，不调用会删除临时文件的安装恢复；不把 pending 字节、旧 ready 或文件证明当
  新范围授权。新读取/底层文件/原安装恢复/原元数据四完整类81/81通过；验证范围见
  TESTING。图标原 SQL/传输/目录及持久归档/回读仍未完成。
- 已增加设备主题只读输入，白名单保留原选择/目录，读取全部已装版本并保留中断安装/
  删除日志及部分字节；核验实际 APK 内置内容，不应用主题、回退或修复目录。新读取与
  原主题文件/选择/目录四完整类64/64通过；不重跑未变图标81项或拼称联合全量。
  原图标库结构/范围行/确认日志及业务授权闭包仍须补齐，再进行私有归档及回读。
- 已补实际图标库结构/原始类型、全部八张表范围行、历史传输规范化确认和完整目录日志。
  未完成状态保持原样，旧下载验证不当作服务端 ACK；未知结构、原值或确认损坏拒绝，
  不复制整库或导出其他范围的行。实际 TCP 处理器确认后与文件证据联合，不新增网络。
  首轮相邻59项全通过，新夹具有1项失败；修正后新14项通过，补源库关闭门禁后最终
  新完整类15/15通过，各次范围及失败路径见 TESTING，不拼成全量绿色。
  未接入持久档案、正式切换或主分支；下一批将完整业务、图标库/文件及设备主题绑定
  同一原授权及内容快照，再完成私有档案落盘/回读，保留阶段/全写入栅栏联合门槛。
- 当前实际 GitHub 仍为59个开放PR、2个worktree，main为af3aa8d（功能仍为#330）。
  本批没有新增零散PR；当前恢复分支通过提交保存内部步骤，不强合依赖未闭合的部分链。
- 已绑定实际完整业务/图标库/文件/设备主题，包含保留权威及原请求的历史引用、配置
  分配和全部未引用自有素材；缺少素材/未确认事实/未决工作仍为明确审查项。私有候选
  有界流式保存并完整回读，核验来源前后不变，取消、覆盖、链接、损坏和刷盘失败不动
  源数据或授权切换。新完整类最终19/19通过，首轮五类79项中78通过、1项旧测试DAO
  在冷重开后的生命周期失败，详细回执见TESTING；相邻61项正文未变，不重复或拼成
  最终80项全量绿色。无新零散PR，无主分支功能交付或默认v5激活。
- 下一批继续补齐私有档案的持久阶段绑定与统一确认，
  阶段日志/所有写入发布栅栏与受控向前恢复，正式确认入口及真实恢复后端联合。
  完整候选冻结后按依赖审查、必要组合验证和精确提交CI合并，之后才清理无用引用；
  最终全量及人工验收仍是独立门槛，需要人工操作时先停止。
- 已补持久SQL栅栏参与者和Room17→18空能力迁移：实际全部业务/请求/outbox写入、
  控制行/清库防绕过及冷重开、精确取消与损坏状态阻断覆盖；普通路径不自动冻结。
  25完整类157项中156通过、1项约束篡改夹具连带删除触发器而失去文件字节不变。
  保留原触发器后仅重验受影响完整类8/8，其余24类149项不重复或拼成最终157全绿；
  详细范围/失败/回执见TESTING。正式阶段日志/所有门禁/确认/COMMIT仍需继续。
  远端main再次核实为`af3aa8d`，仍59开放PR、2个worktree；本批没有新增零散PR、
  强合未闭合依赖、操作正式数据、安装正式APK或激活默认v5。
- 本批已直接验证真实TCP事项ACK进入完整新业务证明及档案，以及实际bootstrap的
  旧服务端计数缺少原始规则时仍为审查项；不借今天的目标值解释历史。正式FastAPI
  恢复、阶段中断/向前恢复、活动消费者及人工验收仍须继续，不据此宣称完整联合通过。
- 配置发送/接受的重复验证耗时保留为性能风险：诊断的27次真实操作累计耗尽150秒，
  不是新配置日志读取卡死。新核验使用覆盖全部替换阶段的最小完整图，未改原服务断言
  或时限；不以反复全量/极端容量验证阻塞恢复主线。
- 已补提交前持久阶段CAS、真实SQL匹配和精确取消；坏指针/缺少日志不会解除普通门禁。
  同步、素材文件/选择/发布、偏好、主题及widget共同受指针约束，原授权的纯归档和固定
  新epoch只读预览仍可核验；冻结冷启动只读原主题，不修复目录或制造默认选择。
  首轮17完整类319项313通过、6项严格认证mock配置失败；修正及必要边界合批后，
  仅重验认证/阶段/设备主题三个完整类31/31。其他15类303项未变，不重复或拼成全量绿；
  真实持久化取消、跨存储冷重开和冻结下完整归档通过，详细失败/回执见TESTING。
  这仍是内部参与者：未接入生产开始、ARCHIVE_VERIFIED、COMMIT、向前恢复或正式入口。
  下一批继续完整排空/源与候选重验、私有档案阶段绑定，再接显式确认及活动副本切换。
  无新零散PR、主分支功能交付、默认v5启用、正式数据/正式APK/部署或本机Docker验证。
- 完整提交前工作流已串联共享同步协调、真实新候选和原完整差异复核、持久指针/SQL
  冻结、实际文件租约排空与完整输入后验、档案刷盘/独立回读和ARCHIVE_VERIFIED回执。
  原控制行只在源比较中严格规范排除，原文仍入档；响应生成时间及能力/记录运输顺序
  不被误判为业务改变，真实内容/权限/游标改变仍拒绝。冷续行重读实际源、候选和档案，
  部分/无引用档案不自动采用或删除；返回确认前证据不等于用户同意切换。
  首次新夹具备注类型编译失败，未跑行为测试；修正后六个完整类88/88通过，详细回执
  见TESTING。档案26项含六个完整工作流联合用例，Room/DataStore三断点冷重开、真实
  刷盘失败/重试、档案损坏及同账户重新认证取消均覆盖，没有重复未受影响的上一批矩阵。
  实际main仍`af3aa8d`、59开放PR、2个worktree，无新零散PR或强合未闭合依赖。
  下一批为受控COMMIT及提交后向前恢复、正式消费者屏障/确认入口与真实FastAPI联合；
  这些门槛完成前不接生产开始，不将分支实现计入主分支交付或启用默认v5。

- 已移除23个历史worktree（普通 `git worktree remove`，不使用force）；保留主checkout及当前开发checkout，共2个。
- 已清理68条已证明合并的本地分支。本地由129条降为61条（含审计分支），未合并57个业务head、
  recovery-capacity别名、recovery-snapshot WIP、main均保留；所有开放PR的base/head未改变。
- 删除前重新核验工作树、当前SHA、GitHub开放依赖及恢复bundle。squash不满足直接祖先检查，
  因而使用带预期旧SHA的 `git update-ref --stdin` 单次事务清理已验证引用，未使用强制分支删除、
  rebase、reset或force-push。只删引用，不删未合并成果。
- 远端只执行fetch/prune移除已不存在的跟踪引用，保留全部60个实际远端分支（不含origin/HEAD）。
- 私有恢复档案：新仓库Git目录下 `integration-audit-2026-10-12/`，约676MiB，未加入公开Git。
  `merged-history.bundle`完整保存68条原引用；`detached-history.bundle`保存#168旧detached HEAD。
  两份bundle验证通过；22份证据tar读取校验通过，保存测试报告/构建outputs/覆盖率与pytest结果。
  构建缓存、虚拟环境等可重建产物随旧worktree移除，主目录测试数据库和当前开发报告未动。
- bundle SHA256分别为 `aecacebcbf55bf280a5399328b76667fb43b5392da986f15bd090753c863ed66`
  和 `fe76d91c6bd14bd8cf709223d739f5dc4b4810b76f5ecbdb3c50556f233ae8c8`。
  恢复分支可先list-heads，再从bundle fetch原引用到一个新的恢复分支；证据tar先在新目录解包，
  不覆盖当前checkout或使用数据库恢复命令。
- 业务链仍保持原PR/base/head，不因本报告发布改变交付状态；不宣称全业务源码审查完成。
- 本轮文档与清理采用root/diff/引用/归档核验，root51项通过；未运行业务全量、极端容量或Docker。
- 首次暂存检查拒绝报告末尾新增空白行，root在该检查处提前停止；修正后完整root51项通过，
  不把提前停止算测试通过。后续仅补充本执行事实，最终diff检查重新通过。
- 文档PR与本地main fast-forward回执在GitHub任务最新评论中记录，不把文档合并计入功能交付。

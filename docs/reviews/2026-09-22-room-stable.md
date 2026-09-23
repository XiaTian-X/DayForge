# Room 稳定版升级（Issue #173）

## 范围与决策

在 #155 / PR #175 的新工具链基线上独立升级 Room 运行库与处理器，版本由
`android/gradle/libs.versions.toml` 统一维护。不修改数据库版本、schema、数据身份、迁移、
账户/时间/计时/同步协议或 UI；保留 Java 生成和生产 provider 的系统 SQLite / openHelper 路径。
不引入 Room Gradle 插件、SQLiteDriver、bundled SQLite 或应用级 serialization 强制覆盖。

通过 android-cli 检索及 [Room 官方发布说明](https://developer.android.com/jetpack/androidx/releases/room)
核对当前候选：2.8 系列提高最低 Android API 至 23，项目既有 minSdk 26 满足要求；
Kotlin / KSP2 已由 #155 独立升级。较新系列包含 Flow 最新值通知竞态修复；本批稳定修正版
明确规定数据库关闭后挂起查询及 invalidation 操作抛出 `IllegalStateException`。
可选 SQLiteDriver 包装器/连接池优化并不代表当前 openHelper 路径已启用新驱动。

生产数据库为进程级实例，当前应用源码没有关闭 Room 的业务路径。本批仍须验证测试夹具和
订阅生命周期，不通过吞掉关闭异常、自动重开旧实例或更改业务预期来取得通过。

## 保护与执行计划

1. 以 main 建立独立 worktree，保留主工作目录中 Gemini 的 UI 分支及修改。
2. 仅调整 Room 版本，先构建以验证此前处理器 serialization/Hilt 阻塞已解除。
3. 保留既有当前 schema / 全表快照 / 回滚 / outbox / Flow 兼容测试，新增两项真实数据库测试：
   - 数据库关闭后挂起读写在有限时间内明确失败，旧实例不重开；生产入口重新打开后全表快照不变。
   - 连续事务更新允许 Flow 合并中间通知，但必须到达最后提交值；取消订阅、关闭、重开三轮后，
     持久化名称和 outbox 写入数量仍准确。
4. 新增及既有测试全部在隔离 testbed 真机执行；随后干净重编译并跑完整套件、lint、零告警、覆盖率校准。
5. 核对 schema / identity 不变，审查依赖与生成代码差异，CI 通过后以独立 PR 合并。

## 本批结果

- 候选 `./tools/verify android-build` 通过：Room/KSP2 与 Hilt 生成不再出现原兼容阻塞；
  lint 无新增问题，编译告警 0 / 允许 0，不需要序列化依赖覆盖或生成方式变更。
- `RoomUpgradeCompatibilityTest` 的 4 项在 Android 15 / API 35 物理 MI 6 通过，
  0 失败/错误/跳过；包含既有两项与新增两项，结果由非空报告门禁检查。
- 9 月 23 日恢复工作时，上次运行只留下 194 项的局部 XML，且没有完整覆盖率报告，
  不将它计为完整通过；局部证据已保存，重新执行干净构建与完整套件。
  不能沿用 #155 的测试结果作为本次 Room 升级证明，也不能只凭局部 XML 的零失败判断执行完整。
- 本次首次干净重跑在 D8 合并 dex 时发生 `OutOfMemoryError: Java heap space`，尚未执行真机测试。
  保留失败日志，仅以命令环境 `GRADLE_OPTS=-Dorg.gradle.workers.max=2` 降低本机构建并发重试；
  不更改仓库堆大小、测试超时、告警门禁或业务实现。

### 绿色结果之外发现的夹具问题

首次完整重跑的 733 项断言通过，但逐项日志审查发现一次 `WidgetRefresher` 查询已关闭连接池。
异常关闭栈指向 `SettingsViewModelTest.teardown`：配色持久性用例连续改变设置 100 次，
真实 WorkManager 小组件请求超出了单用例数据库生命周期。这不是正式应用主动关闭数据库的证据，
也不能仅凭测试绿色忽略跨用例工作。

仅在该 ViewModel 套件的 `WidgetRefreshScheduler` 调度边界使用替身，保留真实 Room/DataStore
和原有 100 轮持久性断言，并追加恰好 100 次刷新请求的断言。调度器、刷新器和 Worker 的独立
真机测试仍保留，生产实现不改，不通过捕获/过滤数据库关闭异常处理问题。修正后重新执行相关
专项与完整真机套件，并再次检查日志。

修正后的设置 ViewModel / 外观刷新 / Room 兼容 / 小组件调度与刷新器专项共 24 项通过，
无失败、错误或跳过；编译告警门禁通过，专项日志未命中关闭后访问或进程致命异常。

### 最终验证（2026-09-23）

- 修正后再次 `clean`，再以以上并发限制执行 `ANDROID_SERIAL=<授权真机> ./tools/verify android`：
  126 项 Gradle 任务全部实际执行，总耗时 14 分 15 秒。
- 完整真机 733 项，0 失败、0 错误、0 跳过，instrumentation 耗时 739.287 秒；
  逐项对比 #155 的原始 XML，保留全部 731 个原用例，仅增加两项 Room 用例，无重复或漏项。
- debug / testbed / instrumentation APK、lint、代码生成及零编译告警门禁通过；
  输入校验、真实 Room 指标仓库、TimerService 覆盖率校准通过。
  既有 lint 基线仍过滤 226 项错误和 4 项提示，本批未扩大基线或告警预算。
- 最终全部用例日志未命中关闭后访问与致命异常检索项；原先发现的后台任务残留不再出现。
  这不是穷尽所有日志错误或线程调度的证明。
- schema v1/v2 无差异，生成的 version 2 identity 仍为 `62d3fb801611543fbe1062b9713ffd2c`。
  依赖报告核对 Room 运行库与处理器解析到同一候选，无额外全局依赖覆盖。
- 仓库工具 20 项检查通过。PR CI 结果另附，不将托管静态检查替代本次真机结果。
- 补充执行 `:app:hiltJavaCompileRelease` 通过，Release Kotlin/Java/Hilt 编译及零编译告警门禁通过；
  未签名、打包或安装 Release，也不将编译结果当作运行验收。

最终 XML、覆盖率、lint、依赖报告及中间失败/复测日志保存于仓库外
`DayForge-test-audit-2026-09-23/room-173/`，不提交原始设备日志或 APK。

## 边界与回滚

只操作独立 `com.dayforge.testbed`，不覆盖正式 App 或清除用户数据，不执行模拟器/JVM 测试或 Docker。
受控 Flow 测试不是穷尽所有线程调度的竞态证明；物理 Android API、手动覆盖安装、重启/断电、
真实 launcher、NAS/外网仍按原专项跟踪，未执行不能记为通过。
回滚须确认 schema / identity 无漂移，仅撤回本 Room 升级，不回滚已合并工具链或用户数据。

# Android 工具链兼容升级（Issue #155）

## 范围与选择

在 #172 / PR #174 的 Room 兼容前置完成后，恢复此前单独保存的工具链候选。
版本以 `android/gradle/libs.versions.toml` 与 Wrapper 为准；本批解决 compileSdk 的受支持组合，
不追求一次升级所有库，也不混入 #173 的 Room 后续升级或 UI 重构。

审查依据：

- [AGP 兼容表](https://developer.android.com/build/releases/agp-8-11-0-release-notes)：所选系列支持 API 36，
  需要 Gradle 8.13 / JDK 17，保留项目现有 compileSdk、buildTools、minSdk 与 targetSdk。
- [Kotlin 兼容表](https://kotlinlang.org/docs/gradle-configure-project.html)：所选 Kotlin / Compose Compiler
  同版本组合覆盖当前 AGP 与 Gradle；不同时升级 Compose BOM 或界面组件库。
- [KSP 发布记录](https://github.com/google/ksp/releases/tag/2.2.21-2.0.5) 与
  [Hilt 发布记录](https://github.com/google/dagger/releases/tag/dagger-2.57.2)：采用 KSP2，
  Hilt 插件、编译器、运行库与测试库共用同一版本入口，避免生成链与测试环境错配。

不修改 Room / schema、数据归属、时间语义、计时状态机、同步协议、后端或用户数据。
Room 仍保留 Java 代码生成与 SupportSQLite/openHelper 路径。

## 配置与完整性

- 删除 `android.suppressUnsupportedCompileSdk=36`，由受支持工具链解决兼容提示。
- 使用 `compilerOptions.jvmTarget` 保持 Kotlin / Java 目标均为 17。
- 使用 AGP 的 `hostTests` 变体 API 禁用主机测试，继续执行隔离 testbed 的真机测试；
  不恢复 JVM / Robolectric 或模拟器测试，不延长测试超时。
- 更新 Wrapper jar / Unix 脚本并补齐生成的 Windows 脚本；分发包 SHA256 写入 Wrapper 配置。
  官方 [分发包校验值](https://services.gradle.org/distributions/gradle-8.13-bin.zip.sha256) 与
  [Wrapper jar 校验值](https://services.gradle.org/distributions/gradle-8.13-wrapper.jar.sha256) 均已核对。
  Windows 脚本为同版本生成物，本机不宣称执行过 Windows 验证。

## 验证记录

### 必要源码适配

新编译器提示 28 处构造属性的 `@ApplicationContext` 默认目标将在后续版本扩展。
逐处改为 `@param:ApplicationContext`，明确保留原来仅限定构造参数的行为，不全局开启新传播规则。
依据为 [Kotlin 注解目标说明](https://kotlinlang.org/docs/whatsnew22.html#new-defaulting-rules-for-use-site-annotation-targets)。
另外删除 `HabitPriorityCalculator` 已穷尽四种 `HabitType` 后不可达的 `else`，保留四个分支原计算。
不扩大告警预算、不添加抑制，也不改页面布局或交互。

首次候选构建已完成 Room/KSP2 与 Hilt 生成，但新版 lint 阻止了 46 条 `UseKtx` 报告。
逐项采用既有 AndroidX Core 的等价扩展：28 处颜色解析、7 处 URI 转换（每处重复报告两次）
及 4 处偏好设置编辑。颜色解析原有异常处理和回退色保持不变；编辑显式使用
`edit(commit = true)`，保留先写磁盘再刷新组件的顺序，不能改成默认异步 `apply`。
同时检查锁定 Core KTX AAR 的字节码：`toColorInt` / `toUri` 分别直接调用
`Color.parseColor` / `Uri.parse`，`edit` 在布尔参数为 true 时调用 `Editor.commit`。
新版 lint 对显式 KTX 同步写入不再报告旧的 3 条 `ApplySharedPref` 建议，删除对应失配基线项；
这是报告匹配变化，不代表改成异步写入或消除了同步 I/O。其余基线与零编译告警预算不扩大。

### 本批实际执行

- JDK 17 执行 `android/gradlew -p android clean`，再执行
  `ANDROID_SERIAL=<授权真机> ./tools/verify android`：通过，126 项 Gradle 任务全部实际执行，
  总耗时 16 分 4 秒；未使用上一批测试结果。
- Android 15 / API 35 物理 MI 6，独立 testbed：731 项，0 失败、0 错误、0 跳过，
  instrumentation 报告耗时 736.884 秒；未增加原有超时。
- debug APK、testbed APK、instrumentation APK、Room/Hilt 代码生成、lint 与覆盖率报告均通过。
  lint 无新增问题，剩余既有基线过滤 226 项错误及 4 项提示；编译告警 0 / 允许 0。
- 覆盖率校准通过：输入校验、真实 Room 指标仓库与 TimerService 均有实际源代码行覆盖。
  731 项为实际展开用例数，不代表所有真实设备/网络场景均已覆盖。
- 配置诊断确认无 `test*UnitTest` 任务，KSP 任务类型为 `KspAATask_Decorated`（KSP2）。
- 额外执行 `:app:hiltJavaCompileRelease`：release Kotlin、Java 与 Hilt 编译通过，告警 0；
  未执行 release 签名/打包，也未安装正式包。
- schema v1/v2 无差异，当前 identity hash 仍为 `62d3fb801611543fbe1062b9713ffd2c`。
- `./tools/verify root`：20 项仓库工具测试通过，diff / 忽略与敏感文件路径检查通过。

原始真机 XML、覆盖率、lint 与构建日志保存于仓库外的本机审计目录，不提交设备标识、日志或 APK。
PR 仍须通过托管 CI；CI 仅进行构建/静态检查，不能代替以上本批真机结果。

## 验证边界与回滚

只使用授权物理手机与独立 `com.dayforge.testbed`；不覆盖正式 App、不清除用户数据。
本批不操作 Docker、不发布镜像、不做 NAS / WAN 或人工界面验收。
渲染、输入法、实际 launcher、厂商后台与网络路由的人工验收继续由 #157 / #21 分组跟踪，
不能把 CI 构建或受控 instrumentation 场景当作这些层级通过。
回滚时撤回整个工具链兼容组及其必要适配，保留已经独立合并的 Room 前置，不回滚用户数据。

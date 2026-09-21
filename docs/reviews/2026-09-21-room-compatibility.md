# Room 兼容升级（Issue #172）

## 决策与范围

用户同意先独立处理 #155 暴露的 Room / KSP2 兼容问题，再继续工具链升级。
本批只升级 Room 运行库与处理器，保留现有系统 SQLite、SupportSQLite 接口和 Java 代码生成。
不切换 SQLiteDriver、不引入 Room Gradle 插件，不改 minSdk / targetSdk、UI、数据库版本、迁移、
账户、时间、计时状态机或同步协议。工具链候选仍留在 #155，不能以本批通过宣称它已验证。

候选选择依据为 [Room 官方发布说明](https://developer.android.com/jetpack/androidx/releases/room)：
2.7 开始支持 Kotlin 2.0 / KSP2，并默认启用 Kotlin 代码生成。本批采用该系列修正版作为
过渡兼容版本，而不是宣称已完成最新稳定版升级；具体锁定版本以版本目录为准。
明确设置 `room.generateKotlin=false`，避免在兼容升级中额外改变 DAO 的代码生成与可空性策略。
生产 provider 仍使用 `openHelper`，不采用可选的 SQLiteDriver / bundled SQLite 路径。

### 候选验证与顺序调整

先验证调研时最新稳定版 2.8.5，当前 KSP1 环境在读取 schema 时发生
`FieldBundle$$serializer / GeneratedSerializer.typeParametersSerializers` 二进制接口错误；
处理器依赖树解析到 serialization 1.8.1，错误发生在代码生成而非业务数据库运行期。
仅使用命令行开启现有插件的 KSP2 再探测，旧 Hilt 处理器的 shaded XProcessing 报
`KSTypeArgument.type ... STAR null`，不能视为兼容组合。

因此先验证仍兼容当前生成链的 Room 2.7.2，再继续 #155 的 KSP2 / Hilt 兼容组升级。
Room 2.8 系列由 #173 跟踪后续独立升级，不为追求版本号在本批混入整个工具链，也不关闭 schema 导出、
强制全局降级 serialization、启用破坏性迁移或削弱警告门禁。上述失败候选与命令行探测未提交。

## 升级保护

新增 `RoomUpgradeCompatibilityTest`，先在旧 Room 上运行同一组测试，再执行升级后的完整套件。
测试从已提交 schema 2 通过 FrameworkSQLiteOpenHelper 建库，不使用升级后生成的建表语句；
固定旧 identity hash，且最终检查 schema 1 / 2 文件无差异，不为库升级制造空迁移。

- 为全部 13 张业务 / 同步表建立非空合成数据，包含毫秒时间与记录时区、普通与拒绝 outbox、
  server shadow、冲突、计时命令 / 时间段 / 按天分配；测试完整字段快照，不仅比较行数。
- 通过生产 provider 打开及重开，比较全部数据、Room identity、表 / 索引 / 触发器 SQL，
  同时检查版本、SQLite integrity / foreign-key 检查和 DAO 解码。
- 在 Room 事务中修改习惯并确认 outbox 已生成，随后注入失败，验证全库快照回滚。
- 成功事务后验证习惯 Flow 与触发器写入的 outbox 数量 Flow 均通知；重开后继续写入仍生成 outbox。
- 原迁移、降级拒绝、同步保留、计时状态机等测试全部保留。计时合成夹具只是存储保护证据，
  真实计时行为仍由已有 Service 测试覆盖，不能把夹具当作完成了一分钟计时。

## 验证记录

- 升级前：新增 2 项在授权 MI 6 / Android 15 / API 35 真机通过，0 失败 / 错误 / 跳过。
- 升级后执行 `clean`，再执行 `ANDROID_SERIAL=<serial> ./tools/verify android`：731 项真机测试，
  0 失败 / 错误 / 跳过；完整构建及测试共 14 分 37 秒。新增 2 项包含在完整套件中，无筛选运行。
- debug / deviceTest / instrumentation APK 构建、lint、覆盖率校准与编译警告门禁通过，
  编译警告 0 / 预算 0；原迁移、同步记录保留、真实计时服务和账户用例全部保留并执行。
- schema 1 / 2 与基线逐文件无差异，生成的版本 2 identity hash 仍为
  `62d3fb801611543fbe1062b9713ffd2c`；生产源码、契约和后端均无修改。
- 仓库检查 20 项通过，最终 diff 检查通过。托管 CI 结果单独记录在 PR，绿色不替代真机行为验证。
- 原始报告保存在仓库外 `DayForge-test-audit-2026-09-21/room-172/`，不提交设备信息或生成物。

## 边界与回滚

使用隔离 `com.dayforge.testbed`，不覆盖正式包、不清除用户数据；不执行 JVM / 模拟器测试。
没有人工覆盖安装、手机重启 / 断电、其他 Android API、真实 NAS / 外网联合验收证据。
库升级不改变 schema，预期可回滚本批依赖与生成配置；仍须以 schema / identity 无漂移为前提。
如完整测试或 schema 对比失败，停止合并并定位，不扩大告警预算或更改业务来绕过。

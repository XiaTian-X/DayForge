# Android Module Rules

- 用户于 2026-09-20 明确要求：后续所有 Android 测试均使用真机执行，不下载、创建或运行模拟器/AVD，也不以 JVM/Robolectric 执行替代真机测试。未连接且授权真机时停止 Android 测试执行，并明确记录未验证；构建和静态审查不算测试通过。

- 依赖方向保持 `Compose UI -> ViewModel -> domain service/use case -> repository -> Room/API`。
- UI 和 ViewModel 不直接访问 DAO，不自行拼装同步请求。
- Repository 负责本地事务、outbox、远端合并和同步状态持久化。
- 使用结构化协程并遵守生命周期；禁止 `GlobalScope`。
- 业务时间使用 `Instant`、IANA `ZoneId` 和明确的本地日期转换，禁止依赖服务器时区。
- 计时必须通过持久化状态机，正在运行的计时不能被当作完整计时结果。
- Room 版本变化必须更新 `app/schemas` 并覆盖升级测试。
- 依赖版本只在 `gradle/libs.versions.toml` 中维护；工具链升级按兼容组单独提交。
- 运行命令和最低验证矩阵见 `../docs/TESTING.md`。

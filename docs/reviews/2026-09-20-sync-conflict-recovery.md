# 同步冲突与拒绝恢复真机回归（Issue #130）

## 目标与依据

基于 PR #129，继续补齐真实存储验证。原 `IncrementalSyncRepositoryTest` 的冲突用例在 mocked merger
回调中手工插入预期冲突、删除队列，再验证该替身产生的数据；不能证明生产合并器事务和最新本地编辑保存正确。
本批使用真实 Room、DataStore、Keystore、Retrofit、仓库和合并器；仅在 HTTP 传输边界提供独立 JSON / IOException。
预期依据 `docs/SYNC_PROTOCOL.md`、`contracts/sync-v2/client/conflict-stale.json`、真实 outbox schema，
以及中英文 `sync_rejected_discard_message` 的既有用户约定。没有修改生产实现、同步语义、账户规则、schema 或依赖。

## 旧场景迁移对应

`SyncDurabilityTest` 和 `SyncConflictDurabilityTest` 共用 `SyncPersistenceFixture`，每项独立建库、
独立 DataStore 文件和测试密钥，关键断言在关闭并重开存储后执行。原有真机用例保留原断言。
以下 15 个旧场景全部有真实执行路径后，移除旧的 `IncrementalSyncRepositoryTest` 及其内存 DAO / mocked merger。
这不是删除失败断言获得绿色；其中 7 个场景复用 #126 已有真机覆盖，其余在本批补全。

| 旧场景 | 真机覆盖（方法名简写前缀） |
| --- | --- |
| 首次 bootstrap 一次并保存高水位 | `SyncDurabilityTest.bootstrap_persists_rows…` |
| 上传进度先持久确认再拉取 | `SyncDurabilityTest.upload_progress_is_reported…`；同时读取实际队列数量 |
| 响应丢失重放完全相同操作 | `SyncDurabilityTest.lost_response_replays…`；额外保留发送后新编辑 |
| 第二批 push 中断 | `SyncDurabilityTest.interrupted_second_push_batch…`；100 / 1 / 1 分批和操作内容保留 |
| 第二页 pull 中断 | `SyncDurabilityTest.interrupted_pull_resumes…` |
| 游标不前进拒绝 | `SyncDurabilityTest.invalid_pagination…` |
| 子节点先于父目标删除 | `SyncDurabilityTest.persisted_parent_first_deletions…`；故意反序保存队列，避免输入本来有序掩盖缺陷 |
| 干净缓存收到坏增量后 bootstrap | `SyncDurabilityTest.malformed_incremental_page…`；验证旧记录消失、权威记录与游标持久保存 |
| 冲突保留发送期间最新编辑，等待用户处理 | `SyncConflictDurabilityTest.conflict_persists_newest_intent…`；通过真实 DAO 修改，验证三份版本和无自动重传 |
| 坏冲突隔离且继续 pull | `SyncConflictDurabilityTest.malformed_conflicts…`；分别缺 revision / 权威实体 |
| 永久拒绝隔离、显式重试换 ID | `SyncConflictDurabilityTest.rejection_survives_reopen…`、`retry_all_rejections…` |
| 放弃拒绝后要求重新 bootstrap | `SyncConflictDurabilityTest.discard_failed_delete…`、`discard_then_failed_bootstrap…`、`discard_reloads…` |
| 旧会话缺公开账户 ID 时 refresh 回填 | `SyncDurabilityTest.legacy_session…`；真实旧格式 DataStore、请求 JSON、请求顺序及重开后的账户/令牌 |
| 错误服务器身份阻止注册和上传 | `SyncDurabilityTest.another_server…` |
| 干净 epoch 变化重建本地副本 | `SyncDurabilityTest.clean_epoch…` |

本批新增 17 项真机测试：4 项仓库剩余场景、13 项冲突/拒绝恢复。
除旧场景迁移外，新增本地/服务端解决方案持久保存和重复处理拒绝、服务端墓碑不可被本地恢复、
冲突插入 SQL 故障整体回滚、本地方案 outbox 插入失败回滚、处理状态更新失败、
放弃时删除失败先保留恢复意图，以及 bootstrap 失败后重试。

## 测试预期自身的审查

首轮新增测试出现 3 个错误预期，均根据独立依据修正，未修改生产代码来迎合测试：

- 手工替身习惯的 `referenceUuid` 为 null，但真实习惯触发器在该列保存 `habitType`；测试现在验证 `GOAL`。
- v4 操作请求只发送 `base_revision`，不发送 `base_payload`（见提交的 conflict-stale fixture）；
  基准 payload 在本地持久化并验证，网络请求另行验证完整字段集合。
- “放弃被拒绝修改”界面明确承诺保留当前本地数据。因此放弃后仅本地存在的记录仍保留、队列消失；
  服务器已有的同 UUID 记录在重新 bootstrap 后应用权威版本。不能把“移除本地记录”擅自当作正确预期。

这说明新增测试同样可能有错误；绿色结果必须同时具备独立预期依据和错误实现检出证据。

## 故障注入

五个临时错误变体分别单独编译并运行指定真机测试；每轮恰有 1 项 JUnit 断言失败，
无跳过或 instrumentation 崩溃。每轮原样还原文件，所有错误变体均未提交。

| 错误变体 | 实际失败证据 |
| --- | --- |
| 冲突保存发送时的旧快照 | 应保留 `Newest local edit`，实际为 `Sent snapshot`。 |
| 去掉冲突保存的外层 Room 事务 | 冲突插入失败后应保留最新本地编辑，实际已变成 `Server title`。 |
| 显式重试继续使用被拒绝的 operation ID | 新旧 ID 不等断言失败。 |
| 删除拒绝记录之后才保存 bootstrap 标记 | 删除 SQL 失败后，恢复标记未保存。 |
| 跳过父子删除操作排序 | 反序队列直接发送，子先父后的独立顺序断言失败。 |

这些是代表性错误检出检验，不是穷尽变异测试。

## 验证结果

- `ANDROID_SERIAL=<serial> ./tools/verify android`：最终版本 MI 6 / Android 15 / API 35，98 项通过，
  0 失败、0 错误、0 跳过，耗时 2 分 55 秒；已还原全部 5 个错误变体。
  构成为上批 81 项加本批 17 项。`SyncDurabilityTest` 16 项、`SyncConflictDurabilityTest` 13 项。
- lint、debug/deviceTest/instrumentation APK 构建、编译警告预算 0、覆盖率校准通过。
- 最终真机覆盖率 XML 源代码行：`IncrementalSyncRepository.kt` 328 覆盖 / 67 未覆盖，
  `SyncV2Merger.kt` 329 / 75，`TokenManager.kt` 172 / 21。覆盖率用于确认生产实现被执行，不证明行为穷尽。
- `./tools/verify root`：12 项通过；差异检查通过，生产代码、schema、后端和契约与基线一致。
- 原有 12 个 `SyncDurabilityTest` 方法正文经文本对比保持原样；旧 15 个模拟测试在完成迁移映射后移除。
- 最终 JUnit / 覆盖率 XML、构建日志、5 组故障证据、首轮错误预期运行证据保存在仓库外
  `DayForge-test-audit-2026-09-20/sync-conflict-130/`，不提交真实数据库或构建生成物。

## 验证边界

- Android 行为仅在已授权 MI 6 / Android 15 / API 35 真机验证；独立 `com.dayforge.testbed`，不运行模拟器或 JVM/Robolectric。
- 请求中修改本地数据验证响应边界时出现的新编辑，不证明任意线程交错或真实网络时序均已穷尽。
- SQL 故障验证对应事务回滚；关闭重开存储不等同于进程强杀、手机重启或断电。
- DataStore 恢复标记先于 Room 队列删除，允许额外 bootstrap；两个存储不是原子事务。
- HTTP 响应为受控边界，未执行真实后端/NAS/外网联合验收；后端与契约未改，本批未重跑后端测试。
- 托管 Android CI 仅构建和静态检查，不代表设备行为通过。历史 `src/test` 仍有 73 个 `*Test.kt` 文件、578 个静态 `@Test` 方法，继续保留且不执行，不计入真机结果。

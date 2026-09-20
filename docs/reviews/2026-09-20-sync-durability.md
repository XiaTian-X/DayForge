# 同步持久性真机回归（Issue #126）

## 范围与依据

基于已合并的 PR #125，继续执行用户要求的真机测试迁移。
预期来自 `docs/SYNC_PROTOCOL.md` 的稳定 operation ID、确认后移除 outbox、分页恢复和 epoch 保护，
以及 `AGENTS.md` 的业务写入/outbox 原子性。不修改生产实现、协议、数据库版本或依赖版本。

## 本批覆盖

| 测试类 | 真机范围 |
| --- | --- |
| `SyncV2OutboxTest` | 原 14 项完整迁移；新增插入/更新遇到 outbox SQL 故障时整体回滚，关闭并重开数据库后验证结果。共 16 项。 |
| `SyncV2ContractFixtureTest` | 原 5 项完整迁移；APK assets 直接使用仓库 `contracts/sync-v2` 原文件，没有复制第二份样例。 |
| `SyncDurabilityTest` | 新增 12 项：真实 Room、DataStore、Keystore、Retrofit、同步仓库、合并器和计时同步仓库；传输边界返回独立 JSON 或 IOException。 |

持久性场景包括：首次 bootstrap、响应丢失后的请求快照重放、新编辑不覆盖在途请求、
101 条操作分批中断、部分确认、确认缺少 revision、分页中断后重开续传、整页合并失败回滚、
无效分页拒绝、已提交页重放不重复业务数据、脏 epoch 保留待同步数据、干净 epoch 更换缓存和错误服务器身份拒绝。
测试关闭 Room 和 DataStore 协程作用域后重新创建对象，从磁盘读取队列、业务记录和同步元数据。

## 仍待迁移的相关测试

以下为静态扫描的 `@Test` 方法数，不是实际执行数；参数化展开可能不同。
保留的历史测试不在真机结果内，不因新增相近场景而标记为全部迁移。

| 类 | 方法数 | 后续重点 |
| --- | ---: | --- |
| `IncrementalSyncRepositoryTest` | 15 | 本批已用真实存储覆盖部分关键行为；冲突保存/恢复、隔离拒绝、撤销和账户回填仍需逐项迁移。 |
| `SyncV2MapperTest` | 11 | 所有映射边界与字段独立预期。 |
| `TimerSyncRepositoryTest` | 9 | 有序计时命令、控制权、拒绝与恢复；本批未验证服务端计时状态机。 |
| `FactTimeMigrationTest` | 4 | 真实旧版本 Room schema 升级及 UTC/IANA 日期归属。 |
| `HabitRepositoryTest` | 22 | 事务、级联删除及子节点约束。 |
| `SyncManagerTest` / `AccountLocalStateCleanerTest` | 4 / 1 | 实际切换账户、清理本地存储及小组件绑定。 |
| `SyncV2AcceptanceTest` / `AccountTimestampContractTest` | 4 / 3 | 请求与时间契约逐项迁移。 |
| `AutoSyncCoordinatorNetworkTest` | 2 | 自动同步调度与网络事件。 |
| `CheckInWidgetMigrationTest` | 5 | 桌面组件配置迁移。 |

本批迁移后，历史 `src/test` 仍有 75 个 `*Test.kt` 文件、597 个 `@Test` 方法，继续保留且不执行。
这份清单用于安排后续批次，不代表这些历史断言均已审查为正确。

## 故障注入检验

以下四个临时变体分别单独编译、单独运行指定真机测试，每次均产生一个 JUnit 断言失败，
没有跳过或 instrumentation 进程崩溃。每次结束后原样还原生产文件，所有变体均未提交。

| 临时错误实现 | 独立失败证据 |
| --- | --- |
| 在 `merger.apply` 前保存拉取游标 | `failed_merge_rolls_back_the_whole_page_and_does_not_advance_cursor`：原游标应为 0，实际变成 2。 |
| 忽略已经发送的请求快照，按最新编辑重新准备请求 | `lost_response_replays_persisted_snapshot_before_uploading_a_newer_edit`：错误合并在途请求与新编辑，只发生 2 次上传，预期是原请求、原请求重放和新编辑共 3 次。 |
| 服务端合并时关闭 outbox 抑制 | `bootstrap_persists_rows_and_cursor_without_echoing_server_data`：预期无待上传操作，实际产生 1 条。 |
| 不安装 habits 表的 outbox 触发器 | `failed_outbox_insert_rolls_back_the_business_insert`：outbox 故障未阻止业务写入，异常断言失败。 |

它们证明本批代表性断言能识别对应错误，不是穷尽的变异测试。

## 2026-09-20 验证结果

- `ANDROID_SERIAL=<serial> ./tools/verify android`：MI 6 / Android 15 / API 35，最终 73 项通过，
  0 失败、0 错误、0 跳过；在四个变体全部恢复后执行，耗时 2 分 21 秒。
  本批完整迁移 19 项旧测试，新增 14 项，加上 #125 的 40 项构成当前真机集合。
- lint、debug/deviceTest APK 与 instrumentation APK 构建通过；编译警告 0 / 预算 0；覆盖率校准通过。
- 最终真机 XML 源代码行覆盖：`IncrementalSyncRepository` 为 228 覆盖 / 140 未覆盖，
  `SyncV2Merger` 为 206 / 104，`SyncSchemaCallback` 为 72 / 0。
  这证明这些生产类确实执行并被统计，不代表其所有分支和行为完整覆盖。
- `./tools/verify root`：12 项通过；最终 diff 检查通过，生产源文件与本批基线完全一致。
- 后端代码和契约内容未改，本批未重跑后端测试；后端与真实设备联合验收也未执行。

## 验证边界

- 只在已授权真机执行 Android 测试，使用独立 `com.dayforge.testbed` 包；不运行 JVM/Robolectric 或模拟器。
- 传输边界模拟“服务器已执行、响应丢失”，验证的是客户端请求标识和内容稳定，不能证明服务端真正只执行了一次。
- Room 合并与 DataStore 游标更新位于不同存储；本批验证先合并后推进游标及失败恢复，通过显式恢复旧游标验证已提交页可重放，不声称跨存储原子提交或真实崩溃恢复。
- 重开存储不是强杀进程、手机重启或断电；NAS、外网、真实服务器联调和其他 Android 版本仍未执行/豁免。
- 仅增加测试与测试资源配置；托管 CI 的 Android 项目仍只构建和静态检查，真机结果必须单独附入 PR。

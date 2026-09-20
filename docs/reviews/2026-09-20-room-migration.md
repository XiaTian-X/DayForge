# Room 迁移测试可信度回归（Issue #128）

## 范围与依据

基于已合并的 PR #127，把 `FactTimeMigrationTest` 原有 4 项测试完整迁移到真机，并新增 4 项。
预期来自 `docs/DECISIONS.md` D004 / D007：保留 UTC 瞬时值，优先恢复有效的 prepared outbox 元数据，
其次使用 server shadow；无法恢复时使用迁移开始时的设备 IANA 时区。未知开发 schema 和降级必须失败，不能清库。
本批只补测试，不修改生产实现、数据库版本、提交的 schema、同步契约或依赖。

## 修正的测试盲点

原测试自行创建 Room builder、注册 migration / callback，未经过 `HabitDatabaseProvider`。
即使应用入口错误启用破坏性降级，测试仍可能使用另一套正确配置而通过。
现在直接调用生产创建入口；从 instrumentation assets 读取仓库提交的 v1 schema，使用 Android SQLite 创建旧库。
仅允许在独立 `com.dayforge.testbed` 包下操作测试库，保留原有全部用例和断言。
迁移前后比较 outbox 的全部字段，不只检查条数或少数准备字段；迁移失败断言检查异常类型和原因。

## 新增测试

| 场景 | 独立断言 |
| --- | --- |
| 第二张事实表回填遇到 SQL ABORT，随后重试 | 关闭 Room，通过原生 SQLite 检查版本仍为 1、两表新增列均回滚、UTC / 值 / 全部 outbox 不变、旧触发器和 identity hash 保留。移除故障后，生产入口可升级至 2，正常写入仍触发 outbox。 |
| 未知开发 schema | v1 表含未知列，迁移后 Room 验证失败；重开原生 SQLite 验证版本、额外列和数据均保留，迁移新增列回滚。 |
| 候选元数据优先级 | 最新有效 prepared 请求优先于旧请求与 shadow；无效最新请求不遮蔽较旧有效请求。拒绝 revert、非法 JSON、错误日期、无效时区、过期瞬时时间和纯偏移时区，分别验证 shadow 或设备时区回退。 |
| 午夜和 DST 边界 | 两类事实均覆盖洛杉矶前一天、Kiritimati 后一天、纽约春季跳时与秋季重复时刻。使用固定毫秒值和日期字面量，不复用生产日期转换生成预期。关闭数据库、切换测试进程默认时区后重开，检查毫秒精度、日期、时区、来源和空 outbox；另验证实际完成时间与计划日期不同时按实际完成时间恢复元数据。 |

原 4 项继续覆盖混合元数据恢复、capture→映射→远端合并往返、未知版本降级拒绝，以及无效远端时间整体回滚。
更改的只是测试进程默认时区，不更改手机系统时间或系统时区。

## 故障注入检验

三种临时错误实现分别单独编译并执行指定真机测试，每次恰有 1 项测试因 JUnit 断言失败，
无跳过、无 instrumentation 进程崩溃。每轮恢复生产文件，所有错误变体均未提交。

| 临时错误实现 | 实际发现的问题 |
| --- | --- |
| 生产 provider 启用 `fallbackToDestructiveMigrationOnDowngrade()` | 降级不再抛出应有的拒绝异常，`unsupportedDowngradeFailsWithoutErasingRows` 失败。 |
| 忽略全部恢复到的元数据 | 最新 prepared 请求应保留 `Asia/Tokyo`，实际错误回退 `America/Los_Angeles`。 |
| 迁移把原始 `date` 加 1 毫秒 | 重开后精确毫秒断言失败，固定 UTC 值被改写。 |

这些结果证明代表性断言能识别对应错误，不是穷尽的变异测试。

## 验证结果

- `ANDROID_SERIAL=<serial> ./tools/verify android`：最终版本在 MI 6 / Android 15 / API 35 完成 81 项，
  0 失败、0 错误、0 跳过，耗时 2 分 24 秒。构成为 #127 已有 73 项，加上本批迁移的 4 项和新增的 4 项。
  此结果在全部故障变体恢复后执行，包含最终测试包清理保护。
- lint、debug/deviceTest APK 与 instrumentation APK 构建通过；编译警告 0 / 预算 0；覆盖率校准通过。
- 最终真机覆盖率 XML：`FactTimeMigration.kt` 42 行覆盖 / 0 未覆盖；`HabitDatabaseProvider.kt` 11 / 0。
  这证明生产迁移和真实创建入口被执行，不代表所有分支与场景穷尽。
- `./tools/verify root`：12 项通过；最终 diff 检查通过，生产源文件、schema、后端和契约与本批基线一致。
- 原始 JUnit XML、覆盖率 XML、运行日志和 3 组独立故障证据保存在仓库外的本地审计目录
  `DayForge-test-audit-2026-09-20/room-migration-128/`，不提交设备数据或构建生成物。

## 验证边界与剩余工作

- 本批只使用 MI 6 / Android 15 / API 35 真机；其他 Android 版本未验证，不运行模拟器或 JVM/Robolectric。
- SQL ABORT 和 schema 验证失败证明相应事务回滚；关闭并重开数据库不等同于强杀进程、手机重启或断电测试。
- 本批未改后端，未重跑后端测试；NAS、外网和真实后端联合验收未执行，保留豁免。
- 托管 CI 的 Android 检查仅构建和静态检查，不能替代真机结果。
- 历史 `src/test` 还保留 74 个 `*Test.kt` 文件、593 个静态 `@Test` 方法，均不计入本批执行结果。
  这是迁移进度，不代表剩余断言已经正确；下一批应继续按风险迁移同步冲突/恢复和仓库事务测试。

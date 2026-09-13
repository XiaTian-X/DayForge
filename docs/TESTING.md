# 测试规范

## 验证层级

### Android 改动

最低要求：

```bash
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
```

统一验证入口和 CI 还执行 AGP 的 `createDebugUnitTestCoverageReport`。HTML/XML 报告位于
`android/app/build/reports/coverage/test/debug/`，包含 Kotlin 业务类。覆盖率不能代替异常路径断言；
全项目汇总包含生成类，审查时应优先检查 domain、repository、sync 等业务包和具体分支。

Gradle 单测任务上限为 15 分钟；CI 的 Android 验证步骤上限为 20 分钟、整个作业为 25 分钟；后端作业为 15 分钟。
超时属于检查失败，不能视为通过。Android 失败时会保留已生成的单测/lint 报告 14 天；
若构建尚未生成报告，不能凭缺少报告判断测试结果。扩充测试或升级工具链时可依据实测调整时限，
不能用延长时限掩盖未结束的协程或测试挂起。
CI 还记录单测开始/失败/跳过事件，帮助定位未完成的用例；本地默认不增加逐用例日志。

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

### 后端改动

最低要求为锁定环境中的格式、静态检查、类型检查和 pytest。数据库相关改动还必须从空 SQLite 执行完整 Alembic upgrade，并验证模型与迁移一致。

### 联合改动

协议、时间、账户、数据库或同步改动必须启动临时后端，至少覆盖：登录、设备注册、首次同步、增量 push/pull、重复提交幂等、创建/修改/删除、账户隔离、冲突、时间与计时、备份恢复。

同步协议的仓库级 JSON 样例位于 `contracts/sync-v2/`。后端的 `test_sync_contract_matrix.py` 和 Android 的 `SyncV2ContractFixtureTest` 必须读取这些共享文件，不能在各自模块复制一份。修改 `contracts/` 会同时触发两个 CI 模块，避免只验证单端。

后端覆盖率必须同时跟踪 `thread` 与 `greenlet`（见 `backend/pyproject.toml`），
否则 SQLAlchemy 异步数据库调用切换后的已执行代码可能被误报为未覆盖。
修正统计配置产生的覆盖率变化不代表新增测试；仍需检查实际异常路径和断言。

统一验证入口通过 pytest 插件和 `backend/warning-budget.json` 登记已有 warning 的完整类别、
规范化消息和最大数量。
未知 warning 或数量增长会令测试失败；减少不会阻断单个增量测试，但完整测试确认减少后，
必须在同一 PR 下调预算。任何预算增加都需要关联明确的问题和审查理由，不能用过滤、宽泛匹配
或提高上限来掩盖可修复警告。lint 基线也只允许随修复缩小。

## 必须长期覆盖的异常矩阵

- 无网络、有移动网络但局域网服务器不可达、Wi-Fi 可用但端口不可达。
- DNS、TLS、代理、认证、限流、数据库繁忙和协议不兼容。
- 服务端提交成功但响应丢失。
- 应用被杀死、设备重启、后台限制和同步任务重复调度。
- 服务器与终端时区不同、UTC 日期变化、负时区和夏令时切换。
- 计时跨午夜、暂停后跨日、离线完成后重放、重复 stop。
- 两账户同名实体、设备令牌撤销、服务端恢复后 epoch 改变。

## 验收表述

自动化、模拟器、真机、外网和 NAS 是不同证据。未执行的层级必须写为“未执行/豁免”，不能由其他层级替代。

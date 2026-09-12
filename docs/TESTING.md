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

Room schema、计时、后台任务或 Android 平台行为变化时还需要相应 instrumentation/真机测试。仅含 TODO、没有断言或没有执行路径的测试不计为有效覆盖。

### 后端改动

最低要求为锁定环境中的格式、静态检查、类型检查和 pytest。数据库相关改动还必须从空 SQLite 执行完整 Alembic upgrade，并验证模型与迁移一致。

### 联合改动

协议、时间、账户、数据库或同步改动必须启动临时后端，至少覆盖：登录、设备注册、首次同步、增量 push/pull、重复提交幂等、创建/修改/删除、账户隔离、冲突、时间与计时、备份恢复。

同步协议的仓库级 JSON 样例位于 `contracts/sync-v2/`。后端的 `test_sync_contract_matrix.py` 和 Android 的 `SyncV2ContractFixtureTest` 必须读取这些共享文件，不能在各自模块复制一份。修改 `contracts/` 会同时触发两个 CI 模块，避免只验证单端。

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

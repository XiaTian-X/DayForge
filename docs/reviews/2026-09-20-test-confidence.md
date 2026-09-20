# 测试可信度整改（Issue #124）

## 范围和结论

本批处理审查中已实证的七类测试缺口，测试预期沿用既有账户、时间、同步和计时契约。
不修改数据库 schema、协议或依赖版本。功能变更只有指标卡片主体补接详情点击回调；
加密器增加内部测试密钥别名入口，生产注入仍使用原别名。

| 缺口 | 本批证据 |
| --- | --- |
| 计时服务入口未受保护 | 真机启动实际 Service，真实经过一分钟计时，暂停后重建服务、恢复、停止；检查通知、完整记录、命令序列和关闭的 segment；另测过期命令、丢弃和 outbox 写入失败回滚。 |
| 认证接口测试只验证 mock | 真实 Retrofit 生成请求，传输边界独立断言 HTTP 方法、路径、JSON、响应字段和 401。 |
| 5 个文件共 10 项恒真占位测试 | 改为真机 Compose 点击/输入断言，以及真实 DashboardViewModel、Room、DataStore 的提示与记录流程；捕获并修复卡片主体点击不导航。 |
| CORS 测试未触发预检 | OPTIONS 覆盖 GET/POST/PUT/PATCH/DELETE、认证请求头、拒绝来源及 TRACE。 |
| 数据库夹具偏离生产 | 每测试独立 SQLite，使用生产 FK/WAL 配置；显式区分共享会话测试与 Alembic + 原始 get_session 的 HTTP 测试，并从独立连接读回提交结果。 |
| 替身加密器被当作加密证据 | 替身用例改为仅验证调用；真机 Android Keystore 验证随机 IV、不可导出密钥、篡改/丢失密钥失败和文件无明文、重开可读取。 |
| Android 覆盖率漏记实际执行类 | 按用户决策改用真机覆盖率，并加入三个应用类的非零行校准门禁；保留报告缺失、错误、仅生成类和局部漏报的门禁测试。 |

## 执行方式

Android 后续只在真机执行，不使用或下载模拟器。验证入口检查已授权设备和 QEMU 标识。
本批从 JVM 迁移 TokenManager、指标仓库等既有测试，并加入服务与平台加密用例；
尚未迁移的历史 JVM/Robolectric 测试保留，不作为本批真机结果的一部分。

- 真机：MI 6，Android 15 / API 35；`ANDROID_SERIAL=<serial> ./tools/verify android`。
- 后端：锁定的 uv 环境；`./tools/verify backend`。
- 仓库检查：`./tools/verify root`。
- 托管 CI：`./tools/verify android-build` 仅编译、lint、组装和检查警告，明确不宣称运行了真机测试。

## 2026-09-20 实测结果

| 检查 | 结果 |
| --- | --- |
| `./tools/verify android` | MI 6 / Android 15：40 项通过，0 失败、0 错误、0 跳过；最终恢复正确实现后的运行耗时 2 分 1 秒，包含构建和覆盖率报告。 |
| Android 静态检查 | lint、debug 和 deviceTest APK、instrumentation APK 均成功；编译警告 0，预算 0。 |
| `./tools/verify backend` | 703 项通过，1 项预算内警告；源代码行覆盖率 92%；锁定环境的 Ruff、格式、29 个文件的 mypy 和 OpenAPI 检查通过。 |
| `./tools/verify root` | 12 项通过，包含设备选择与覆盖率报告门禁的正反例。 |

最终真机 XML 报告中，`NumericInputUtils` 有 16 行覆盖 / 3 行未覆盖，
`MetricRepository` 为 36 / 17，`TimerService` 为 411 / 137。
这些数值用于确认应用类插桩生效，不代表三个类所有行为已经覆盖。
将整改前的 JVM 覆盖率 XML 交给新门禁时，仓库和计时服务均因零覆盖被拒绝。

### 故障注入：测试是否真的能发现错误

注入时保留测试断言；以下五类 Android 变体在同一轮真机运行中产生 11 个明确测试失败，
40 项均执行结束，未以 instrumentation 进程崩溃作为检出证据。
CORS 变体单独运行 10 项 CORS 测试，产生 4 个失败。
所有临时错误实现均已还原；最终真机验证重新运行上述完整 Android 入口，40 项全部通过。

| 临时错误实现 | 检出的测试 / 失败数 |
| --- | --- |
| `TimerService.onStartCommand` 仅启动前台通知并返回，不处理计时命令 | `TimerServicePersistenceTest` 的服务恢复、完整计时、outbox 回滚共 3 项。 |
| `AuthApi` 登录注解改为错误路径 | `AuthApiTest` 登录请求与 401 请求的独立路径断言共 2 项。 |
| `LinkedMetricsSection` 点击不调用 `onMetricClick` | 关联指标组件及习惯卡片导航共 2 项。 |
| `PostCheckInDialog` 空输入仍启用记录按钮 | 弹窗输入/提交测试 1 项。 |
| `AndroidKeystoreTokenCipher.encrypt` 直接返回明文 | 加密往返、损坏密文和 DataStore 文件检查共 3 项。 |
| CORS `allow_methods` 仅保留 GET | POST、PUT、PATCH、DELETE 预检共 4 项。 |

这六类代表性错误均被拦截；它们不是穷尽的变异测试，也不能据此推断其余测试预期全部正确。

## 验证边界

真机覆盖的是本批 instrumentation 测试，不是历史 Android 全套测试的等价迁移。
Service 重建不是应用进程被杀或设备重启；本批没有验证 NAS、外网、真实服务器联调、
厂商后台策略或其他 Android 版本。这些层级保持未执行，不由本批结果替代。

测试数与覆盖率只能证明执行范围；判断测试有效还需要检查独立预期和故障注入失败证据。

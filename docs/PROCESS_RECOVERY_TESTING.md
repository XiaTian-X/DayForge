# 真实进程恢复验证：仓库入口

当前入口为 [`tools/run_command_process_gates.py`](../tools/run_command_process_gates.py)，可从仓库根以 Python 模块执行。它与已独审611主机脚本逐字相同：54,031字节，SHA-256 `9b60c3460a839e9c43afa1d897af85c643958ea5d327a926fc7667e86d1eeeb7`。四个既有主机测试仅调整相对导入，包内路径及摘要见[源包清单](testing/process-recovery-tools.json)。本清单不是任何设备或APK的运行准入。

旧513目录、README及模板是历史封存，仍保留原hash；其旧驱动摘要和“515待终态”等状态不应用于当前入口。此处不覆盖历史原件、不复制旧准入作为新通过证据。

## 两种验证分开进行

仓库原 `./tools/verify root` 会通过现有unittest discover发现以下四个完整主机模块，共45个方法；无需修改 `tools/verify` 的CLI。也可从仓库根单独运行完整四模块：

```sh
python3 -B -m unittest \
  tools.tests.test_command_build_admission \
  tools.tests.test_command_process_protocol \
  tools.tests.test_replica_build_admission \
  tools.tests.test_replica_process_protocol
```

这些是合成文件、mock进程及协议拒绝检查，不构建APK、不调用adb、不证明Android恢复通过。它们是现有45方法的搬迁，没有新增retained五场景的真实运行证据。包准备时未执行它们；实际结果另记。

真实进程验证只能在对应普通安全门禁通过、最终Android源/构建输入冻结、实际APK已归档并取得独立准入之后运行。每组完整执行，不单选失败方法，不把有意终止PREPARE记作JUnit成功。

| group | 完整场景数量 | Android入口 | 准入format |
|---|---:|---|---|
| count | 4 | CountCommandProcessDriver | count-command-build-admission/v1 |
| metric | 3 | CountCommandProcessDriver | count-command-build-admission/v1 |
| retained | 5 | CountCommandProcessDriver | count-command-build-admission/v1 |
| replica-final | 2，每例三阶段 | ReplicaCompletionProcessDriver | replica-final-build-admission/v1 |

场景身份直接使用驱动内固定 `GROUPS` 全集合，不能通过CLI替换或删减。retained还要求完整 `RETAINED_ORDINARY_CLASSES` 并集，沿现有最终验证清单合并；本说明不另建立一套重复矩阵。旧组结果是限定历史证据，不自动成为最终APK的准入。

## 从真实结果生成准入

没有自动生成“通过”的入口。执行者从已封存的完整普通结果、实际源清单和实际APK生成新的JSON，再独立核查并固定整份JSON原字节SHA。不得编辑原XML/summary或把失败、跳过、部分方法伪装成成功。

必须具备以下原loader字段，精确结构以脚本 `load_admission` 为准：

- `format` 为上表对应值；`serial` 为新预检确认的授权真机；`packages` 精确为 `com.dayforge.testbed`、`com.dayforge.testbed.test`。
- `host_script_sha256` 是本脚本的实际摘要；`host_groups` 中每个声明组必须列出其固定完整case集合，且含本次所选组。旧count-only准入不能授权retained或replica-final。
- `sources.path/sha256` 指向最终完整冻结JSON；其 `files` 包含全部 `android/app/src`、`android/app/schemas` 及脚本的四个 `ADMISSION_BUILD_INPUTS`，每项精确hash与当前文件一致。不仅比较生产代码或APK摘要。
- `driver_source/driver_source_sha256` 指向上表的实际Kotlin入口，完整相对路径分别是 `android/app/src/androidTest/java/com/dayforge/CountCommandProcessDriver.kt` 和 `android/app/src/androidTest/java/com/dayforge/ReplicaCompletionProcessDriver.kt`。CLI的 `--admitted-driver-sha256` 是该Kotlin源摘要，不是host脚本或APK摘要。
- `ordinary_results` 引用完整成功结果：`path`、原字节 `sha256`、`sources_sha256`。每份结果必须具备真实 `validated_success=true`、exit_code0、failures0、正数cases与expected_test_methods相等、零漂移/新增/validation_errors；每个完整class身份/数量精确、0失败/跳过，结果自身 `manifest` 指向的源清单 `files` 必须与最终冻结完全相等。如果原结果格式不同，独立映射记录必须保留原XML/summary引用与核算依据，不改原结果来满足loader。
- `artifacts` 为这两个包各自全部真实APK/split的相对path及原字节SHA。源→构建→APK的关联由独立构建准入负责；loader的摘要比较不是编译器证明。

所有准入中的源、结果、APK路径必须位于显式 `--source-root` 内并使用相对路径；运行原件可置于已有忽略的构建/报告目录，无需将APK或运行数据库提交进Git。`ordinary_results[].manifest` 相对其结果文件目录。准入文件本身和输出目录由CLI显式指定。

源清单相同是严格条件；若后续修改被冻结的Android源，重新整理实际同源证据，不能只替换JSON中的hash继续使用旧成功。`approved=true`等说明字段不能替代外部固定准入SHA及loader全部检查。

## 运行完整组

以下只是参数形状，不是已填好的准入。先用已核实际值替换占位内容；不要将模板直接执行：

```sh
python3 -B -m tools.run_command_process_gates \
  --adb "/absolute/path/to/adb" \
  --serial "<authorized-physical-serial>" \
  --group retained \
  --source-root "/absolute/path/to/checkout" \
  --admission "/absolute/path/to/new-reviewed-admission.json" \
  --admission-sha256 "<sha256-of-that-json>" \
  --admitted-driver-sha256 "<sha256-of-CountCommandProcessDriver.kt>" \
  --output "/absolute/path/to/new-output-directory" \
  --budget-seconds 2700
```

其它组只在已独立准入的范围内替换group；replica-final同时使用其Kotlin入口摘要和准入format。输出目录必须不存在，永不覆盖旧证据。预算在启动前按[测试规范](TESTING.md)估算；整组2700～10800秒，Android每阶段150秒、host每阶段180秒、预期kill退出30秒均不变，构建预算独立。动态等待只减少主机状态扫描，不改变持续故障监测和截止。

主机脚本不构建、安装、卸载或清数据。执行者仅在整组开始前准备已授权隔离包、检查确切APK、授权真机及无未知存活进程；进入组后直到所有phase结束，不重装、不pm clear、不更换数据库/偏好/Keystore。每个组使用自己的真实checkpoint合同：count/metric/retained包含未返回调用边界；replica-final仅证明其明确资源闭合后的继续，不把两类崩溃边界混称。

脚本只有在实际checkpoint、run/nonce/唯一PID/cmdline/APK及原instrumentation状态都匹配时才发精确SIGKILL；恢复必须使用新PID、原持久文件并完成Android原断言、主机核查和对应实际owner收尾。任何单个marker、signal、终态JSON或host45方法通过都不是整组成功。

## 终态、失败与交付

保存每组plan/result、各phase原输出、checkpoint/terminal原字节、PID/APK绑定及独立核对；仅全部固定case真实完成才记组成功。失败保留 `FAILED_PRESERVED` 和原输出，不自动重跑或借后续成功改判旧失败。host停止自身adb子进程不证明App已退出；执行者核清活动资源后再进行授权testbed收尾，未知结束仍按原恢复合同处理。

最终交付引用同最终源的实际普通完整类与完整进程组结果；Launcher、真实系统任务、家庭场景和NAS/多设备等原范围独立记录。源码包、host测试、Android普通测试、真实进程验证分别报告，不把其中一个替代其它层。后续若脚本变动，重新核逻辑、相应完整host测试、包摘要和最终运行准入，旧封存不覆盖。

## 同账户两台真机

双机验收需显式指定两台不同的真实安装。驱动在首尾复核源、安装包与主机输入，并为 A1、B1、B2、B3、A2、B4、A3、B5、A4 九个固定阶段核对设备、PID、前序摘要、业务见证和实际关闭；两个安装共享同一隔离服务器。设备期限制为 150 秒/阶段，主机为 180 秒/阶段，整组上限 4,500 秒，含末尾预留的 120 秒清理。独立端的普通测试通过与九阶段业务结果分别登记。[双机驱动](../tools/run_dual_physical_acceptance.py)

| 准入合同 | 实物与前置条件 |
| --- | --- |
| v1，A/API35、B/API27 | 由本地、独立核准并固定摘要的准入文件显式绑定两个序列号。原 B 必须具有与其自身 API27、完整 Joint5 及实际 APK 对应的完整普通批次和独立设备证据。 |
| v2，A/API35、B/API36 | 两个安装分别提供完整 Q04A，包括实际 UTP 的 serial/API、完整五项发现与结果、XML、覆盖率、两个包的准确角色、后端与清理。驱动比对旧普通源和本次双机源，仅允许受审的双机主机与其测试差异；宿主文件管理器元数据按驱动的显式规则处理。Android 及后端输入逐项核对。 |

运行选择指定版本和硬件组合，拒绝额外设备、缺失原件与交换角色。实际 serial 由本地准入提供。成功后核实两个隔离包都已清除；失败时保留隔离状态和失败证据，记录每个已取得资源的真实关闭结果。USB 回环验收、家庭 NAS 部署和 API27 兼容性分别报告。

# ViewModel 与 UI 协调器测试审查（#141）

## 范围与预期

迁移 14 个历史文件、109 个方法：LinkedMetricCoordinator（2）、SettingsViewModel（8）、
SettingsAppearanceWidgetRefresh（2）、HabitDetailViewModel（7）、HabitDetailViewModelNotification（4）、
CreateHabitViewModel（15）、DashboardViewModel（18）、DashboardHabitListBuilder（4）、
DashboardTimeWindowTicker（6）、DashboardViewModelMetric（9）、NestedViewModel（10）、
GoalDraft（5）、EditHabitViewModelScheduleDays（12）、LoginViewModel（7）。新增两个失败恢复方法。

真实数据场景统一使用生产磁盘 Room 创建入口，保留所有现行行为断言。新建/编辑页原有固定 DataStore
文件改为唯一文件与显式 scope，关闭 ViewModel 并等待 DataStore 结束后删除；登录测试同样等待协程结束。
已正确管理生命周期的夹具保留原清理逻辑。时间窗口上限/下限改用固定 3600000/10000 毫秒预期，
避免从被测常量读取“正确答案”。

- 新建成功：除 UI 保存 ID 外，实际读取名称、描述、类型、目标数值和 outbox；重复名称拒绝不增加行。
- 新建失败：SQL 触发器使 outbox 插入失败，UI 不得宣称保存成功，事实与队列均为零；去除故障后必须能再次
  保存，关闭并重开数据库仍只有一条事实和一条待同步操作。
- 账户切换：缓存清理失败时旧会话已失效，新凭据未生效，旧归属和偏好仍保留；错误可见且再次登录可完成。
  这是既有清理顺序的回归验证，不改变账户归属或会话决策。传输/缓存清理替身不代表真实服务端已验收。

## 旧夹具问题

首次定向运行 111 项，108 通过、3 失败：

- 两个主页删除测试将普通习惯作为父节点，其中一个还构造孙节点，违反已生效 D-003。
  改为顶层目标；保留子节点场景仍检查解除关联，级联场景改为两个直接子习惯并精确检查数量为 2、
  三行全部删除和对话框清理。没有重新开放已废弃任意嵌套；非法层级拒绝由 #136 的仓库测试覆盖。
- 无效关联指标测试从 instrumentation 工作线程直接调用会显示 Toast 的 suspend UI 操作。
  改为在真实 Android 主 Looper 的协程调度器运行该操作，保留真实 Toast 和“无记录、待处理状态仍在”断言。
  没有模拟 Toast、吞异常或更改产品线程行为。

修正后相关 28 项定向测试通过。

## 错误实现与完整验证

三个可编译错误均在真机被断言检出：失败后不释放保存标记、清缓存前不失效旧凭据、一小时刷新上限改成两小时。
首个保存标记探针先被 2 秒有界状态等待检出；进一步增加“重试必须开始或完成”的即时断言，重跑同一错误
直接得到 AssertionError。每次均按字节恢复源码，未把构建失败算作检出。
最终完整真机 637 项通过，0 失败/错误/跳过；lint、构建、编译警告 0 和覆盖率校准通过，仓库 12 项通过。
首次完整门禁在未修改的旧 AccountLocalStateCleanerTest 文件遇到 lint 内部 FIR 阶段异常，未计为通过。
保持源码不变单独重跑 lint 通过，随后再次执行完整门禁得到上述结果，未关闭规则或升级工具。
本批无后端修改；后端最近完整结果为 #138 的 703 项、92% 行覆盖率，最终收尾仍将全量重跑。证据保存在仓库外
`/Users/xbase/workspace/DayForge-test-audit-2026-09-20/viewmodel-141/`。

## 边界

本批没有产品实现、协议、模型或依赖变化。仅 MI 6 / API 35 真机，其他 API、真实服务器、进程被杀、桌面
小组件与人工外观未验收；ViewModel 测试不能替代 Compose 页面点击验证。没有 JVM/Robolectric 或模拟器执行。

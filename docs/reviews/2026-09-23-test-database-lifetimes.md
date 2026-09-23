# 真机测试数据库生命周期修正（#178）

## 范围

基于 #177 合并后的 `a10a174`，仅修改 instrumentation 测试及测试规范。
不改生产数据库提供器、UI、Worker、业务规则、schema、同步协议或依赖；不操作正式 App 数据。

## 来源与证据

主分支 #176 的 733 项最终运行和 #177 的 743 项最终运行都出现测试包数据库连接泄露及
`file unlinked while open`；后者在 HabitDetail、MetricDetail、ProgressWidget 附近报告。
GC 可能延迟释放连接，不能将日志所在测试直接视作创建者。

源码和前序日志对应到以下真实请求路径：

- CreateHabitViewModelTest 保存习惯，GoalDraftTest 保存目标重试成功，经过真实仓库提交小组件刷新。
- HabitDetailViewModelTest 与 NestedViewModelTest 的打卡/生命周期更新同样提交真实刷新。
- CheckInMetricPromptTest 使用自己的内存库，但真实刷新器从 HabitDatabaseProvider 获取全局文件库，
  因此刷新读取的甚至不是当前用例持有的数据库；该全局实例不属于内存库 teardown。
- TimerServicePersistenceTest 在真实一分钟计时完成后提交异步刷新；停止 Service 不代表
  已结束由 WorkManager 接管的刷新任务。

这些用例的清理只管理自有 ViewModel/Service/Room，不管理另行排队的 Worker。
Worker 可能在测试清库/替换全局实例期间打开或持有数据库，导致跨用例访问或实例失去所有者。
旧日志还显示 CreateHabit 非保存用例期间仍有 Worker 因数据库协程取消而失败，说明任务已越过原用例。
此前首页和设置套件已局部隔离；本批补齐上述可确认的六个调用来源，不将全部历史 GC 告警宣称为精确归因。

## 修正与回归

引入仅显式采用的 IsolatedWidgetRefreshRule，在 WidgetRefreshScheduler 请求边界记录次数，
不实际排队后台渲染；规则覆盖整项测试生命周期并在 finally 恢复真实对象。
保留真实 Room/业务协调器/计时 Service、原持久性及 outbox 断言，追加刷新请求次数断言。
不在全局 runner 禁用 WorkManager，不削弱专门调度器/Worker/刷新器测试，不捕获忽略连接异常。

规则自身验证 setup/测试体/teardown 全部受控，正常与异常结束后真实调度器均恢复；
连续三轮真实文件数据库打卡写入 outbox、关闭数据库并清理文件，验证无后台请求入队。
计时服务测试仍经过真实一分钟计时，不补造完成结果、不缩短等待来取得绿色结果。

## 验证

两轮不同顺序的 56 项真机专项通过（均 0 失败、错误、跳过），第二轮先计时服务后页面测试，
最后执行规则及真实调度器/Worker/刷新器测试。两轮均未检出 SQLite 连接泄露、打开时删除、
关闭后访问或致命异常记录，编译告警 0 / 允许 0。
最终完整套件、覆盖率和 CI 结果统一记录在关联 #178 的修复 PR 验证部分；
未完成完整真机及 CI 验证前，不将 #178 标记解决。专项绿色不替代完整验证。
原始日志留在仓库外 `DayForge-test-audit-2026-09-23/database-178/`，不提交设备标识或运行数据。

## 回滚

仅撤销本批测试规则与采用点，不回退 #177 UI、Room 升级或任何业务数据。

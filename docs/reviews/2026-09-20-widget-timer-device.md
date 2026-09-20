# 最后一批桌面组件与计时测试审查（#144 / #146）

## 范围与旧测试问题

迁移最后 12 个历史文件、50 个静态方法：计时管理器 6、计时服务契约 12、账户清理 1，以及
组件调度/worker/刷新 15、打卡回调 6、配置页 1、旧“迁移”5、进度组件 4。
新增账户清理失败、空进度和混合完成比例 3 项，共 53 项。

- `ProgressWidgetTest` 原先在测试内部求和并计算完成条件，没有调用组件。现在调用生产
  `ProgressWidget.refreshWidgetData`，读取真实 Glance DataStore，固定断言 0/1、1/1、空库 0/0
  与混合比例 0.5→1；保留 3/5/7 次实际完成记录检查。
- 配置页旧测试自己过滤列表，现在启动真实 `CheckInWidgetConfigActivity`，实际检查打卡习惯可见、
  计数习惯不可见和数据库的两个输入样本。没有点击添加到用户桌面。
- `CheckInWidgetMigrationTest` 的“自动从 1x1 迁到 2x2”完全由测试自己复制 SharedPreferences，
  产品没有对应自动迁移入口。依用户无生产数据与 D-007，不增加虚构兼容功能；改名
  `CheckInWidgetStateTest`，保留类型、绑定 ID 等有效契约，改为真实 CheckIn/Counting 刷新与状态持久化。
  五个原场景分别对应计数状态、打卡状态、打卡绑定、计数绑定隔离、打卡绑定保留与删除标记。
- 缺少 habitId 的打卡回调原有 `assertTrue(true)` 改为数据库/队列均无写入且不排入刷新工作。
  实际回调使用 Hilt 应用图与生产磁盘 Room；正常打卡关闭重开后事实和 outbox 仍存在。
- 账户清理原测试只验证最终清空，现在在清理回调中断言绑定仍存在，从而验证真实先后顺序；新增
  存储清理异常必须原样传播且绑定保留。没有改变账户归属决策。
- TimerManager 用记录 Context 捕获真实 Intent，替代 Robolectric shadow；这是派发边界，不能
  宣称新启动了实际服务。完整服务执行、暂停重建与一分钟结果由既有 TimerServicePersistenceTest 验证。
  倒计时夹具在 DAO 返回时创建十秒前的记录，避免把 MockK 初次安装时间误计入业务时间。
- Worker 和刷新用例保留取消传播、有限重试、顺序和失败隔离断言。框架边界替身不代表真实
  WorkManager 持久调度或桌面渲染已经验收。

## Glance 夹具边界

使用仓库锁定 Glance 1.1.0 的公开 Intent→GlanceId 接口和唯一正整数 ID，在隔离 testbed 内读写真实
Glance 偏好。没有使用内部反射、伪造 Android 桌面绑定成功或修改用户桌面。绑定与渲染仍属于未验证项。
需要主题的状态用例初始化真实 Hilt 图，数据库使用统一生产创建入口；偏好键、mock 和数据库在测试后清理。

## 配置收尾

确认 `src/test` 不再有 Kotlin/Java 测试后，移除 Robolectric 依赖/属性文件、旧 testImplementation、
单测资源映射和 JVM 覆盖率配置；保留并明确声明 androidTest 使用的 JUnit/runner，版本不变。
AGP 8.7.3 的公开 beforeVariants API 不再创建空 JVM 测试变体。配置阶段拒绝重新加入 src/test* 的测试
源文件，避免未来新测试静默落到无人运行的目录。没有删除有效测试或通过跳过用例获得绿色。

## 验证

53 项定向真机首轮通过。三个可编译错误均被独立断言检出：未完成习惯被算作完成（0→1）、
过早清除绑定（42→-1）、错误增加重试次数（应完成队列却继续 Retry）；源码均按字节恢复。
清理配置后完整真机 712 项通过，0 失败/错误/跳过；lint、构建、警告 0、覆盖率校准与仓库 12 项通过。
配置探针误放旧目录源文件后，Gradle help 在任何任务前拒绝；恢复后任务清单保留真机入口且没有 JVM 单测任务。
本批没有产品实现变化，后端同阶段 703 项、92% 覆盖率，实际 backend/tools 树与本次交付一致。完整日志、XML、迁移映射与错误实现检出证据保存在仓库外
`/Users/xbase/workspace/DayForge-test-audit-2026-09-20/widget-timer-144/`。

仅 MI 6 / API 35 真机；其他 API/OEM、真实 launcher 绑定与视觉、WorkManager 后台持久调度、设备重启/断电、
NAS/外网与实际服务器联合验收未执行。无 JVM/Robolectric 或模拟器执行。

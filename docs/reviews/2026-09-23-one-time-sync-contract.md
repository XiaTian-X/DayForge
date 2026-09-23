# #181 事项事实传输与客户端投影审查

## 范围

接续已合并的 PR #183、#184，新增双端事项事件绑定、不可变转换证明、权威投影单调合并、
完整历史重建及本地待确认因果链的显示计算。后端 NextActivityEventPayload 复用既有 UTC/IANA、
来源及业务日期校验，并验证一次性预条件；当前 v4 请求模型继续拒绝新字段。

没有接入现有路由、Room、Alembic、UI 或 outbox 写入；没有新增 HTTP 接口、切换协议或清数据。
OneTimeEventProof 仅描述完整事实快照的六个事项关联字段，不替代其时间/来源等全部 DTO。
独立 one_time_conflict 的形状与处理要求已定义，当前 v4 OpenAPI 未增加它。

## 审查重点

- 结构 revision 和事项状态 version 独立；状态随事实保存为当时的 state_after，不动态拼入结构快照。
- intent 与外层事件 UUID、check_in/revert 类型和根撤销目标完全绑定，服务端存储策略决定事项类型。
  metadata 中的仿冒字段不能替代预条件；请求不能提交 state_after 或混入计时/计数结果。
- 旧成功响应和乱序事实不能回退已确认状态；同版本不同 head/completion 拒绝，删除不能被迟到事实复活。
- bootstrap 历史必须从 1 开始、连续且因果正确，重复/缺段/分叉/错误事项均拒绝；输入顺序不影响有效结果。
  审查中发现仅有连续性仍无法识别末尾整段遗漏，已改为与同一 bootstrap 快照的独立权威校验点精确匹配。
- 乐观队列输入与操作 ID 不修改。基准变化而结果未知属于待重放，不等同于服务器拒绝；
  明确拒绝才阻断该操作与后继，前面的丢失响应仍可原 ID 重试。
- 本地显示计算不能确认 outbox，也不能删除指标事实。账户/epoch 隔离、数据库 CAS、事务、
  HTTP 权限与提交失败仍需实际接入测试；纯函数没有认证能力。
- 发现后续必须处理的真实接入点：现有结构编辑可直接重写 completion_policy；
  有事实后禁止习惯/事项互转必须后端兜底。新事件冲突没有 current_entity_snapshot，
  不能伪造事件成功实体来套用旧冲突 UI。
- 隔离文件库探针证实当前 SQLite 适配器的普通 SQLAlchemy 读取事务并非可重复快照：
  首读 0，另一连接提交 1 后同一读取事务再读到 1。生产同步目前还会更新设备活动时间，
  本探针不等于已复现生产 bootstrap 丢数据；但新校验点/事实/游标的一致性不能依赖这种附带写入。
  存储接入必须验证真实 HTTP 会话、并发写入和失败重试，不在本纯契约批次暗改连接策略。
  该现象符合 [SQLAlchemy 2.0 的 SQLite 旧事务模式说明](https://docs.sqlalchemy.org/en/20/dialects/sqlite.html#legacy-transaction-mode-with-the-sqlite3-driver)；
  调整 BEGIN 策略还需覆盖锁竞争与重试，不能只改连接参数后宣称一致性问题全部解决。

## 验证

- 仓库统一入口：22 项通过。
- 最终后端 `./tools/verify backend`：1010 项通过，216.78 秒，包含新增 44 项；
  Ruff、格式、mypy（121 个文件）、既有警告预算和 OpenAPI 检查通过。
- Android 初轮定向 OneTimeSyncContractTest：MI 6 / Android 15 真机 6 组通过；
  随后追加同版本历史分叉和非法本地意图断言，最终结果以完整回归为准。
- 共享样例包含 8 个权威合并、12 个全历史重建、11 个待确认链、9 个非法证明，
  以及显式请求和 4 个合法证明；两端均消费固定预期，不从实现反生成结果。
- Android 最终 `./tools/verify android`：MI 6 / Android 15 真机 774 项通过，
  零失败/错误/跳过；测试 XML 791.851 秒，完整入口 13 分 44 秒。
  构建、lint、覆盖率校准与编译警告预算通过；lint 沿用无新增问题的缓存结果
  （既有基线 226 errors / 4 hints），编译警告 0。逐例日志未检出 SQLite 连接泄漏、
  文件打开时删除、关闭后访问或 FATAL EXCEPTION。

未运行模拟器、JVM/Robolectric、本机 Docker、NAS、外网或人工 UI 验收；隔离 testbed 不覆盖正式 App。
当前批次仅前向契约与纯规则，回滚不涉及数据库；#181/#160 尚未完成，不能据此宣称事项功能已上线。

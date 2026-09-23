# #188 一次性事项持久化基础审查

## 当前范围

在原 activity_details / activity_events 上增加状态和事件预条件，不建立平行任务表。
迁移保留全部旧列和记录，全 null 明确表示 v5 未初始化，不能以旧图标猜测事项完成状态。
账户限定条件更新、事件预条件捕获和不可变事实证明已可执行；**当前 v4 路由仍未调用这些原语**。
实际 mutation、operation replay、日志、HTTP 提交边界和 bootstrap 接入尚未完成，不关闭 #188。

## 存储与回滚

投影 version/head/completion 同时参与 CAS，SQL WHERE 包含本账户未删除节点；读取具体列避免 ORM
旧 identity map 导致错误冲突上下文。新事实身份检查整个本账户历史，并由账户限定唯一约束兜底。
捕获预条件后不能改写已持久事件。state_after 来自该事件自身预条件，不读取最新事项状态。

SQLite 追加列时附加命名 CHECK，避免重建被外键引用的父表或关闭 FK；迁移定义独立冻结，不导入
以后可能改变的模型常量。约束涵盖三字段完整性、版本范围/交替、事项类型与事件动作/撤销要求；
完整领域、身份和权限校验仍在服务层，不能将 SQL CHECK 称为所有业务规则证明。
参考 [SQLite ADD COLUMN](https://www.sqlite.org/lang_altertable.html#alter_table_add_column)
与 [Alembic add_column](https://alembic.sqlalchemy.org/en/latest/ops.html#alembic.operations.Operations.add_column)。

未使用新字段时可以撤回迁移；有新状态或事件预条件时明确拒绝降级。没有执行部署或正式库迁移。
旧逻辑归档测试改为按其声明的 schema 恢复，原 fixture 字节、身份、校验和和回滚断言均保留；
没有伪造新版本归档或放松 schema 匹配。新增迁移测试使用该冻结样本恢复后升级，并逐表逐列核对旧数据。

## 当前证据

- 21 个新回归：10 个迁移测试、11 个真实迁移 SQLite 存储测试。
- 升级和模型约束一致；晚期 DDL 注入失败回滚全部新列，原版本及旧行不变，之后可重试。
- 完成—跨日撤销—再完成跨会话恢复，历史证明保持原版本，ORM 已加载对象随 CAS 更新。
- 后续失败时投影/事实一起回滚；同版本错误 head 拒绝；历史事件 UUID 不能被后续完成复用。
- 两真实快照写连接竞争只接受一个投影，另一个返回可重试 SQLite BUSY；另有受控旧读取探针
  检查数据库 WHERE 确实拒绝旧版本，二者不是同一类证据。
- 未知账户、删除、普通习惯与旧未初始化事项拒绝；两账户同 activity/event UUID 的不同状态和
  事实预条件在逻辑导出/恢复后保持各自归属。
- `./tools/verify root`：22 项通过。`./tools/verify backend`：1051 项通过，224.53 秒；
  Ruff/格式/mypy 127 文件、警告预算与当前 OpenAPI 一致性通过。

存储测试中的事务调用器不替代生产同步 service，也不证明 HTTP 原操作重放已接入；这部分仍在 #188
继续完成。未进行 Android、人工 UI、NAS、外网或本机 Docker 验收；本批不修改 APK 或清理部署数据。

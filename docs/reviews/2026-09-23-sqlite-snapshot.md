# #185 SQLite 事务快照审查

## 原因与范围

#160 / #181 的事项历史、校验点及游标必须来自同一事务快照。隔离文件库探针发现旧适配器
的普通读取事务不可重复读取；新增五个测试还证实 SAVEPOINT 释放后的写入及 DDL 可能逃逸外层回滚。
这些测试不等于已复现现有 HTTP bootstrap 丢数据：原设备活动更新可能提前获得写锁，不能以此附带行为
代替明确事务策略。

采用 SQLAlchemy 官方的 [显式 BEGIN 方案](https://docs.sqlalchemy.org/en/20/dialects/sqlite.html#enabling-non-legacy-sqlite-transactional-modes-with-the-sqlite3-or-aiosqlite-driver)：
关闭驱动隐式 BEGIN、连接时在事务外设置原 PRAGMA、SQLAlchemy begin 事件发出 deferred BEGIN。
异步适配器、同步适配器和实际 Alembic 在线入口使用相同策略；Alembic 明确事务性 DDL 并释放引擎。
不改已合并 revision、不改表结构、依赖或协议版本，不更改权限/业务/计时规则。

## 并发与失败

WAL 允许独立写入者在只读快照存续时提交。旧快照写升级可能返回 SQLITE_BUSY_SNAPSHOT；
HTTP 请求会话完整回滚后转为稳定 503/DATABASE_BUSY 与 Retry-After，不在原事务重试 SQL。
只识别真实 SQLite 驱动 BUSY 数字码，不匹配消息，不把所有 OperationalError/约束错误当成繁忙。
客户端沿用 5xx 瞬时失败和原操作身份重试；不新增自动重定基准或删除 outbox。

## 验证

- 旧实现：新增 5 个读取/保存点/DDL 回归全部因目标断言失败；没有修改用例以迎合旧实现。
- 修复后定向：20 项通过，包括真实 HTTP push、timer、bootstrap、pull 的独立连接竞争、
  持有写锁时的失败与重试，以及提交阶段 BUSY 注入。后者不是物理提交竞争的模拟替代。
- 先前生命周期、适配器、Alembic 和 HTTP 提交边界合并定向：47 项通过。
- 最终 `./tools/verify root`：22 项通过；`./tools/verify backend`：986 项通过，219.15 秒。
  Ruff、格式、mypy（121 个文件）、既有警告预算和 OpenAPI 一致性检查全部通过。

全程仅使用临时测试库，没有重启/清除运行中服务，没有更改手机或 Android 文件。
本机不运行 Docker，容器双架构留既有 CI；NAS/外网/人工验收未执行。
本批是外观与事项接入的存储前置，不表示 #181 或 #160 已完成。

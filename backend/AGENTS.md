# Backend Module Rules

- Router 只处理认证、参数和响应；权限、领域约束与事务放在 service/domain 层。
- 每个个人数据查询都从已认证主体推导 owner，不能接受客户端指定 owner 后直接查询。
- 实体、revision、change log、snapshot 和幂等结果必须处于同一事务边界。
- 正常启动不得调用 `create_all()` 修补数据库；所有结构变化使用 Alembic。
- 时间必须是带时区的 UTC 瞬时值；每日归属使用记录携带的有效 IANA 时区。
- SQLite 只使用单应用 worker；数据库特有逻辑限制在存储模块中，为未来数据库替换保留边界。
- API 返回稳定的机器可读错误码，不以异常文本作为客户端协议。
- 锁文件是唯一依赖基线；不得使用未锁定环境生成发布物或声称全量验证通过。
- 运行命令和最低验证矩阵见 `../docs/TESTING.md`。

# DayForge

DayForge 是面向家庭自托管场景的习惯与目标管理系统。Android 是当前主要终端，后端负责账户隔离、同步协调、冲突处理和数据持久化。

当前仓库是从旧的多仓库结构迁移而来的模块化单仓库。现阶段包含：

- `android/`：Android 客户端。
- `backend/`：FastAPI 后端和 SQLite 存储。
- `docs/`：长期有效的架构、同步、开发、测试和部署规范。

## 开始开发

开始任何修改前先阅读 [AGENTS.md](AGENTS.md) 和相关模块内的 `AGENTS.md`。

完整环境与命令见：

- [开发规范](docs/DEVELOPMENT.md)
- [测试规范](docs/TESTING.md)
- [系统架构](docs/ARCHITECTURE.md)
- [Android UI/UX 重构交接与功能基线](docs/DESIGN.md)
- [一次性事项与外观目标契约（分阶段启用）](docs/APPEARANCE_CONTRACT.md)
- [同步协议](docs/SYNC_PROTOCOL.md)
- [部署与恢复](docs/DEPLOYMENT.md)

新仓库的可复现构建与首个生产数据库基线已经建立；这不等同于正式发布，计时真机、外网代理、NAS 和发布镜像仍需按对应 Issue 验收。迁移证据和已知豁免见 [MIGRATION.md](MIGRATION.md)。

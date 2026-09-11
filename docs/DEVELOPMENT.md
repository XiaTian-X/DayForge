# 开发规范

## 分支与任务

- `main` 始终保持可构建和可发布。
- 每项修改对应一个 GitHub Issue。
- 分支格式：`codex/df-<issue>-<slug>`。
- 一个 PR 只处理一个主题；功能、广泛重构和依赖升级不得混合。
- 使用 squash merge，使主分支每个提交对应一个完整任务。

`main` 已启用分支保护：修改必须通过 PR，且 `repository`、`backend`、`android` 三项 CI 均通过；禁止强推和删除分支，并要求线性历史与已解决讨论。当前为单维护者仓库，因此不强制批准数量；如后续增加维护者，再启用至少一人批准。

Issue 至少包含目标、原因、范围、非目标、不变量、API/数据库/同步影响、验收标准和回滚方式。

## 提交信息

使用 Conventional Commits：

```text
feat(sync): add resumable initial pull
fix(android): preserve timer event timezone
test(backend): cover lost-response retry
docs: define database rollback policy
chore(deps): upgrade FastAPI compatibility group
```

提交作者使用仓库级配置。AI 参与情况记录在 PR 描述中，默认不追加大量共同作者 trailer。

## 依赖升级

- Android 版本以 `gradle/libs.versions.toml` 和 Gradle Wrapper 为准。
- 后端版本以 `pyproject.toml` 与锁文件为准。
- 文档不复制具体依赖版本。
- 安全更新优先；常规 patch/minor 定期处理；major、targetSdk、Python 和数据库框架升级必须单独进行。
- 自动更新工具只创建 PR，不自动合并。
- 每次升级查阅官方迁移说明，运行完整测试并比较新增警告。

兼容升级组：Gradle/AGP、Kotlin/KSP/Compose Compiler、FastAPI/Starlette/Pydantic、SQLModel/SQLAlchemy/Alembic。Room、targetSdk 和 Compose BOM 分别单独升级。

## 本地工具发现

后端固定使用 Python 3.12 和锁文件，安装 `uv` 后执行 `uv sync --frozen`。Android 固定使用 JDK 17；根验证脚本优先使用 `JAVA_HOME`，并能识别常见的 Homebrew JDK 安装。不要假定 AI Agent 进程会继承交互式终端的 PATH。

统一验证入口：

```bash
./tools/verify all
```

也可以只验证 `root`、`android` 或 `backend`。

## 公开仓库与本地文件

- 本仓库是公开仓库。真实密钥、令牌、账户、数据库、备份、签名材料和设备配置不得进入 Git。
- 可提交的环境配置只有使用无效占位值的 `.env.example`；实际部署配置保存在未跟踪的 `.env` 中。
- 根 `.gitignore` 是敏感文件和生成物的最低防线，不得使用 `git add --force` 绕过。
- `./tools/verify root` 会检查忽略规则和已跟踪文件名；GitHub CI 还会使用 Gitleaks 扫描每个待合并或推送提交中的秘密内容。
- 如果秘密曾进入提交，即使后续删除也必须先撤销或轮换该秘密，再决定是否清理历史。

## Definition of Done

- 实现符合 Issue 范围和架构不变量。
- 自动化测试覆盖成功路径与关键失败路径。
- 相关模块检查和联合集成检查通过。
- API、数据库或同步变化更新契约和稳定文档。
- 最终 diff 无秘密、生成垃圾、调试地址和无关文件。
- 真机、外网或 NAS 验收若未执行，PR 中明确记录豁免与风险。

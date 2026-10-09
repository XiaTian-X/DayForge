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

Android 构建在项目级 `gradle.properties` 使用 `org.gradle.logging.stacktrace=all` 保留失败异常栈，
沿用普通日志级别，不默认打开 info/debug 或上传 Build Scan。异常栈仅增强定位证据，不改变
构建任务、告警预算、测试范围或期限；缺少底层原因的打包失败不能凭重跑成功认定已修复。
配置依据：[Gradle 异常栈日志](https://docs.gradle.org/current/userguide/logging.html#sec:stacktraces)。

## 依赖升级

- Android 版本以 `gradle/libs.versions.toml` 和 Gradle Wrapper 为准。
- 后端版本以 `pyproject.toml` 与锁文件为准。
- 文档不复制具体依赖版本。
- 安全更新优先；常规 patch/minor 定期处理；major、targetSdk、Python 和数据库框架升级必须单独进行。
- 自动更新工具只创建 PR，不自动合并。
- 每次升级查阅官方迁移说明，运行完整测试并比较新增警告。

兼容升级组：Gradle/AGP、Kotlin/KSP/Compose Compiler、FastAPI/Starlette/Pydantic、SQLModel/SQLAlchemy/Alembic。Room、targetSdk 和 Compose BOM 分别单独升级。

Android 工具链升级须同时核对 KSP 实现、Hilt 插件/处理器/运行与测试库的版本一致性，
以及构建变体中没有重新启用主机测试。Wrapper 的 jar 与启动脚本须同版本生成，
分发包写入官方 SHA256 并核对 jar 校验值；不能只改下载 URL。
新编译器的注解目标、默认方法或 lint 行为变化须逐项审查，保留原业务语义，
重新执行干净构建和完整真机测试，不能沿用前一兼容组的通过结果。

## 本地工具发现

后端固定使用 Python 3.12 和锁文件，安装 `uv` 后执行 `uv sync --frozen`。Android 固定使用 JDK 17；根验证脚本优先使用 `JAVA_HOME`，并能识别常见的 Homebrew JDK 安装。不要假定 AI Agent 进程会继承交互式终端的 PATH。

统一验证入口：

```bash
./tools/verify all
```

日常按 `TESTING.md` 的风险矩阵选择 `root`、`android-build`、`android-targeted` 或相关后端模块；
`android`、`backend` 和 `all` 保留为完整门禁，不对每个小编辑自动调用。

## 开发与验证节奏

- 每批以一条可使用的功能链为交付单位（入口、业务写入、展示及必要同步），先完成该链再集中审查和验证。
  内部支撑层可以作为必要前置步骤，但不得用持续拆分支撑层、重复测试代替正式消费者接入。
- 先列出本批交付内容、受影响入口和验收边界；相关修复合批，不把每个内部小修改拆成一次全量验证。
- 局部 Android 修改执行明确选择的完整真机测试类及必要相邻类，不以 JVM/模拟器替代。
- 数据库、账户、时间和同步修改验证完整的受影响模块矩阵；边界无法界定时扩大范围。
- 纯文档、工具、配置修改使用根工具/实际配置/静态检查，改变执行路径时做定向 smoke。
- 全量联合验证安排在关键跨模块接入、协议激活、阶段收尾或发布前；不在未变源码上反复执行。
- 失败先区分生产缺陷、测试夹具错误和环境问题，再修复根因。仅修正夹具且生产源码未变时，
  重验受影响的完整测试类及确有影响的相邻类，不默认重跑原整套矩阵；原失败仍保留记录。
  若失败暴露共享业务边界的问题，则按实际影响扩大范围，不用定向验证隐藏未解决失败。
- 每次启动较长验证前说明它将验证什么、为什么本批必须执行；已有同一源码和范围的有效结果不重复取得。
  测试工具或性能优化只有在实际阻碍交付、缺少可靠证据或必要验证无法完成时才单独安排，
  不把非阻塞的小问题变成目标之外的长期任务。
- 结果记录精确源码、测试范围、通过/失败和未执行项；定向或部分通过不得标作全量通过。
- 用户操作成为阻碍时停止并给出步骤，收到操作完成确认后继续；不能用等待人工操作的时间反复跑无关测试。

后端静态检查使用开发组中锁定的 [Ruff](https://docs.astral.sh/ruff/linter/)，
可在 `backend/` 运行 `uv run --frozen ruff check --config pyproject.toml .`。
规则集显式配置，新增规则按独立批次推进；不要自动执行不安全修复或删除 pytest fixture／模型注册导入。
显式重导出的写法遵循 [Ruff 导入规则](https://docs.astral.sh/ruff/rules/unused-import/)。
格式化使用同一锁定工具，在 `backend/` 执行 `uv run --frozen ruff format --config pyproject.toml .`；
CI 仅运行 `format --check`，有格式差异即失败。稳定配置使用 88 列、4 空格、双引号、LF，
不启用 preview；编辑器须遵循仓库配置和锁定版本，升级时单独审查格式变化。
类型检查使用开发组锁定的 mypy，在 `backend/` 执行 `uv run --frozen mypy --config-file pyproject.toml`。
配置中的 `files` 覆盖源码、测试、脚本和 Alembic，默认正常跟踪导入，不把模块转换为 `Any` 或全局忽略错误。
这是覆盖所有后端 Python 文件的渐进检查，不是 strict 全注解保证；提高严格程度须独立审查并同步测试和 `TESTING.md`。

## 公开仓库与本地文件

- 本仓库是公开仓库。真实密钥、令牌、账户、数据库、备份、签名材料和设备配置不得进入 Git。
- 可提交的环境配置只有使用无效占位值的 `.env.example`；实际部署配置保存在未跟踪的 `.env` 中。
- 根 `.gitignore` 是敏感文件和生成物的最低防线，不得使用 `git add --force` 绕过。
- `./tools/verify root` 会检查忽略规则和已跟踪文件名；GitHub CI 还会使用 Gitleaks 扫描每个待合并或推送提交中的秘密内容。
- 如果秘密曾进入提交，即使后续删除也必须先撤销或轮换该秘密，再决定是否清理历史。

## Definition of Done

验证超时按具体证据诊断，不把整套作业容量等同于产品性能要求。先检查实际执行进度、单项
耗时、资源泄漏及取消收尾；相同证据且源码未变时避免反复完整重跑。真实挂起/错误优先修复；
正常测试增长导致容量不足时，单独记录实测依据、显式上限及用户确认，再调整整批/作业容量。
不得通过改类名分布、过滤测试、放宽断言或拼接失败结果取得全量通过。纯容量修正先验证工具与
实际任务配置，全量门禁在下一关键集成/阶段收尾执行。显式定向验证仅代表所选完整类/模块。

- 实现符合 Issue 范围和架构不变量。
- 自动化测试覆盖成功路径与关键失败路径。
- 与改动风险相称的相关模块检查通过；需要的联合/全量检查在对应接入或阶段节点通过，
  未执行项在 PR 和后续任务中明确记录，不能据定向结果宣布完整阶段完成。
- API、数据库或同步变化更新契约和稳定文档。
- 最终 diff 无秘密、生成垃圾、调试地址和无关文件。
- 真机、外网或 NAS 验收若未执行，PR 中明确记录豁免与风险。

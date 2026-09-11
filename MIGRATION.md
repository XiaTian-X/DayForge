# 旧仓库迁移记录

## 来源

迁移日期：2026-09-11。

旧工作区：迁移设备上的本地旧工作区（未纳入新仓库）。

迁移后采用无旧历史的模块化单仓库；旧历史通过独立 bundle 保留。

| 来源 | 最终提交 | tree |
|---|---|---|
| 根仓库 | `2f8686d8e47d58199d5e796229778d2a38933088` | `4c20f8bf45493f92059d67904789388ac4513ecd` |
| Android | `f851c45f46780fbf90d0ed8b447a09d0fcb18c2f` | `3dd02b78900b412a0a2f40083ed020761c137b7a` |
| 后端 | `29993d4fe98b6cdc0b43b94fd265d1becab536c5` | `602da5a5170855c2b9afd8553b2256a5808258a2` |

三个来源均具有本地 annotated tag `legacy-final-2026-09`。工作树外的本地归档包含 `root.bundle`、`android.bundle`、`backend.bundle`，均已通过 `git bundle verify`；校验和记录在归档目录的 `MANIFEST.sha256`。归档路径不写入公开仓库，且归档文件不会上传。

## 导入方式

Android 和后端分别从其最终 tag 使用 `git archive` 导入，因此没有复制旧 `.git`、未跟踪文件或旧子模块结构。新仓库后续删除过期 CI、过程文档和生成物，这些属于明确的迁移清理。

## 迁移前验证证据

- Android：488 项单元测试通过，`lintDebug` 和 `assembleDebug` 成功。
- 后端：205 项 pytest 通过，报告覆盖率 85%。
- SQLite：空数据库 Alembic upgrade、逻辑归档、恢复、epoch 轮换和校验通过。
- 同步验收 fixture：登录、协议 4 设备注册和首次 push 返回 HTTP 200。
- 导入到新目录后再次完成 Android 单测/Lint/构建和后端测试。

## 尚未证明的事项

- 按用户决定，本次迁移不执行新的真机验收；不能将其记录为真机通过。
- 当前计时功能仍可能存在产品问题，本次仓库迁移不负责修复。
- 迁移前的后端验证使用了旧虚拟环境，暴露出原依赖清单不可复现；新仓库的修正与结果记录在下节。
- 外网代理、真实 NAS、`amd64`/`arm64` 镜像和 Android release 签名尚未完成新仓库验收。

## 新仓库可复现性复核

- Python 3.12.14 和 `uv.lock` 全新环境：205 项测试通过，覆盖率报告为 65%。
- 复核发现旧依赖清单遗漏 SQLAlchemy 异步运行所需的 `greenlet`；新依赖通过 `sqlalchemy[asyncio]` 明确声明。
- Android 删除 20 个无断言的占位测试及本地 Glance 仓库，并切换官方 Gradle、Google Maven 和 Maven Central 后，468 项有效单元测试、Lint 和 Debug 构建成功。
- 本设备按用户要求不执行 Docker 镜像、Compose 或多架构验收。
- GitHub 仓库公开前已对工作树和可达历史执行 Gitleaks 8.30.1 扫描，结果为零告警；仓库已启用秘密扫描、推送保护和依赖漏洞告警。
- GitHub 仓库仅允许 squash merge 并在合并后删除分支；`main` 强制 PR、三项 CI、线性历史、已解决讨论，并禁止强推和删除。

## 首个生产数据库基线

- 在确认没有真实数据和受支持旧客户端后，Android Room 的开发历史 3–24 被替换为 schema `1`；对新旧导出 JSON 去除版本号和 identity hash 后逐项比较，表、字段、索引和外键完全一致。
- 后端 6 段开发期 Alembic 历史被替换为由最终 SQLModel metadata 生成的 revision `000000000001`；空库升级后执行 `alembic check`，并验证同步序列不会在清空表后复用。
- 旧 Android 测试数据通过高版本到 schema `1` 的 downgrade fallback 重建；旧后端 SQLite 文件必须删除后重建，不能 stamp、恢复或继续使用。
- 本次只完成本地 JVM、构建和后端自动化验证；按用户决定未执行 Android 真机或 instrumentation 验收，Docker/NAS 验证亦未执行。

本文件只保留到新仓库首个稳定基线完成。之后迁移证据应放入对应 GitHub Release，文件可删除或归档。

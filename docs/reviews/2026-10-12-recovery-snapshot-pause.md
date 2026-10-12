# 跨代次快照批次进展

## 恢复开发后的状态

2026-10-12 用户允许在依赖链未满足合并门槛时继续原目标。本批仍在
`codex/df-160-recovery-snapshot`，没有新增 PR，也没有把内部步骤算作主分支交付。
已修复暂停时的指标关联字段，并补齐指标记录必填单位；生产校验和业务规则不变。

新增 `NextReplicaCandidateValidator` 在独立内存 Room17 中复用现有结构、完整事项历史、
普通事实、计数日、完整计时事实和挑战元数据事务参与者。冻结/重解码完整响应，核验账户、
服务器、新 epoch、原设备/installation、权限和认证代次，验证结束后关闭候选数据库。
返回不可修改的完整服务端内容集合和本次响应摘要；运行/暂停计时只作为阻碍证据。
不替换活动 provider、不持久保存凭据、不发布新权限/游标、不 ACK 或重绑原请求。
候选通过不是切换许可，也不是旧副本完整性证明。

### 本批实际验证

- 后端完整新增模块最初 44/46：关联字段修正后，另两个失败暴露缺少必填 `unit`。
  修正后 46/46、15.80 秒；最终新模块及真实产品恢复模块合批 48/48、61.90 秒，
  warning 1/1。使用锁定环境、warning 插件和 `--no-cov`，不称为后端全量或覆盖率校准。
- 锁定 Ruff 检查及格式检查通过，mypy 252 文件通过，两份 OpenAPI `--check` 通过。
- Android 新构造参数的默认注解位置告警使 `296a2870039b493c830cad9329e0ac1c`
  在行为执行前失败；改用明确的 `@param:ApplicationContext`，没有提高预算。
- 授权 MI6/API35，五完整类实际 84 项：83 通过、1 新事项夹具同名失败，0 error/skipped，
  XML 112.207 秒；`01bd9c6a1b434f52b5b96c11307172a5`。HTTP43、预览HTTP8、
  challenge契约13、Runtime14全部通过；新增候选类5通过1失败。仅修夹具独立名称，
  不改变名称唯一性规则，不回填或称该运行全绿。
- 最终只重验完整新增候选类 6/6，0 failure/error/skipped；
  `5dd043c2b2a644f39da1b82b1b3afe4d/targeted.json` 为 `targeted_complete`。
  build20秒、connected43秒、XML14.119秒。活动库整文件字节、全部偏好、捕获权限和
  provider不变；验证完成事项、未结束服务端计时、损坏结构/关联、调用方可变集合和错范围。
  构建/lint/预算、发现/XML/APK/新覆盖输入及测试包最终清理通过；不拼称最终84项全绿。

真实恢复模块在实际停止/重开 Uvicorn、物理备份及逻辑归档恢复后，通过 JWT/API Token
读取新预览，核对三账户隔离、全部基础习惯/事项/指标/关联、两种完整跨日计时和完整挑战历史。
每次预览前后实际 SQLite dump 相等，返回内容与原完整快照一致，旧 JWT 失效。
这是后端真实恢复链加 Android 独立候选/HTTP 验证，尚不是 Android 直接连接恢复后服务器的
正式恢复确认联合或人工验收。

### 下一批及合并门槛

1. 完整旧副本内容与 SQL 类型/schema/列/约束核验，新增/改变/缺失差异和同数量内容变化。
   特别核对已 ACK 但备份缺失的数据；响应摘要含 server_time，不能直接充当远端不变票据。
2. 账户私有旧副本档案、素材闭包、持久阶段日志和全部写入/发布栅栏；保存/校验失败不动旧副本。
3. 显式确认、Room COMMIT 与 DataStore 发布的受控向前恢复，以及实际冷启动/Worker/widget接入。
4. Android 与真实恢复后端必要联合、完整受影响回归；人工验收前停止并给步骤。

以上完整链闭合、审查和精确头门禁满足后再按依赖整合；不为减少 PR 数量强行合并未闭合入口。
本批不操作正式数据、正式 APK、部署、默认 v5、Docker 或极端容量。

## 整合审计时的暂停记录

2026-10-12 用户要求先暂停 #160 新增功能开发，进行 PR 分支及 worktree 整合审计。
本批保存为 `codex/df-160-recovery-snapshot` 上的未交付检查点，父提交
`73c84a931f4ba1280bc8d7a10bba0aa5532ac7be`。不进入整合候选，不作为功能完成证明。

### 暂停时已保存实现

- 固定只读 recovery-preview GET、无 API Token 使用时间写入的认证、当前账户及注册设备证明。
- 复用 bootstrap 的纯投影，保持普通同步设备活动时间和游标写入；快照同时包含未结束计时。
- Android 独立新 epoch GET，仍核验旧本地认证代次，禁止自动 refresh 和正常上传改绑。
- 两份生成 OpenAPI、规划增补及完整新测试模块源码。

完整差异预览、隔离候选业务验证、素材证明、私有档案、持久阶段日志、全部写入栅栏和活动
切换尚未实现；没有正式恢复页面，不调用此入口来发布新身份或同步成功。

### 暂停时验证和未决问题

初次新后端模块42项：34通过、8失败，14.32秒。失败是新测试未列入项目既有 HTTP400
拒绝契约；已修正为精确状态码断言，没有改变接口拒绝行为。
首次静态检查发现新测试 wire 类型注解不适合重复头/query 夹具，修正后锁定 Ruff 通过，
mypy252文件通过。一次 pytest 直接入口因无法加载 tests.warning_budget 在收集前失败，
改为项目既定 `uv run --frozen python -m pytest`，没有忽略告警预算。

最终运行八个完整受影响后端模块326项：324通过、2失败、告警1/1，194.57秒。
两个失败均为新 `test_all_fact_kinds_and_once_proof_are_preserved` 的 JWT/API Token 变体；
关联夹具使用不存在的 `required_on_completion` 字段，真实 API 拒绝了该操作。源码原样保存，
待恢复本批时改为实际契约字段并重新验证完整新增模块；不将部分结果拼接为全绿。

Android 证据目录 `android/app/build/reports/dayforge-device/0f5468e551534ca9851c151cd6859701`：
root62项通过；构建及 lint 成功，build3分50秒。选择的相邻契约测试类包名写错为
`com.dayforge.domain.model.ChallengeSyncContractTest`，实际是 `com.dayforge.data.api.dto`；
真实发现阶段报 ClassNotFoundException，未进入行为执行，也没有成功 targeted.json。
发现日志中的 log=true 事件不算测试通过。testbed 两包卸载均 Success，运行进程已结束。
恢复本批须使用正确完整类名进行定向验证；目前新 transport 只有构建通过，不是行为通过。

没有正式数据操作、正式 APK 安装、部署、默认 v5 启用、Docker、极端容量或人工验收。
审计期间不继续完善此新增功能，也不把该检查点合并到 main。

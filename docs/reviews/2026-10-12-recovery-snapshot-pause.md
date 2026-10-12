# 跨代次快照批次暂停记录

2026-10-12 用户要求先暂停 #160 新增功能开发，进行 PR 分支及 worktree 整合审计。
本批保存为 `codex/df-160-recovery-snapshot` 上的未交付检查点，父提交
`73c84a931f4ba1280bc8d7a10bba0aa5532ac7be`。不进入整合候选，不作为功能完成证明。

## 已保存实现

- 固定只读 recovery-preview GET、无 API Token 使用时间写入的认证、当前账户及注册设备证明。
- 复用 bootstrap 的纯投影，保持普通同步设备活动时间和游标写入；快照同时包含未结束计时。
- Android 独立新 epoch GET，仍核验旧本地认证代次，禁止自动 refresh 和正常上传改绑。
- 两份生成 OpenAPI、规划增补及完整新测试模块源码。

完整差异预览、隔离候选业务验证、素材证明、私有档案、持久阶段日志、全部写入栅栏和活动
切换尚未实现；没有正式恢复页面，不调用此入口来发布新身份或同步成功。

## 实际验证和未决问题

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

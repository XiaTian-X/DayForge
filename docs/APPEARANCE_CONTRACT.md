# 一次性事项与外观契约

状态：2026-09-23 冻结产品边界；#181 分批落实可执行契约。**当前运行协议仍为 v4**。
本文的目标行为不能当作已经上线的功能。入口与原能力清单见 [DESIGN](DESIGN.md)，有效决策见
[DECISIONS](DECISIONS.md)。临时进度只维护在 [#160](https://github.com/XiaTian-X/DayForge/issues/160)
及其关联 Issue/PR，不另建重复路线文档。

## 1. 一次性事项

复用 activity、check_in/revert、账户、目标归属、指标关联与同步链路，不建立平行任务表系统。
Android 明确持久化 `completion_policy`，不再由图标、目标次数或失败模式猜测。

- `recurring`：现有三类习惯及正倒模式，保持原有计划/统计规则。
- `one_and_done`：第一版仅 `tracking_mode=check`、`recurrence_rule.type=once`、
  `target_value=1`、非倒数、宽松失败策略；没有每日达标次数、连续天数或长期达标弹窗。
- 事项可独立或归属一个顶层目标；不支持事项作为父节点。
- 完成后保留，允许跨日撤销和再次完成，不自动删除，不自动判定父目标成功。
- 完成与停用/归档是两个维度。`status` 仍表达 active/archived，不把完成写入结构 status。
- 有事实后禁止习惯/事项互相转换；新建和无事实编辑也必须显式选择，不能随图标切换。
  计时会话也视为历史（包括运行、暂停、取消和已完成），软删除事实不解除限制；
  结构写入拒绝代码为 `COMPLETION_POLICY_LOCKED`。允许转换时，事项初始化 0/null/null，
  转回普通习惯清除未使用的投影，不能清除已发生的完成/撤销链。
- 保留轻量创建入口和完成/未完成可发现性；不增加优先级、子清单、分派或重复任务。
  事项不进入习惯成功率/连续天数分母；统计、筛选、目标子项、平板和详情必须共同验证。
- `once.due_date` 只表示可选日期，不建立到期自动失败或每日提醒；本阶段不增加提醒调度。

### 1.1 事实与投影

每个事项的只读投影为 `OneTimeState`：

| 字段 | 定义 |
|---|---|
| `version` | 0 起始的非负 32 位整数；每次有效完成/撤销 +1，与结构 revision 分开 |
| `head_event_uuid` | 最近一次有效状态转换事实，初始 null |
| `completion_event_uuid` | 当前有效完成事实；未完成 null |

初始状态三字段为 `0/null/null`。完成投影的 head 等于 completion；撤销的 head 是撤销事实，
completion 为 null。合法链严格交替，奇数版本完成、偶数版本未完成。投影可由有序事实重建；
不能接受结构 upsert 直接指定完成状态。数据库应在同一事务写事实、投影、revision、snapshot、
change log 与幂等结果；客户端同理写本地事实、投影和 outbox。

状态转换预条件为 `OneTimeIntent`：新 `event_uuid`、`action=complete|undo`、
`expected_version`、`expected_head_event_uuid`、`reverts_event_uuid`。
complete 的 reverts 必为 null；undo 必须指向当前 completion。版本和 head 必须同时匹配，
防止两条离线分支碰巧到达相同数字版本后互相覆盖。公开 ID 使用标准小写 UUID 文本。
时间仍由事实捕获 UTC/IANA/业务日期；不使用时间戳决定哪个意图覆盖哪个，也不以“今天”查完成状态。

实现接入时，这些预条件放在一次性 activity_event 的专用字段，不能塞进不受校验的 metadata。
event UUID 仍是同步实体 UUID；外围稳定 operation ID 负责重放。事件类型仍为 check_in/revert，
action 必须与类型一致。活动 UUID、归属、时间校验和来源规则沿用既有事实契约。
普通习惯不得携带一次性状态预条件；一次性事项不得绕开预条件使用旧事件写入口。

处理顺序：认证/设备事实权限 → 本账户操作重放 → 本账户事项及删除检查 → 预条件及转换 →
条件写入投影与事实。CAS 必须在持久化处再次约束 version/head；纯 reducer 不能防止数据库竞争。
事件 UUID 也须检查整个历史的账户限定唯一性；不能仅检查是否与当前 head 相同。
使用数据库无关事务/条件 UPDATE/唯一约束，不能把 SQLite 单 worker 当作一致性证明。

| 情况 | 结果 |
|---|---|
| 原 operation ID、原内容重试 | 原结果重放；不再次执行转换 |
| 相同 operation ID 不同内容 | `OPERATION_ID_REUSED` |
| 非初始事项却使用初始预条件，或 head/version 任一不符 | `TASK_STATE_CONFLICT`，附本账户权威投影 |
| 已完成又完成（预条件匹配） | `TASK_ALREADY_COMPLETED` |
| 撤销并非当前完成 | `TASK_COMPLETION_MISMATCH` |
| 版本达到整数上限 | `TASK_STATE_EXHAUSTED`，不回绕 |
| 事项已删除 | `ENTITY_DELETED`，不恢复墓碑 |
| 缺少预条件、字段矛盾/未知、非法类型 | `INVALID_PAYLOAD`，零写入 |

离线完成→撤销→再次完成保留三个独立事实、三个 operation ID 和因果链，顺序重放。
前驱被拒绝后隔离依赖后继，不把它们自动重定基准为服务端新状态，也不静默丢弃。
冲突页展示本地意图和服务端状态；用户确认新意图才创建新 operation/event ID。
已冻结请求体绝不就地改写。删除事项/目标的现有 cascade/detach 语义保持不变。

事实权限设备可完成/撤销，但不可借完成自动删除结构。旧 v4 的“已完成任务可由事实设备删除”
例外只在新协议切换后移除；不能先删后端例外却让旧 Android 继续自动删除。

### 1.2 指标

提示身份绑定该次 completion event，而不是事项 ID 或当日日期。
完成先持久保存，取消提示不撤销完成；保存失败保留输入和重试身份。
指标事实使用稳定 UUID 防重复，正常撤销事项不删除真实指标值。
远端完成冲突也不能清理已记录的指标事实；关联已删除时明确提示，不虚构成功。
完成后重启/同步只恢复待处理提示，不能反复创建相同指标记录。

### 1.3 不可变事实传输与投影

纯转换通过不代表 pull/bootstrap 已能正确恢复状态。新 activity_event 写载荷使用
`one_time` 携带 OneTimeIntent；一次性事项必填，普通习惯必须省略/null。存储的 completion_policy
决定规则，不能信任客户端自行指定类别。事件实体 UUID 必须等于 intent.event_uuid，
check_in 对应 complete，revert 对应 undo，根 reverts_event_uuid 必须与 intent 一致。
UTC、IANA/业务日、来源设备及自动化 external_event_id 规则沿用原事实校验；
one_time 不能藏在 metadata 中，也不能携带计数或计时结果来绕过状态机。

被接受的事项事实快照新增只读 `one_time_state_after`，和该事实的 one_time 一起不可变保存。
请求不能提交 state_after；写入成功响应、重放、pull 和 bootstrap 均返回该事实当时的值，
不是读取时事项的最新状态。`OneTimeEventProof` 校验完整快照中的六项关联字段：
public_id、activity_uuid、event_type、reverts_event_uuid、one_time、one_time_state_after；
它不是替代整份事实 DTO 的新接口，时间/来源等其他快照字段仍然存在。
验证 state_after 必须恰好是该 intent 的一次合法转换结果，不能只看数字版本或奇偶性。
不能给同一个 plan_node revision 动态拼上不同完成状态，破坏快照一致性。
结构编辑 revision 与事项状态 version 独立，改名称/图标不使合法完成意图过期。
响应丢失重放可能返回旧的成功投影；客户端不得因此让更高版本权威状态倒退。
全量恢复、分页、重复/乱序变化和本地未确认意图都须验证；已确认基准与本地乐观链分开，
不能用一次 pull 直接覆盖待确认的完成/撤销。目标子项与小组件使用事项全局完成投影，
普通习惯继续使用原来的当日/周期判定；两者不能共享一个只查今日记录的快捷判断。

权威状态合并限定在同一已认证账户/服务器 epoch/事项内：低版本只存事实、不回退投影；
同版本同状态无操作，同版本不同 head/completion 报 `TASK_STATE_DIVERGED` 并中止该合并事务；
高版本采用已验证服务端投影。删除优先，迟到事实不能复活父事项。
分页面可先见较新事实；全量 bootstrap 单独返回 `one_time_checkpoints`（每项为
`{activity_uuid,state}`），与 next_cursor/全部事实来自同一数据库一致快照。
每个可见事项必须恰有一个校验点，未完成且无历史的事项也显式返回 0/null/null；
缺失、重复、跨账户、引用普通习惯或不可见事项的校验点均不能用于恢复。
它不属于 plan_node 的结构快照，也不单独制造结构 revision。
激活全量恢复前必须完整校验该事项从 1 起的因果链，且结果与校验点完全相等：
缺段 `TASK_HISTORY_INCOMPLETE`、重复事件 `TASK_EVENT_ID_REUSED`、跨事项 `TASK_ACTIVITY_MISMATCH`，
同版本分叉或错误因果 head 也必须拒绝。独立校验点用于发现整条链或末尾整段缺失，
仅检查已收到事件之间连续并不能证明完整；不能把半份历史当成完整恢复。

新事项事实未成功创建时没有可返回的 activity_event 实体。拒绝响应增加独立
`one_time_conflict={activity_uuid,state}`，只提供本账户事项权威状态，外层保留原 operation_id/
entity_uuid/error_code。不能把这个状态伪装成成功事件 entity，也不使用事项 version 替代事件 revision。
没有事项访问权限时不返回该字段；墓碑仍由既有结构删除通道传输，不暴露其他账户状态。
前向 `NextSyncOperationResult` 对上述四种 TASK 状态错误要求独立冲突字段，且禁止同时返回
entity/revision/base_entity/local_entity；客户端还须绑定原 operation/entity/activity 身份。
成功/重放成功响应须携带正确事项的完整不可变证明，且 one_time 与原提交 intent 完全一致；
不能只凭相同操作 ID 确认别的事项、另一个合法意图或没有事实证明的成功状态。
新同步外壳和 [v5 增量 OpenAPI](../contracts/next/openapi.json) 已由共享正反例约束，
尚未将这些字段接入当前 v4 路由。Android 新字段校验不替代现有完整业务映射和账户仓库校验。
请求外壳不提前验证整批领域载荷；在认证/幂等重放之后逐操作校验，沿用逐项拒绝结果。
一个非法事项载荷不能使同批其他合法操作变成整批 HTTP 422，或阻止原操作结果重放。

### 1.4 本地未确认操作链

`projectPendingOneTime` / `project_pending_one_time` 只计算显示投影，不确认、删除、重写或
重新定基任何 outbox 条目。输入是同账户/epoch/事项的确认基准、按因果顺序的稳定 operation ID
和 intent，以及可选已持久化的明确拒绝身份；身份重复、跳过依赖或非法链报告 INVALID_PENDING_CHAIN。

- 基准吻合时顺序计算本地完成→撤销→再次完成的乐观状态。
- 基准已改变且操作结果未知时，保留原链并列入 awaiting_replay_operation_ids；
  可能只是成功响应丢失，不能据此制造冲突。仍用原 ID 和原请求体顺序询问服务器重放结果。
- 只有明确拒绝才将该操作及因果后继列入 blocked_operation_ids；更早待确认操作仍可原样重试。
  前驱未确认或已拒绝时不继续发送依赖它的后继。
- 确认前驱后，在同一本地事务更新权威基准、事实和 outbox，再重新计算剩余链；
  不能凭时间戳或 UUID 看似相同就当成 operation 成功。

指标事实与输入草稿不因这些队列分类而删除；账户切换、epoch 更新、墓碑及数据库 CAS
仍由接入层在事务中验证。纯函数没有账户授权能力，不取代真实存储/HTTP 竞争和恢复测试。

## 2. 图标引用与包

两种引用，不持久化 Android resource ID/ImageVector/文件绝对路径：

- 角色：`{"kind":"role","role":"habit.water"}`。切换图标包时重新解析。
- 固定素材：`{"kind":"asset","asset_id":"<小写 UUID>"}`。切换风格不替换。

role 符合 `(habit|metric|goal|task).[a-z][a-z0-9_]{0,47}`，命名空间用于目录分类；
非内置角色允许存在，缺少映射不能改写为 health。`task.*` 为事项专属；
素材声明 `purpose=task|general`，普通习惯、目标和指标不能选 task 素材，事项只能选 task 素材。
服务端写入时也校验用途和本账户素材归属。缺失元数据是 unresolved，不等同于授权成功；
存在已授权元数据但尚未下载字节时允许保存引用。用途限制不承诺判断任意图片的视觉含义。
返回/删除/同步/开始/暂停等操作图标固定，不受内容包影响。

`IconPack` 的精确字段由 [共享样例](../contracts/next/icon-pack.json) 与双端模型约束：
`format=dayforge.icon-pack`、`format_version=1`、`pack_id`、正整数 `revision`、name、
assets、roles、nullable placeholder_asset_id。一个 `(账户,pack_id,revision)` 的内容不可变；
冲突同版本内容拒绝安装，新版本须预览且完整校验后切换。名称/revision/hash 不证明发布者身份。

每个 `IconAsset` 具有不可变 asset_id、name、purpose、`color_mode=template|original`、
必需 light 与 nullable dark blob。每个 blob 描述 sha256、小整数 byte_length、media_type、
width/height；相同 hash 的描述必须完全一致。更改内容/用途/着色方式必须创建新 asset_id，
不覆盖正在被对象或离线请求引用的版本。

template 根据 alpha 模板统一着色，忽略素材 RGB；可选主题强调色或对象强调色。
original 保留原色与 alpha，不反色、不自动按扩展名猜着色能力；缺少 dark 时使用 light。
第一版不做多色槽位 SVG。未知/缺图保留原引用：当前包通用 placeholder → 简单几何/文本；
纯导入模式禁止悄悄回退 Material 内容图标。占位仅表示缺图，不成为事项的可选 general 图标。

### 2.1 文件与解码边界

离线包为 ZIP，根 `manifest.json`，字节仅在 `blobs/<sha256>`；文件名来自已验证 hash，不来自 name。
同一字节可被多个资产声明引用；必须列出且校验全部必需字节，禁止额外条目。

| 预算 | 上限（包含边界） |
|---|---|
| 单个 SVG / PNG | 512 KiB / 2 MiB |
| 解码尺寸 | 每边 1–1024px；最多 1,048,576 像素 |
| 单包 | 128 素材、256 角色、32 MiB 压缩输入、64 MiB 去重素材字节；另计最多 1 MiB 清单 |
| manifest | 1 MiB UTF-8；JSON 深度 16 |
| SVG | 元素 2048、深度 16、path 命令 16384 |
| 默认账户素材配额 | 256 MiB、1000 素材（含待提交上传的预留）；可由管理员降低 |
| 默认账户元数据配额 | 8 MiB 规范 JSON（独立计入素材声明和所有包版本）；可由管理员降低 |

必须边读边计数，不相信 ZIP 头、MIME 或声明尺寸；拒绝重复 JSON 键/ZIP 条目、路径穿越、
绝对路径、链接、加密条目、额外文件和不支持版本。预览前验证签名、实际尺寸、hash 与长度。
拒绝动画 PNG；PNG 重新解码为受限位图，不能把不可信元数据作为可执行输入。
SVG 只允许静态 svg/g/path/rect/circle/ellipse/line/polyline/polygon、明确画布及数值属性；
禁止 DTD/实体、script、事件属性、image/use/text/font、动画、外部引用、CSS、filter、渐变和 URL。
未知元素/属性拒绝，不“删掉危险部分后当成功”。透明/单色与原色都仍受同样安全约束。
完整 XML/path 数值语法、安全解码器及压缩总量实测是安装功能上线门槛，元数据模型不是安全沙箱。
精确 SVG 属性、数值、路径与复杂度白名单见 [静态 SVG profile](SVG_PROFILE.md)。
Android SVG 绘制使用同一次严格解析生成的受限几何，验证 viewBox/矩阵、推导坐标与虚线工作预算，
实际测试原色/alpha 和整图模板着色；后端验证相同静态条件，但不声称执行 SVG 像素栅格化。
这一图片原语不代表 UI/安装已经接入，也不会将元数据自动标记为就绪。
PNG 完整块、压缩流和实际像素解码的预算见 [PNG profile](PNG_PROFILE.md)；
两种格式的校验/解码均不代替受限输入读取、授权、安装恢复和字节备份闭包。

文件暂存、fsync、同目录原子 rename、数据库安装日志配合恢复；不能将文件 move 与数据库事务
宣称为跨存储原子提交。取消或校验失败保留旧活动包；重启清理仅触及该次暂存和未提交安装。
后端账户文件状态机、目录与备份闭包约束见 [账户素材文件协议](ASSET_STORAGE.md)，由 #202 实现；
该设计不表示当前已开放上传或安装。

共享阻塞流读取器按已验证 blob 的长度分段读取，最多消费声明长度加 1 字节并核对 SHA-256。
字节错误为 IMAGE_BYTE_LENGTH / IMAGE_HASH，违反读取接口约定为 IMAGE_READ_INVALID；
I/O 故障和取消不包装成格式错误或成功 EOF。来源由调用方关闭，并提供调度、超时与取消。
返回冻结字节仍须经过格式检查/实际解码；不能重开来源、仅凭 hash 标记就绪，或把这一原语
表述为已经实现账户 HTTP 上传/下载、URI 导入、ZIP 解压或持久安装。

### 2.2 账户素材 API

下列是协调启用 v5 时的接口，不是当前已部署路由。请求/响应模型与有效、无效和错配响应样例
见 [api.json](../contracts/next/api.json)；OpenAPI 由 `backend/scripts/export_next_openapi.py`
生成，后端测试检查其可重现性与全部 schema 引用。它是 v5 增量契约，不替代未改变的认证/计时接口。

| 路径（共同前缀 `/api/v2/appearance`） | 操作与结果 |
|---|---|
| `/assets/{asset_id}` | PUT 声明不可变素材并预留配额；GET 返回元数据及 advisory ready_variants |
| `/packs/{pack_id}/versions/{revision}` | PUT 声明不可变包版本；GET 返回精确版本 |
| `/assets/{asset_id}/content/{light或dark}` | PUT 上传原始字节并返回精确 blob 收据；GET 下载已验证字节 |
| `/catalog` | GET 账户不可变元数据目录，按 sequence 递增分页 |
| `/quota` | GET 账户额度与预留量，含未完成上传 |

账户从认证主体推导，不接受 owner 字段；所有接口检查本账户有效设备、server_instance_id 和
sync_epoch。声明的 context 放请求体，读取/字节操作放查询参数；路径身份必须与声明完全相同。
写操作需要 `structure.write`，所有读操作需要 `sync.read`；事实设备不能借素材声明取得结构权限。
同账户相同 ID/规范元数据重试返回同一身份；改写旧素材或包版本返回 409，不分配第二份额度。
包内每个描述必须与本账户已声明素材完全一致，不能借包导入他人素材。配额按账户原子预留：
字节按本账户不同 hash 去重、素材按不同 ID、元数据按规范 JSON 的 UTF-8 字节；新增包版本
也占元数据额度，避免无限版本绕过素材数量上限。管理员降低限额不删除旧素材，不拒绝精确重试。
不可变比较和元数据计费仅针对规范化后的 asset/pack 值，不包含随请求设备变化的 context 外壳。
额度响应允许已有使用量大于新限额，只拒绝会增加超额资源的新声明。

目录是独立于业务同步游标的、追加且不可变的账户元数据序列；第一页面冻结 through_sequence，
后续带回同一 through，after/next_cursor 单调递增。最后页 next_cursor=through，可为空；
中间页必须有记录，next_cursor 为末项且小于 through。页上限 100，序号为非负有符号 64 位整数。
响应必须绑定原账户会话代次、设备、服务器和 epoch；不能将旧账户响应应用到新账户。
ready_variants 不放不可变目录，其变化通过独立 GET 查询。同 hash 的 light/dark 就绪状态一致。
就绪提示不免除下载后的 hash/长度/安全解码验证；下载失败保留引用和队列，不阻塞业务事实。

字节上传先在短事务认证/检查声明，再在无数据库事务的受限暂存中读取并验证第 2.1 节全部规则；
提交前重新检查账户、设备权限、服务器/epoch，按安装日志完成文件与数据库持久化。
不能让认证中的 last_seen 写入/autoflush 将整个网络上传包在 SQLite 写事务里。
不接受客户端路径、远端 URL、压缩 Content-Encoding 或主动重定向下载；首版不提供分块续传，
2 MiB 上限内以原身份完整重试。同身份重试仍须验证字节，不能仅凭声明 hash 返回成功。
下载仅通过本账户 asset_id/variant，返回明确 MIME、`private, no-store` 和 `nosniff`；
不存在 dark 返回 404，界面在本地按声明回退 light，不由服务端隐式替换响应字节。

未声明/非本账户身份统一 404；元数据不可变冲突为 409 `ASSET_ID_REUSED` / `PACK_VERSION_REUSED`；
未上传内容为 409 `ASSET_CONTENT_PENDING`；已标就绪但文件缺失/损坏为 503 `ASSET_CONTENT_UNAVAILABLE`，
不得作为空图成功。格式/路径错配 422，输入/配额超限 413；设备和权限沿用 DEVICE_NOT_FOUND /
DEVICE_CAPABILITY_DENIED；新服务端上下文检查使用 SERVER_IDENTITY_MISMATCH / SYNC_EPOCH_MISMATCH，
不复用“素材不存在”掩盖恢复后的身份变化。账户素材/包不存在分别为 ASSET_NOT_FOUND / PACK_NOT_FOUND，
同账户 hash 描述冲突为 ASSET_BLOB_METADATA_MISMATCH，额度/目录序号耗尽为 ASSET_QUOTA_EXCEEDED；
存储目录或描述损坏为 ASSET_METADATA_CORRUPT，不能作为空结果成功。认证仍由现有 JWT/API Token
依赖处理，权限错误不泄露他人元数据。中断/临时错误保留原 ID 和持久队列并退避重试。
本节的模型只验证值与响应绑定；实际授权、流式限额、安全解码、额度竞争和安装恢复须在接入时验证。

已实现但未挂载在线路由的声明服务在同一调用方事务校验账户设备、能力、服务器身份/epoch，
条件更新 quota 并追加 blob/素材或包/目录。相同内容重放不追加目录，不受后来降低限额影响；
新增声明只检查本次增加的额度维度，不能用已超额的字节维度阻止仅增加元数据的合法包版本。
SQLite 旧快照升级写入失败必须整笔回滚后重试，不在旧事务内循环；恢复检查与声明共用规范 JSON。
目录独立读取冻结的 through，连续完整的条目才允许推进；缺记录不作为正常空末页。
读取设备能力不创建 policy、不更新 last_seen；API Token 认证自身的短事务写入仍由统一依赖管理。
这些服务与测试专用 HTTP bridge 不代表生产 v5 路由或版本门禁已启用。

## 3. 主题、设备偏好与小组件

主题定义与图标包独立；全局主题选择、浅深模式、浅/深主题各自选择、卡片模式和 OLED 为本机偏好，
不随另一设备的同步强制切换。对象强调色与 icon_ref 属账户业务数据，随对象同步。

主题文件新格式使用 `format=dayforge.theme`、`format_version=1`、theme_id、revision、name、
generator_id、seed、light、dark。light/dark 保存完整解析后颜色角色，颜色为不透明 `#RRGGBB`；
生成器版本锁定，升级库不得自动重算已保存主题。每个主题明确提供两套色板，单项编辑也导出完整值。
角色集合由双端 `ThemeRoles` / `MATERIAL_ROLES` 等常量和 [完整样例](../contracts/next/theme.json)
共同冻结，不随 UI 库升级隐式增减。每套色板包含三个精确字段集：

- `material`：36 项，覆盖当前 Material 3 的全部颜色角色，包括 surface bright/dim 和五级
  surface container；不是旧 27 角色的重新命名。完整名称以样例和常量为准。
- `status`：success、warning、pending 各自的主色、on 色、container、on_container，共 12 项；
  error 使用 Material 的四个 error 角色，不另建矛盾副本。
- `chart`：line、target、grid、selection，共 4 项。

缺失和未知角色均拒绝；不从运行时库补默认色。generator_id/seed 要么同时为空，要么同时存在；
未知但语法合法的 generator_id 仅作来源说明，不能执行生成器或改变已保存颜色。
对象强调色另允许 `#RRGGBB` / `#AARRGGBB`，保持现有透明度能力；不接受七位颜色。
拒绝无效颜色，而不是把它悄悄替换为黑色。主题文件导出不再混淆“种子参考模板”和“当前主题”。
不提供旧文件兼容转换；错误提示必须明确版本不支持，不清理旧配置/本地数据。

共享解析输出包含：Material 容器/文字/动作角色、对象强调色、成功/警告/错误/待同步状态色、
图表色、图标 tint 与资源状态、版本标识。实际背景及 alpha 合成后检查可读性；显示层调整
不能回写对象颜色或生成 outbox。普通文字至少 4.5:1，必要图形及适用大字至少 3:1。
原图颜色不可控时使用合适底板/边界，不能假装所有图片本身都满足对比度。

Compose 和 Glance 各有适配器，但使用同一解析结果；Glance 必须在不启动 MainActivity 时加载
主题和账户素材。位图按显示尺寸有界解码，缓存键包含账户/服务器命名空间、素材 hash、主题版本、
模式、tint、尺寸与渲染器版本。不在计时每秒 tick 解码/联网。素材到达或主题改变走既有
WidgetRefreshScheduler 队列；账户切换失效旧缓存/回调，不复用上个账户图片。
真正输出颜色与 ImageProvider 的回归是交付条件，不能只留下空接口。小组件视觉重排另行处理。
参考官方 [Glance 主题](https://developer.android.com/develop/ui/compose/glance/theme) 和
[Glance 构建边界](https://developer.android.com/develop/ui/compose/glance/build-ui)：
Glance 不是 Compose UI，不能直接共用 composable 或假定无 RemoteViews 资源限制。

## 4. 配置包与同步

配置包独立 `format=dayforge.config`、`format_version=2`，不等同于素材包或完整备份。
保留目标/习惯/指标/关联及必要外观依赖，排除事实、计时会话、完成历史、账户、设备、令牌、
revision、墓碑、服务端身份及 outbox。完成事项默认不导出；可显式“作为未完成模板”纳入。
全部事项导入后是未完成新身份，不能复用来源 UUID 修改其他账户或复活旧墓碑。

包内使用局部符号标识并验证唯一性/引用/单父目标，确认后一次生成持久映射；同一次导入失败重试
复用映射和 operation ID，新一次导入重新分配。引用的固定素材与角色解析快照/实际使用的字节
必须随包提供，离线导入不能依赖来源账户下载权限。复制素材到目标账户时重新分配身份，改写
包内引用。不自动应用来源设备的全局主题；主题作为可选安装依赖单独预览。
配置元数据的全部根字段必填：nodes、metrics、links、nullable icon_pack、unresolved_roles、themes，
无内容必须显式空数组/null；缺字段不能被解释成“删除全部”。
[完整样例](../contracts/next/config.json) 和双端 `ConfigBundle` 冻结以下字段：

| 对象 | 保留内容 |
|---|---|
| node | 包内 key、kind、名称/描述、启用状态、单父目标 key、appearance，以及互斥 goal/activity 详情 |
| activity | check/count/duration、completion_policy、正倒模式、目标值/周期、失败模式、preferred_minute、IANA 时区和类型化 schedule |
| goal | 可选 start_date/due_date、目标周期及失败模式；不带目标结果 |
| metric | 名称/描述/单位、启用状态、小数位、average/sum/by_time、目标方向及上下限、appearance |
| link | 局部 key、习惯/事项和指标 key、系数、详情显示/完成提示及启用状态 |
| appearance | role/asset 引用、对象强调色、icon_tint=theme/object；original 素材仍不参与 tint |

schedule 支持 daily、weekly（ISO 周一=1）、monthly、interval 和 once；日期是有效 YYYY-MM-DD。
计时目标以秒存储，须为完整分钟；preferred_minute 是当日 0–1439 分钟的偏好，不建立新的提醒调度。
一次性事项只有 once 计划和单次 check，不带周期目标、严格失败或每日时间偏好。
配置模型保留字段不等于 Android 现有存储已经接入这些字段，尤其不能改变目标窗口既有语义。

上限为 1000 nodes、1000 metrics、5000 links、16 themes、256 unresolved_roles；
内嵌 icon_pack 仍受第 2 节上限约束。所有 node/metric/link 的局部 key 在包内全局唯一，
父节点只能是同包顶层目标，关联端点存在、无重复对，同类对象名称不重复。
固定素材必须包含元数据及字节；角色必须由内嵌包映射，或明确列入 unresolved_roles。
缺失角色不能伪造一个映射；未使用、重复或已有映射的 unresolved 声明均拒绝。
导入预览提示缺失角色并保留原引用，使用既定占位策略。导入安装的角色快照包可供用户选择，
但不自动替换当前设备图标包，也不将角色偷偷固定为素材；角色仍随当前选择的包解析。
主题/包/素材的源 UUID 也只是包内身份，导入目标账户时重新分配并持久保存引用映射。

配置 ZIP 同样只包含 manifest.json 和已声明的 blobs/<sha256>；压缩输入 32 MiB，
去重素材字节 64 MiB。配置清单独立上限为 8 MiB UTF-8（不是图标包的 1 MiB），
JSON 深度仍为 16；单独主题文件上限 1 MiB。数量上限和字节上限必须同时满足。
导出超过预算时明确失败并提示减少范围，不能截断对象或丢弃依赖后生成“成功”文件。

数值、字符串和布尔类型不互相隐式转换；未知字段拒绝。完整 ZIP 字节校验、稳定身份映射、
替换事务及恢复路径仍是新导入器启用门槛，元数据模型不能替代它们。

继续提供替换导入，但确认前展示待删除对象及历史数量，不包装成无损追加。
先把 URI 一次读成受限不可变暂存并校验，确认后不得重新读取可能改变的 URI。
活动计时、未解决冲突、不安全待确认操作或权限不足时阻止替换；在账户协调锁内再次核查。
本地业务替换、身份映射、导入日志和 outbox 同一事务；文件安装先就绪再提交引用。
网络同步不宣称整包原子：顺序删除关联/旧对象，再创建指标/顶层目标/子项/关联，依赖组等待
前驱确认，避免同名新旧冲突。逐项永久拒绝保留导入进度和恢复入口，不谎报整包成功。
原冻结请求不改写；重试/撤销必须显式处理已远端成功部分，不能简单回滚本地数据库假装撤回服务器。

素材元数据先经已认证账户声明，字节上传/下载有独立持久队列，不阻塞打卡与计时事实。
同账户存在合法元数据、字节尚未就绪时引用可以同步，界面显示缺图/下载状态。
禁止任意 URL 拉图；复用已验证服务器身份、网络绑定、认证、退避及注销协调。
下载权限从 asset_id 的账户归属推导，不提供裸 hash 公共下载或跨账户去重查询。
旧回调必须校验账户/服务器及当前引用代次，不覆盖用户后来选择的图标。
卸载包只改变本机选择/目录，不立即物理删除仍被对象、其他包、导出、待同步请求引用的素材。
本期保守保留服务器素材；GC 上线前必须定义离线支持窗口、引用闭包与恢复矩阵，不能按最近访问时间清除。

## 5. 存储、备份与启用

SQLite 继续单 worker；asset 元数据属于数据库，字节在服务端私有受控目录。
数据库变更新增 Alembic/Room 迁移并提交 schema，不重写已合并历史（D-007 不被暗中撤销）。
对象外观预备迁移 `000000000004` 新增 plan_node_appearances / metric_appearances，以对象内部主键
一对一持有显式 role 或固定素材引用、accent_color 和 icon_tint；owner 同时绑定对象及素材。
原主表增加 (owner,id) 唯一索引供复合外键使用，不重建被事实/计时引用的父表，不复制或猜测旧 icon。
存储原语从实际 activity completion_policy 决定 task/general 用途，不接受调用方自行声称“这是事项”。
字节未就绪不阻断已有本账户合法素材引用；角色无须创建账户素材额度。外观写入必须由结构变更的
revision/change/snapshot/幂等事务包裹，原语本身不增加 revision 或修改完成投影。
备份与恢复一并校验对象所有权、固定素材引用和用途；存在任意外观明细时拒绝降级。
原 v4 字段与新明细仍显式隔离。内部 `next_protocol` 路径已将新明细接入结构写入、不可变
snapshot/change、冲突和 bootstrap；所有当前在线路由仍使用 v4，Android 尚未切换。
新结构三方合并把 `appearance.icon` 作为整体，强调色和 tint 分别合并；禁止拆分 role/asset 字段
拼成从未存在的引用。事项投影不加入结构 merge/revision，重命名或换图不改变完成状态。
新 bootstrap 在同一事务验证全量事项历史并返回精确可见检查点；缺失明细或旧未初始化事项
明确失败，不用默认图标或空完成状态伪造成功。读取损坏/缺失明细的错误分别为
`APPEARANCE_STATE_INVALID` / `APPEARANCE_STATE_UNINITIALIZED`。
后端素材预备迁移 `000000000003` 新增账户额度、账户内 hash 描述、不可变素材、包版本和目录五张表，
不自动创建额度行、不改写旧业务或协议版本。素材到 light/dark blob、目录到素材/包版本使用包含
owner 的复合外键，不能以外部主键存在代替账户归属；相同 UUID/hash 可出现在不同账户。
目录使用独立 64 位账户序号，包身份包含 revision。额度降低允许已有使用量高于限额；数据库约束
不取代规范 JSON、严格整数、声明一致性及完整引用校验。存在任意新账户配置或素材数据时拒绝降级。
逻辑归档明确解析标量主身份，不从复合外键集合中随机选引用；恢复后核对全部元数据、目录、
账户限定包引用及去重额度。当前仅接入元数据备份，尚未有字节上传/安装路径；发现非空 ready_at
明确拒绝物理/逻辑备份和恢复，直至完整字节闭包接入，不能将“声明存在”当作图片就绪。
后端事项存储预备迁移 `000000000002` 在 activity_details 增加 one_time_version、
one_time_head_event_uuid、one_time_completion_event_uuid；全 null 表示尚未启用 v5，
不解释为已知无历史。新事项在协调启用后显式写入 0/null/null，普通习惯保持全 null。
activity_events 保存不可变 one_time_expected_version / one_time_expected_head_event_uuid，
action 与撤销目标复用现有事件列。事实的 state_after 从其自身预条件和事件身份精确还原，
不读取事项当前 head，不重复保存可产生矛盾的第二份事件状态。
投影条件更新同时检查账户/未删除父记录与 version/head/completion；调用方仍须在同一事务
写事实、change log、snapshot 和幂等结果。存储原语本身不提供认证或业务成功确认。
迁移只追加可空列和约束，不猜测/转换旧事项。所有新增字段未使用时允许降级；一旦初始化
新状态（包括版本 0）或写入新事实预条件，降级明确停止，不能删除新数据来获得成功。
逻辑归档继续要求精确 Alembic head 匹配；旧归档先恢复到其记录的 schema，再运行增量迁移，
不能改写归档 head 或校验和来强行导入新 schema。

已初始化事项的备份和恢复还须校验完整事实链与持久投影相等，不能仅通过文件 checksum、行数
或外键检查。逻辑导出/导入与物理备份检查共用账户限定的历史校验；缺尾、整段缺失、跨账户撤销
引用、非法版本和已删除事实均拒绝。逻辑导入在同一事务验证，失败不得保留数据或新 epoch。
已删除事项保留的历史也纳入备份检查；未来账户 bootstrap 只返回本账户可见事项的校验点。
读取复用调用方一致快照，不独立连接读取“最新状态”。旧未初始化行在旧备份中可保留，不能
据此初始化 v5；运行协议切换仍须满足空业务基线和版本门禁。临时 WAL/SHM 清理仅限工具自身
创建且已关闭连接的临时副本，不清理运行库的 WAL/SHM。

用户不要求保留当前测试数据，因此不建设依赖旧图标猜测事项的存量分类迁移。
正式切换以双方空业务基线为前提：有旧业务数据时明确停止并提示受控重建，不能自动 destructive fallback。

新字段启用采用协调发布的协议 v5 切换；不宣称旧 v4 客户端理解新事项或素材。
实施前保持 v4 在线路径不变，完成服务端、终端和恢复验证后才提升 advertised/minimum 版本。
维护窗口内确认目标部署、停止旧写入、部署后端与新 APK、验证版本/能力，再允许业务同步。
旧客户端/冻结 v4 outbox 明确报告不兼容，保留原请求供审查，不翻译成新命令、不静默丢弃。
设备注册和每个写入口都必须进入版本门禁，不能只提升信息接口的 advertised 版本；
既有设备若没有可靠版本证明也不能绕过门禁。激活前由真实 HTTP 验证旧注册、旧设备和旧冻结请求均拒绝。
具体边界为：v5 注册显式提交受支持的 protocol_version=5 并由服务端保存；每次 sync/timer/appearance
请求都显式携带 `X-DayForge-Protocol: 5`。缺失/旧值返回升级错误，未来未知值同样不接受，
不能仅凭设备曾注册 v5 就放行无版本请求。注册、服务端元数据和每请求声明三者须一致；
协议标记不替代认证/权限，也不作为可信客户端证明。身份发现和登录维持可访问以提供升级说明。
不得仅清服务端表而保留客户端身份和 outbox；受控重建清单须覆盖服务器实例/epoch 策略、
设备注册、Room/DataStore 队列、素材与缓存，再由用户确认实际目录、包名和数据范围。
本契约没有执行或授权一次具体清除操作。

新备份覆盖数据库与引用闭包内不可变字节，包含版本、大小、hash 清单。
备份期间防止 GC/上传提交导致不一致；可使用维护锁+SQLite 一致快照并验证闭包。
恢复到隔离目标校验所有文件、引用、账户、版本和校验和后再激活；失败不覆盖正在运行的服务。
逻辑归档继续使用账户限定身份（D-010），新增素材集合、外键和格式版本；不能只备份 DB 后声称
自定义图标可恢复。物理和逻辑恢复都须覆盖缺失、损坏、多账户同 ID 及中断。
本机不运行 Docker，容器验证留在既有 CI；不把 CI 构建当成 NAS 人工验收。

回滚代码不等于回滚数据。新业务写入前可撤回新代码；写入后只支持配套备份恢复到匹配版本，
或经另行验证的前向修复，不尝试旧代码读取新库。部署/重建脚本与用户分组验收是上线门槛。

## 6. 可执行范围与后续门槛

`contracts/next/` 当前由双端测试消费：事项纯转换、图标引用/包元数据、完整主题角色和配置包元数据，
以及事项不可变快照证明、单调投影合并、全量因果链、未确认队列投影、新同步外壳与素材 API 值，
包括有效/无效输入和跨设备/epoch/操作/内容错配响应。当前 v4 OpenAPI 必须保持不变。
这证明格式和纯规则一致，不证明授权、真实图像解码、数据库竞争、同步恢复或 UI 已接入。
前向 OpenAPI 标明 NOT ACTIVE，不创建占位路由，不将模型验证称为真实授权/传输验证。
设备版本门禁、领域写入、数据库/文件存储、Android 仓库/UI 和备份接入仍是发布门槛。
之后每批先补契约，再接实际领域/持久化路径并验证，最后移除旧猜测/自动删除/整数图标代码。
所有习惯模式、目标、指标录入、筛选、配置替换、账户隔离、离线和计时完整性均保持回归门槛。

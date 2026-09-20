# 网络、认证会话与计时同步测试审查（#138）

## 范围与独立预期

将八个历史文件的 48 个方法迁入真机：AuthenticationSessionTest（4）、EndpointResolverTest（9）、
NetworkMonitorTest（17）、SelectedNetworkTransportTest（1）、BaseUrlInterceptorTest（2）、
AutoSyncCoordinatorNetworkTest（2）、SyncManagerTest（4）、TimerSyncRepositoryTest（9）。
原有效断言全部保留；新增 TimerSyncDurabilityTest 五个真实存储场景。

- 认证：旧账户/旧登录请求不得借新会话刷新；相同会话可复用新令牌；header 与会话 tag 必须来自同一快照。
- 网络：局域网不要求 INTERNET/VALIDATED；VPN 附带 Wi-Fi 不等于直接局域网；回调并发、失联、阻塞、
  DNS 更新、默认路由改变、关闭和注册异常均保留状态断言。自动同步检查实际时间窗口内的调度次数。
- 端点：真实本机 TCP HTTP 服务返回固定服务器身份，验证身份不符、路由回退、取消、无配置和远端明文拒绝。
  Network/socketFactory 仍为路由边界替身，不代表真实 VPN、NAS 或外网路由通过。
- 计时：由真实 start/stop Room 事务生成完成记录和命令，Retrofit 使用真实转换器，仅 HTTP 响应边界受控。
  响应丢失后重开数据库，以原命令重试；独立 JSON 字面量校验命令 ID、会话、顺序、UTC 时间、控制代数、
  revision 和毫秒单位。临时错误保留命令身份与诊断，永久拒绝持久化隔离，手动重试换新 ID；
  缺失/未知确认保留原命令，确认成功不删除完成事实或计时片段。

## 实际发现与修复

新增真实存储测试在修复前发现：服务器返回正确 command ID、错误 session ID 的 applied 响应后，
待同步队列变为空。原测试仅覆盖取消恢复路径的 ID 校验，常规上传缺少会话校验。
TimerSyncRepository 在改变队列前同时检查 session ID，错配时报告错误并保留该命令。
遵守既有“同 session 与 operation ID 重放、确认后删除”契约，不改变协议字段、状态机或数据库结构。

旧 MockK 接口代理在 Android 上把受检 IOException 包成 UndeclaredThrowableException，导致旧异常
断言失败。该场景改用委托 API 的小型传输替身，仍要求原 IOException，并新增响应丢失时零删除断言。
新增真实 Retrofit 测试另行验证同一路径，未将断言放宽为任意异常。

EndpointResolverTest 原先使用固定文件、未取消 DataStore scope，并以 runCatching 吞掉服务线程异常。
改为独立文件、显式取消并等待存储结束、检查服务线程退出及意外异常；只容许关闭监听 socket 的预期异常。

NetworkCapabilities 没有公开 setter；测试用 NetworkRequest 公开 builder 和 Parcelable CREATOR 构造真实
capabilities，使生产 defensive copy 继续得到执行。夹具依赖 Android 15 AOSP 的 parcel 首字段布局，并逐次
断言 transport/capabilities 完全符合输入；格式变化应使测试失败，不能静默退回假对象。
依据：[AOSP NetworkRequest.java](https://android.googlesource.com/platform/packages/modules/Connectivity/+/refs/heads/android15-release/framework/src/android/net/NetworkRequest.java)。
LinkProperties DNS 使用公开 setter；订阅条件通过 NetworkRequest 公开查询，不反射私有字段。

## 验证证据

修复前真实计时测试明确检出队列丢失；修复后计时 14 项定向测试通过。
三种临时错误实现均被单项断言检出：VPN 错作 LAN、重试复用原拒绝 ID、60000 毫秒错误发送为 60。
每次探针结束后源码按字节恢复；未与后端或仓库门禁并发变异。最终完整真机 477 项通过，0 失败/错误/跳过；lint、构建、编译警告门禁与覆盖率校准通过。
后端 703 项通过，行覆盖率 92%，仅已登记开发 JWT 警告；Ruff、格式、已配置 mypy 和 OpenAPI 检查通过。
仓库 12 项通过。证据存放于仓库外
`/Users/xbase/workspace/DayForge-test-audit-2026-09-20/network-timer-138/`。

## 未验证边界

只在 MI 6 / Android 15（API 35）执行；原 Robolectric API 26/28/34 参数不代表对应真机通过。
网络回调是受控输入；真实多网络、VPN、设备厂商后台限制、NAS、外网及端到端服务器幂等仍未验收。
数据库重开不等于断电、设备重启或系统杀进程。没有下载或运行模拟器，没有运行 Android JVM/Robolectric 测试。

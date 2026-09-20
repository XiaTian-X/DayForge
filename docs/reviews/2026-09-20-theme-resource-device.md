# 主题、资源与图标测试审查（#139）

## 范围

五个历史文件、49 个方法迁到真机：FormattedStringResourceTest（2）、IconMapperTest（31）、
ColorSchemeGeneratorTest（8）、SeedPaletteCompatibilityTest（4）、ThemeExportCompatibilityTest（4）。
同时移动共享 fixture helper 与 CSV，不改变生产主题算法、UI 外观、资源或依赖。

## 预期可信度

旧 OLED 导出测试通过调用被测 ColorSchemeGenerator 计算预期；生成器错了，导出与预期可能一起错。
改为固定的 27 角色 OLED 设计值，生成器、主题分支与导出各自接受相同独立规范约束。
无效种子回退、逐角色覆盖和自定义 oled 分支也改用固定 CSV，保留原有效断言。
原 ColorSchemeGenerator 的颜色非空检查另加完整角色数组相等，防止“任何合法颜色均通过”。

CSV 为既有重构前记录的 43 个种子 × 明暗两种模式 × 27 个角色，不由当前实现生成。
该 CSV 证明历史输出兼容，不能单独证明历史算法或视觉设计一定正确。
移动前后 SHA-256 相同：`9e7edec574afca7d32db355d497dad592bbadc88192a3f2f78d48bb1e9c60141`。
资源测试继续使用独立中文/英文结果字面量检查参数顺序、补零；图标保留固定 ID/名称映射和未知值行为。

## 故障对照与验证

49 项定向真机测试通过。三个代表性错误均检出：

- OLED outlineVariant 从 #202020 改为 #909090：还原旧的生成器预期时，该单项导出测试通过；
  恢复固定预期后，同一错误明确触发 ComparisonFailure。这里只证明该旧方法漏检，不声称整套旧测试全部通过。
- 浅色 outlineVariant 错用 outline：固定 CSV 对 FF1976D2 角色给出精确数值差异。
- 中文计时资源交换分钟/秒参数：预期 03:07、实际 07:03，字符串断言失败。

探针均为可编译、实际在真机执行的错误；源码按字节恢复。记录器首次只识别 AssertionError 字样，
未识别其子类 ComparisonFailure，因而记录器停止；核查 XML 确认该项已正常失败，修正记录器后继续剩余
两项，没有把构建错误当作检出证据。最终完整真机 526 项通过，0 失败/错误/跳过；lint、构建、编译警告 0 与覆盖率校准通过，仓库 12 项通过。
本批仅修改 Android 测试与文档，无后端代码变化；后端最近完整结果为 #138 的 703 项、92% 行覆盖率，未冒充本批重跑。
证据保存在仓库外
`/Users/xbase/workspace/DayForge-test-audit-2026-09-20/theme-resources-139/`。

## 边界

只在 MI 6 / API 35 真机执行，没有运行 JVM/Robolectric 或模拟器。其他 API 的真机结果未验证。
数值与字符串正确不等于屏幕外观、文字对比度、厂商系统栏或桌面小组件已人工验收；这些限制仍保留。

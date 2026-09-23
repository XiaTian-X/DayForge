# 静态图标 SVG profile v1

这是 [外观契约](APPEARANCE_CONTRACT.md#21-文件与解码边界) 的精确输入白名单，
不是通用 SVG 浏览器，也不承诺接受任意设计工具的默认导出。生成/导出图标须按此 profile
转为静态几何；不支持的输入明确拒绝，不静默删除内容后安装。

`inspect_svg` / `inspectSvg` 校验实际字节、完整 XML、数值语法及绘制预算，返回尺寸和复杂度统计。
Android `renderSvg` 从同一完整校验生成的受限场景实际绘制像素；不将原始 SVG 交给 WebView 或通用解码器。
后端只验证静态绘制条件，不声称产生与 Android 相同的 SVG 像素。两端均不安装、不上传、不改变
blob 就绪状态；ZIP、文件持久化、账户授权、备份闭包和实际 UI 接入仍是独立交付门槛。

`inspect_svg_stream` / InputStream 版 `inspectSvg`、`renderSvg` 先调用共享限量读取器，读取一次并要求 EOF，
最多消费已声明长度加 1 字节；长度和 SHA-256 一致后才解析返回的冻结字节。不使用 available、
文件头或 Content-Length 代替实际读取，不在校验后重开来源。短读会继续，截断/超长/错 hash
明确失败；I/O 错误和取消原样传播。调用方负责关闭、后台执行与传输超时，不能在主线程调用，
也不能将该阻塞流适配器当作已实现的 HTTP/URI/ZIP 授权或安装流程。

## 字节与 XML

- 最大 524288 字节；实际长度和 SHA-256 必须与已验证 `IconBlob` 完全相同。
- 仅 UTF-8（允许 BOM，BOM 计入长度与 hash），XML 1.0；声明编码只能为 UTF-8。
  不接受 UTF-16/32、压缩 SVG、其他编码或尾随非 XML 内容。
- 一个根 `svg`；元素使用 SVG namespace 或无 namespace，禁止其他 namespace 声明，
  属性不能带 namespace。只允许 `svg` / `g` 包含子图形，禁止嵌套 `svg`。
- 不接受 DTD、自定义实体声明、外部实体或处理指令。原文出现 `<!DOCTYPE` / `<!ENTITY`
  即拒绝，包括注释中的同样文本；XML 内建转义及数字字符引用解析后仍须符合属性/文本白名单。
- 可包含合法注释；元素间文本/CDATA 仅 ASCII 空格、tab、CR、LF。禁止文字图形。
- 整个文件最多 2048 元素，含根深度最多 16；未知元素、属性和多余非法尾部都拒绝。

## 元素和属性

| 元素 | 专属属性（粗体为必需） |
|---|---|
| svg | **width、height**，viewBox、version、preserveAspectRatio |
| g | 无 |
| path | **d** |
| rect | x、y、**width、height**、rx、ry |
| circle | cx、cy、**r** |
| ellipse | cx、cy、**rx、ry** |
| line | x1、y1、x2、y2 |
| polyline / polygon | **points** |

通用属性为 `id`、`fill`、`stroke`、`opacity`、`fill-opacity`、`stroke-opacity`、`fill-rule`、
`stroke-width`、`stroke-linecap`、`stroke-linejoin`、`stroke-miterlimit`、`stroke-dasharray`、
`stroke-dashoffset`、`transform`。除此之外均拒绝，包括 style、class、事件、href 和 URL。

- 根 width/height 是数学上的整数，各 1–1024，允许 `px` 后缀，必须与声明像素尺寸相等。
  viewBox 可省略；提供时是四个数，后两个必须大于零。百分比和其他单位不支持。
- version 若提供只能 `1.1`；preserveAspectRatio 只能 `none` 或 `xMidYMid meet`，省略取后者。
- fill/stroke 只能 `none`、`#RGB` 或 `#RRGGBB`，大小写十六进制均可；透明度通过 opacity 属性表达。
  不支持 CSS 命名色、currentColor、渐变、filter、剪裁、外部图片或字体。
- opacity 系列在 [0,1]；几何半径/宽高和 stroke-width 非负；stroke-miterlimit 至少 1。
- fill-rule 为 nonzero/evenodd；linecap 为 butt/round/square；linejoin 为 miter/round/bevel。
- dasharray 为 none 或 1–64 个非负数且至少一个大于零；奇数项保留 SVG 重复语义。
- id 为 `[A-Za-z_][A-Za-z0-9_.:-]{0,127}`；不用于资源查找，不支持任何引用。

## 数值、路径与复杂度

ASCII 十进制（允许小数和 e/E 指数），每个 token 最多 64 字符，解析后必须有限且绝对值
不超过 1000000。不接受 NaN、Infinity、十六进制、Unicode 数字或 Unicode 空白。
完整消费输入，不用“找到几个数字”的正则提取代替语法校验。
非零十进制值若下溢成 Double 零则拒绝；数学上的零（如 `0e999`）仍合法。

路径支持 SVG 1.1 的 M/L/H/V/C/S/Q/T/A/Z 及其小写版本、隐式重复和 moveto 后隐式 lineto；
非空路径必须以 moveto 开始。圆弧半径是无符号非负数，两个 flag 各恰为 0/1；
flag 允许规范中的相邻写法。空 d 合法。不得以 Z 后数字隐式继续。
每个命令参数组计一次，包括隐式组及 Z，整文件最多 16384 次。
polyline 至少两个点，polygon 至少三个点，各点计入同一命令预算；闭合边不另计。
数值列表最多 32768 项，路径的紧凑相邻数字语法不能泛用到其他属性。
points 仅允许一对坐标内部 `x-y` 的特殊紧邻写法，点对之间仍需 comma-wsp。

transform 支持 matrix(6)、translate(1/2)、scale(1/2)、rotate(1/3)、skewX(1)、skewY(1)。
参数与相邻 transform 之间必须符合 comma-wsp；不接受 `translate(1)scale(2)`。
每属性最多 64 个、整文件最多 256 个变换。组合矩阵与所有祖先累计矩阵的每一系数均须
有限且绝对值不超过 1000000；skew 的 abs(cos(angle)) 必须大于 0.000001，防止奇异变换。
viewBox 映射也计入根与祖先累计矩阵预算，不允许借极小 viewBox 绕过。既有 root transform
按 SVG 2 的外层语义处理：`rootTransform × viewBoxMapping × descendants`；这是 profile
明确支持的扩展，不表示支持其他 SVG 2 特性。

上述复杂度与数值限制是 profile 的额外安全约束，不意味着 SVG 规范要求这些上限。
template/original 共享同一白名单。

## 静态几何与原生绘制

- 路径先转为绝对 M/L/C/Q/Z，保留相对命令、隐式重复、close 后当前点及 S/T 的原命令反射语义。
  close 后继续绘制时显式开启同起点新子路径，计入 contour 预算并重置虚线相位；
  自动补出的 moveto 只计展开预算，不改变原始命令数。
  圆弧端点相同则省略、任一半径为零则为直线，否则按端点算法校正半径；每段不超过 45°，
  一个圆弧最多 8 段 cubic。这是受限贝塞尔近似，不承诺逐像素匹配浏览器栅格化。
- 推导的半径、中心、端点和控制点须有限且绝对值不超过 10^12，展开最多 131072 个命令。
  中间乘除采用缩放计算，合法极小圆弧半径不会仅因中间上溢被误判。
- matrix 系数、stroke-width、miterlimit、dashoffset 和正 dash interval 转原生 Float 后须仍有限、
  非零值不变零，失败为 SVG_DRAW_PRECISION；防止零线宽被解释为 hairline，或虚线参数失效。
  几何坐标允许正常的亚像素舍入；虚线计算量同时检查原坐标与实际 Float 坐标。
- 每个描边图形虚线工作上界为 `(ceil(lengthBound / period) + contourCount) × intervalCount`，
  整文件相加最多 65536。路径 lengthBound 用控制多边形长度，形状使用外接矩形/圆周等保守上界；
  取原始与 Float 几何较大者，period 取 Double 加总与 Float 逐次加总较小者。
  奇数 pattern 先重复、零 interval 保留；无描边/零线宽不计绘制工作。超预算为 SVG_DASH_LIMIT。
- 继承 fill/stroke 与对应透明度、填充规则和描边配置；默认黑填充、无描边、线宽 1、miter 4。
  `opacity` 不继承，元素的填充与描边、组的全部子图形先合成，再应用一次元素/组透明度。
  支持非零/奇偶填充、端帽、连接、偏移虚线；零尺寸形状不绘制，零线宽不转为 hairline。
- 仅产生声明原尺寸、density-none、sRGB ARGB_8888 位图。位图和中间 opacity 层始终裁在
  1–1024px 画布；深度 16 最多 16 个同时存在的中间组/元素层，加结果位图最多 68 MiB 像素存储
  （不包含路径、运行时和解码对象开销）。不以图形坐标范围分配巨大离屏画布。
- null tint 保留原色；template tint 在整图 alpha 合成完成后以 SRC_IN 着色，包含 tint 自身 alpha。
  每次返回独立位图，调用方负责回收并在后台执行；异常时回收本次已创建的结果位图，不吞掉错误。
  缓存、并发调度、包安装与资源就绪属于上层职责，不能由本入口伪造成功。

## 双端验证

[共享样例](../contracts/next/svg.json) 包含独立预期的路径 token 和 XML 正反例，
由后端 `test_svg_inspection.py` 与 Android 真机 `SvgInspectorTest` 读取同一文件。
另测每个字节/元素/深度/命令/变换/尺寸边界的等于上限和超出上限情况。
解析失败不能创建就绪素材或触发文件/网络外部实体读取。

[几何样例](../contracts/next/svg-geometry.json) 独立验证端点、控制点和圆弧展开；
[绘制样例](../contracts/next/svg-drawing.json) 由后端验证预检结果，Android `SvgRendererTest`
另在真机读取实际像素，覆盖组/元素透明度、继承、几何、viewBox、dash 和预算失败。
着色、独立位图、真实 EOF 流入口、累计预算及画布限制另外断言；不以非空图片代替像素预期。

语法依据：[SVG 路径 BNF](https://www.w3.org/TR/SVG11/paths.html#PathDataBNF)、
[变换](https://www.w3.org/TR/SVG11/coords.html)、[points](https://www.w3.org/TR/SVG11/shapes.html)。
XML 防护参考 [Android XXE 指南](https://developer.android.com/privacy-and-security/risks/xml-external-entities-injection)。
绘制语义参考 [SVG 圆弧实现注记](https://www.w3.org/TR/SVG11/implnote.html#ArcImplementationNotes)、
[组透明度](https://www.w3.org/TR/SVG11/masking.html#ObjectAndGroupOpacityProperties) 和
[Android Canvas](https://developer.android.com/reference/android/graphics/Canvas)；根变换顺序依据
[SVG 2 viewBox](https://www.w3.org/TR/SVG2/coords.html#ViewBoxAttribute)。

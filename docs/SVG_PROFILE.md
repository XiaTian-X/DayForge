# 静态图标 SVG profile v1

这是 [外观契约](APPEARANCE_CONTRACT.md#21-文件与解码边界) 的精确输入白名单，
不是通用 SVG 浏览器，也不承诺接受任意设计工具的默认导出。生成/导出图标须按此 profile
转为静态几何；不支持的输入明确拒绝，不静默删除内容后安装。

当前 `inspect_svg` / `inspectSvg` 校验实际字节、完整 XML 与数值语法，返回尺寸和复杂度统计。
它们不产生像素、不安装、不上传、不改变 blob 就绪状态。像素渲染、PNG 解码、ZIP、
文件持久化、账户授权、备份闭包和实际 UI 接入仍是独立交付门槛。

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

上述复杂度与数值限制是 profile 的额外安全约束，不意味着 SVG 规范要求这些上限。
template/original 共享同一白名单；alpha 着色须在实际渲染阶段测试，不由检查器虚构结果。

## 双端验证

[共享样例](../contracts/next/svg.json) 包含独立预期的路径 token 和 XML 正反例，
由后端 `test_svg_inspection.py` 与 Android 真机 `SvgInspectorTest` 读取同一文件。
另测每个字节/元素/深度/命令/变换/尺寸边界的等于上限和超出上限情况。
解析失败不能创建就绪素材或触发文件/网络外部实体读取。

语法依据：[SVG 路径 BNF](https://www.w3.org/TR/SVG11/paths.html#PathDataBNF)、
[变换](https://www.w3.org/TR/SVG11/coords.html)、[points](https://www.w3.org/TR/SVG11/shapes.html)。
XML 防护参考 [Android XXE 指南](https://developer.android.com/privacy-and-security/risks/xml-external-entities-injection)。

# 静态图标 PNG profile v1

本 profile 细化 [外观契约](APPEARANCE_CONTRACT.md#21-文件与解码边界) 的实际字节检查，
不启用上传、安装、备份或新的 UI。原始字节和 hash 不改写；展示用位图是独立派生物。

## 验证顺序与预算

输入最多 2097152 字节，实际长度/SHA-256/MIME/画布必须与已验证 IconBlob 一致。
每边 1–1024 像素，逐 chunk 检查完整长度与 CRC；IHDR 首个且唯一，IEND 最后且无尾随数据，
IDAT 连续，PLTE/透明度/颜色描述按规定顺序且必要时唯一。最多 4096 个 chunk。
acTL/fcTL/fdAT 一律拒绝，不接受动画。未知 chunk 明确拒绝，不删除后伪装成原文件成功。

支持灰度、RGB、调色板、灰度 alpha、RGBA 及 PNG 规定的各合法位深，包含 16 位和 Adam7。
不是仅识别 IHDR：先计算各扫描 pass 的精确展开长度，再有界解压完整的单个 zlib 流，
拒绝截断、尾部、拼接流、字典、长度不符和无效 filter。调色板图片还须撤销行过滤，
逐样本验证索引没有越界，不能让解码器把坏索引静默画成黑色。

最后后端通过锁定的 Pillow 调用实际 load/read pixels，不用 open 或 verify 代替解码；
Android 先读 BitmapFactory bounds 再实际解码，统一到不随设备 density 缩放的 sRGB/ARGB_8888。
原生解码偏好不是保证：合法 16 位 RGB 可能返回扩展 sRGB 浮点格式，此时使用显式 sRGB
目标 Canvas 转换，保留 alpha，释放中间位图。不能因返回格式不同就拒绝所有 16 位图。
中间解码最多每像素 8 字节，最终位图每像素 4 字节；UI 后续仍须限制并行解码与缓存总量。
原图色彩模式/模板着色、缩略与小组件共享渲染还需后续接入，不能在计时 tick 中反复解码。

## 附加元数据

白名单为 tRNS、cHRM、gAMA、iCCP、sBIT、sRGB、bKGD、hIST、pHYs、tIME、tEXt、zTXt、iTXt，
各结构、大小和顺序检查与 PNG 核心块分开。ICC 与 sRGB 不同时声明。EXIF、HDR 和其他
当前未定义的附加块不在此 profile 中；导出图标应明确使用支持的静态格式，不依赖隐藏元数据。

- 每个附加块原始内容最多 262144 字节；zTXt/iTXt/iCCP 的独立展开内容也最多 262144 字节。
- 全文件附加内容最多 1048576 字节，压缩元数据同时计入原始与展开字节。不是仅限制像素，
  也不直接沿用通用图库面向大照片的默认元数据预算。
- 压缩前缀、keyword、压缩方式、完整 zlib 流、UTF-8/语言标签均校验；不展示或执行原始文本。
  iCCP 另校验总长度、acsp、RGB/GRAY 与图片类型对应、tag table 数量及偏移/长度边界。
  这是有界 ICC 容器检查，不是自行实现通用 ICC 色彩管理器；实际色彩转换由平台处理。
- 不支持的输入在预览/安装前失败，不能换一份清理后的字节却仍沿用原 hash。

## 验证与后续接入

[共享字节样例](../contracts/next/png.json) 固定合成 PNG 的 base64、独立像素预期或错误类别。
双端消费同一份文件，包含五种行 filter、完整七 pass、调色板透明度、16 位、有效 ICC，
以及 CRC、动画、压缩流与附加元数据攻击反例。透明像素的隐藏 RGB 不用于 Android 像素相等
断言，因为平台使用预乘 alpha；原始文件仍保留，alpha 本身必须精确一致。
额外生成恰好上限/超限的字节、像素、块数和元数据样本，避免把大测试文件提交进仓库。

当前接收已在内存中的有界输入。实际 URI/HTTP/ZIP 接入还必须边读边限制总量、冻结一次读取的字节，
在全部校验和实际解码成功后才能进入安装恢复流程；不能先更新素材就绪状态再异步检查。
本 profile 不代替账户授权、文件原子替换/日志、队列恢复、备份引用闭包或人工外观验收。

依据：[PNG 规范](https://www.w3.org/TR/png/)、[Pillow PNG](https://pillow.readthedocs.io/en/stable/handbook/image-file-formats.html#png)、
[实际 load 与延迟 open](https://pillow.readthedocs.io/en/stable/reference/Image.html#PIL.Image.open)、
[Android 解码偏好](https://developer.android.com/reference/android/graphics/BitmapFactory.Options#inPreferredConfig)。

# 贴译 TextTack Android

安卓上的屏幕翻译工具：点一下悬浮按钮，读取当前屏幕的文字（读不到就截屏在本机识别），翻译后把译文盖在原文位置上。重点支持泰语。

> 基于 [TextTack（贴译）](https://github.com/Willardzsw/TextTack) / OverTranslate 的思路，独立实现。不复用其 C# 代码。

**当前状态**：项目准备阶段，只有空的应用框架，翻译功能还没有实现。

## 特点（计划）

- 以无障碍服务为核心：优先直接读取界面文字，不截屏、不耗电。
- 读不到文字时（游戏、图片、自绘界面）用无障碍截屏 + 本地 OCR（PaddleOCR 模型，ONNX Runtime 运行），截图不离开手机。
- 译文用无障碍悬浮层显示，不需要"显示在其他应用上层"权限。
- 只需安卓 11 及以上。

详见 [docs/第一版范围.md](docs/第一版范围.md)。

## 安装

从 GitHub Releases 下载 APK 安装（不上 Google Play）。安卓 13 及以上需要先在应用信息里"允许受限设置"，才能开启无障碍服务。

## 构建

见 [AGENTS.md](AGENTS.md) 的"构建"一节。OCR 模型不在仓库里，放法见 [docs/移植清单.md](docs/移植清单.md)。

## 许可证

本项目代码使用 MIT 许可证，见 [LICENSE](LICENSE)。

使用的 OCR 模型来自 [PaddleOCR](https://github.com/PaddlePaddle/PaddleOCR)（Apache-2.0），ONNX 格式由 [RapidOCR](https://github.com/RapidAI/RapidOCR) 导出（Apache-2.0）；运行库 [ONNX Runtime](https://github.com/microsoft/onnxruntime)（MIT）。

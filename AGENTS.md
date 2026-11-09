# TextTack 安卓版（贴译 Android）项目说明

给在这个仓库里干活的人和 AI 助手看的说明。

## 这是什么

Windows 翻译工具 TextTack（贴译）的安卓版，只给作者自己用。放在 GitHub 上发 APK，不上 Google Play。

Windows 版仓库：`Willardzsw/TextTack`，本地在 `E:\Claude\overtranslate`，基于开源 OverTranslate 修改（C#/WPF）。安卓版是**独立实现**：只借思路、模型和参数，不复用 C# 代码。

## 已定方向（改之前先和用户商量）

- **原生 Kotlin**，不用 .NET MAUI。现有 C# 和 WPF/Windows 接口绑得紧，复用价值低。
- **以无障碍服务（AccessibilityService）为核心**：
  1. 先读界面节点的文字和位置（`AccessibilityNodeInfo`），够用就不截屏。
  2. 读不到（游戏、图片、自绘界面）再用无障碍截屏 `AccessibilityService.takeScreenshot()`（安卓 11+，不用每次同意，但有最短间隔限制），然后本地 OCR。
  3. 译文用无障碍自带的悬浮层 `TYPE_ACCESSIBILITY_OVERLAY` 显示，不另申请"显示在其他应用上层"权限。
  4. 输入框替换翻译以后用 `ACTION_SET_TEXT` 实现（第二步）。
- **OCR 在本地跑**：ONNX Runtime Android + PaddleOCR 模型（PP-OCRv6 tiny 检测 + 各语言识别）。第一版先支持泰语和中英文。
- **只用轻量的进程内模型**，不引入大模型、不跑本地推理服务。
- minSdk 30（安卓 11，无障碍截屏需要）。

## 用户习惯

- 所有面向用户的文字（界面、提示、文档、回复）都用**中文**。
- 多项改动先对齐方案，用户说"开始改"后再写功能代码。
- **功能改动后要提高版本号**（`app/build.gradle.kts` 里的 `versionCode` 和 `versionName`），提交说明里写上新版本号。
- 用户自己在手机上测界面，不要用电脑操控工具去点界面。
- 测试手机：荣耀 100（MAA-AN00），MagicOS 10，安卓 16（API 36），arm64-v8a。详见 `docs/第一版范围.md`。

## 目录

```
app/                     安卓应用模块
  src/main/java/...      Kotlin 源码（包名 io.github.willardzsw.texttack）
  src/main/res/xml/accessibility_service_config.xml   无障碍服务配置
  src/main/assets/ocr/   OCR 模型放这里（不进 git，见 docs/移植清单.md）
docs/第一版范围.md        第一版做什么、不做什么、权限、风险
docs/移植清单.md          要从 Windows 版移植的逻辑、模型来源、许可证
```

## 构建

需要 JDK 17。本机 PATH 里没有 java，用 Android Studio 自带的 JBR：

```powershell
$env:JAVA_HOME = 'D:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat assembleDebug
```

Git Bash 里：

```bash
JAVA_HOME="/d/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug
```

- Android SDK 在 `%LOCALAPPDATA%\Android\Sdk`，路径写在 `local.properties`（不进 git，Android Studio 打开项目时会自动生成）。
- 产物：`app/build/outputs/apk/debug/app-debug.apk`
- 装到手机：`adb install -r app/build/outputs/apk/debug/app-debug.apk`（`adb` 在 `%LOCALAPPDATA%\Android\Sdk\platform-tools`）
- 版本：Gradle 8.6、Android Gradle Plugin 8.4.1、Kotlin 1.9.24、compileSdk/targetSdk 34。

## 约束

- **不要修改** Windows 版仓库 `E:\Claude\overtranslate`，只读参考；不要读取或复制里面的任何 API 密钥；不要碰它的 `profile-thai` 文件夹。
- 模型文件体积大，不进 git（`.gitignore` 已排除 `*.onnx`）。
- 翻译服务的 API 密钥只存在手机本地（以后用加密存储），不写进代码、不进 git。
- PaddleOCR 模型和 RapidOCR 导出是 Apache-2.0，ONNX Runtime 是 MIT，发布 APK 时要附带它们的许可证声明（见 `docs/移植清单.md`）。

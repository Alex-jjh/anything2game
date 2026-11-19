# 文档摸底与对话讲解验收

2026-11-19，起点为 `8cf2582`，仓库 `API用`（Android 实际工程在 `android/`）。用户明确要求补齐 B 的缺项，并测试完整流程。本轮没有改动同级旧 `Android用` / `IOS用`，没有安装软件或调用真实/付费模型。

**本机可运行链路已经验收：真实 PDF 上传与文字提取 → 文档相关基础摸底 → 有限理解评估 → mock 对话讲解；左侧独立生成并运行巩固。** 真实模型语义效果、PostgreSQL 与实体手机尚未验收，不能把本报告作为生产上线通过证明。

## 改动责任标记

| 责任范围 | 实际修改 |
| --- | --- |
| A：Android | 导入确认/重试/取消；本机安装令牌与 HTTP；独立 TutorRepository/AtomicFile 缓存；选择题＋不知道；理解起点；对话/原文；独立巩固生成；三栏/个人页回归 |
| B：本轮代补 | TutorService、5 个认证接口、draft DTO、2 个版本化 prompt、模型输入上下文、输出/引用校验、请求去重与 revision CAS、取消/迟到防护、文件存储、PostgreSQL 新表及适配 |
| A/B：验证 | contract 规则测试、服务端 HTTP/模型替身/存储测试、Android 状态/持久化/界面测试；自拟 PDF fixture 与实际模拟器联调 |
| C：交付参考 | 保留既有纸色/角色/卡片风格，提供下列运行截图；未声称正式视觉或内容审核完成 |

逐项实现、draft JSON/错误码及 B/C 待确认事项见 [document-tutor.md](document-tutor.md)。详细文件清单见 [document-tutor-files.txt](document-tutor-files.txt)。

## 构建与自动测试

沿用 JDK 21.0.10、Gradle 9.4.1、AGP 9.2.1、Kotlin 2.4.21、Compose BOM 2026.09.00；compileSdk 37、targetSdk 36、minSdk 26。使用已获授权的项目 SDK 与临时缓存，无新增依赖。

从仓库根目录实际执行：

```sh
env JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  GRADLE_USER_HOME="$(cat build/home-nav-qa/ascii-cache-path.txt)/gradle-home" \
  ANDROID_HOME="$PWD/build/home-nav-qa/sdk" \
  ANDROID_USER_HOME="$PWD/build/home-nav-qa/android-user" \
  ./gradlew :android:app:assembleDebug :android:app:testDebugUnitTest \
  :android:app:lintDebug :android:engine:test :contract:test :server:test \
  -Proborazzi.test.record=true \
  --init-script build/home-nav-qa/test-network.init.gradle \
  --console=plain --no-daemon
```

项目专用 init script 仅用于本机仓库/缓存访问，已有正常 SDK 与 Maven 环境无需此参数。另执行 `:server:installDist` 构建服务端发行目录成功；实际服务从发行目录的独立快照启动，避免构建重写正在运行的 JAR。

| 范围 | 通过 | 失败/错误 | 跳过 |
| --- | ---: | ---: | ---: |
| Android app（含 Compose/Robolectric） | 74 | 0 | 0 |
| Android engine | 27 | 0 | 0 |
| contract | 35 | 0 | 0 |
| server | 150 | 0 | 27 |
| 合计 | **286** | **0** | **27** |

PostgreSQL/Testcontainers 的 27 项因本机 Docker 服务不可用跳过；文件 store 测试实际执行。模型测试使用受控替身，包括真实延迟/取消竞争、超时、坏 JSON、无效引用、请求重放与身份隔离；不是供应商模型实测。Roborazzi 为 record 模式，不是像素基线对比。

最终命令退出码 0，**BUILD SUCCESSFUL in 59s**；本机安装 APK 与构建文件 SHA-256 一致，最终 crash buffer 为空。

Lint：**0 errors、22 warnings**，含既有 targetSdk/图标/备份/资源等告警；新增令牌保存的 UseKtx 建议保留显式 `commit()` 以检查写入结果。原始结果：`build/home-nav-qa/tutor-release-check.log`、各模块 `build/test-results/`、`android/app/build/reports/lint-results-debug.html`。`git diff --check` 无空白错误。

## 实际设备验证

独立 Android 17 / API 37 arm64 模拟器 `emulator-5578`，1080×2400、420 dpi。测试未清空 app 数据。测试服务器仅监听本机 127.0.0.1:18080，所有模型 provider 均 fake；通过 adb reverse 连接。PDF 原稿/文件在 `android/qa/fixtures/`，内容自拟、无真实研究或外部来源宣称。

| 验收项 | 实际方法与结果 |
| --- | --- |
| 三栏/个人主页 | `home_navigation_smoke.py` 7 项通过：导航目标、原生键盘昵称保存/取消、角色、旋转、强制关闭恢复、系统文件选择取消 |
| 基础摸底 | 固定示例依次选答对、答错、不知道；分别展示有依据、需澄清、尚未判断；不写巩固成绩 |
| 对话与来源 | 优先讲需要澄清的概念；举例、原文弹窗、自由输入可操作；mock 明示无法语义回答自由提问 |
| 真实文件链路 | 原生文件选择器选自拟 PDF，确认后服务端提取实际文字，生成文档相关 mock 摸底；完成评估与讲解、打开对应原文 |
| 连接失败与重试 | 临时撤销本 QA 设备的 adb reverse，出现连接错误；恢复 reverse 后原页面重试成功，无重装/清数据 |
| 取消与迟到 | 文件选择取消在设备验收；上传/生成取消和迟到 UI 结果由 ViewModel 测试覆盖；服务端延迟模型响应被取消后不能提交，由服务测试覆盖。未将这些受控测试写成全都在模拟器网络现场重现 |
| 旧数据 | 对原有两个游戏/session/mastery 文件进行前后逐字节比对，保持不变；未删除旧自评、昵称、角色 |
| 独立巩固 | 在左侧为已上传文档生成练习包，校验安装，进入原游戏并实际作答；中间讲解不再自动跳入巩固 |
| 断网查看 | 移除 reverse 后冷启动，缓存的远程讲解历史可打开，显示离线提示；记录字节不变 |
| 布局 | 中文竖屏/横屏、130% 字号、原生键盘与来源弹窗；截图复核发现滚动内容进入状态栏后修正安全边距并重新构建复测 |

恢复验证严格区分：

- **配置变化**：设备旋转与字号变更，保留回答/对话、个人资料；未把旋转当成进程死亡。
- **受控进程重建**：Home 到后台，再通过 `run-as` 对本 app PID 执行 `kill -9`，启动后确认新 PID；固定和上传文档会话恢复，缓存字节一致。这不是系统真实低内存回收。
- **强制关闭重开**：`am force-stop` 后确认进程消失，再启动；Tab/昵称/角色保留，从讲解入口继续原答案与历史。
- **未测**：真实低内存回收、实体手机、全机型/API 兼容、真实模型语义质量、PostgreSQL 迁移执行/并发。

本轮未重新完成所有旧游戏模板的人工整局验收；原游戏引擎 27 项测试通过，设备执行 Classic 作答。旧游戏内 Ask 面板仍沿用原状态；本轮实现的是中间独立文档教师对话。

可复跑主页脚本（只用于专用 QA 模拟器，会改测试昵称/角色，不清数据）：

```sh
python3 android/qa/home_navigation_smoke.py \
  --adb /Users/linziwei/Library/Android/sdk/platform-tools/adb \
  --serial emulator-5578 --output build/home-nav-qa/tutor-home-smoke-final
```

## 截图

均为真实模拟器截图，无生成/拼接。在本轮各功能验收步骤截取；安全边距修复后重拍对话、来源、大字号、横屏、输入法及主页。`ReaderQA` 是测试昵称，统计包含预生成示例及测试导入。固定示例与上传解析＋AI mock 分开标注；来源可追溯不等于内容已验证。

| 内容 | 文件 |
| --- | --- |
| 讲解主页与中央导航 | [01-home.png](screens/document-tutor/01-home.png) |
| 巩固与左侧导航 | [02-practice.png](screens/document-tutor/02-practice.png) |
| 个人主页与右侧导航 | [03-profile.png](screens/document-tutor/03-profile.png) |
| 固定示例基础题 | [04-fixed-question.png](screens/document-tutor/04-fixed-question.png) |
| 答对/答错/不知道的理解标签 | [05-assessment.png](screens/document-tutor/05-assessment.png) |
| 对话讲解 | [06-chat.png](screens/document-tutor/06-chat.png) |
| 对应原文 | [07-source.png](screens/document-tutor/07-source.png) |
| 确认实际发送文档 | [08-confirm.png](screens/document-tutor/08-confirm.png) |
| 连接失败可重试 | [09-retry.png](screens/document-tutor/09-retry.png) |
| 实际 PDF 文字提取后的 mock 基础题 | [10-uploaded-question.png](screens/document-tutor/10-uploaded-question.png) |
| 上传文档的 mock 讲解 | [11-uploaded-chat.png](screens/document-tutor/11-uploaded-chat.png) |
| 独立生成的巩固 | [12-game.png](screens/document-tutor/12-game.png) |
| 离线历史 | [13-offline.png](screens/document-tutor/13-offline.png) |
| 130% 字号 | [14-large-text.png](screens/document-tutor/14-large-text.png) |
| 横屏对话 | [15-landscape.png](screens/document-tutor/15-landscape.png) |
| 原生键盘 | [16-keyboard.png](screens/document-tutor/16-keyboard.png) |

## APK、启动与剩余限制

APK：`android/app/build/outputs/apk/debug/app-debug.apk`，debug 包，不入 Git。最终 SHA-256 与构建结果记录在同目录的 [document-tutor-results.json](document-tutor-results.json)，安装包与构建产物摘要已核对。

```sh
adb -s DEVICE_SERIAL install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE_SERIAL reverse tcp:18080 tcp:18080
adb -s DEVICE_SERIAL shell am start -n com.anything2game.app/.MainActivity
```

离线固定示例无需启动服务。本机 QA 设备继续访问已上传文档时，需要沿用本次服务器的数据目录（安装令牌与文档都保存在此）：

```sh
JAVA_HOME='/Applications/Android Studio.app/Contents/jbr/Contents/Home' \
  A2G_HOST=127.0.0.1 A2G_PORT=18080 A2G_STORE=file \
  A2G_DATA_DIR="$PWD/build/home-nav-qa/tutor-server-data" \
  A2G_COMPILE_PROVIDER=fake A2G_FRAME_PROVIDER=fake A2G_FLASH_PROVIDER=fake \
  A2G_DEFAULT_FRAMES=classic server/build/install/server/bin/server
```

服务启动期间不要覆盖其运行 JAR。换全新服务器数据目录时旧令牌不再有效；不要通过清空用户学习数据“修复”。正式账号/云同步与服务迁移仍需设计。

未解除的边界：当前摸底仅基于前 8 个片段、每片至多 2000 字符；会话至多 40 轮；初次跨实例并发生成可能重复模型工作；上传成功但响应丢失可能留下重复文档；取消不等于删除服务器数据；未提供文档管理删除界面。真实模型适配已接线，但没有付费调用/内容质量验证，不等同正式 AI 产品完成。

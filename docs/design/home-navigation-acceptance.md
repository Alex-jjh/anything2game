# Android 主页与导航验收（2026-11-19）

本轮在 `anything2game-api` 的 `main`（起点 `9d024e5`）完成。范围为 Android 三栏主页、个人资料、本机阅读准备与现有学习流程衔接。功能验收通过；尚未接入的账户、上传生成和术语级 AI 个性化不在“已实现”范围。

## 构建与自动测试

环境：Android Studio 自带 JDK 21.0.10；仓库 Gradle 9.4.1、AGP 9.2.1、Kotlin 2.4.21、Compose BOM 2026.09.00；compileSdk 37、targetSdk 36、minSdk 26。SDK 37 按用户授权安装到忽略的 `build/home-nav-qa/sdk`，Gradle / Robolectric 缓存使用本任务独立临时目录。

最终命令（从仓库根目录运行；已有完整 SDK / Maven 访问环境不需要本机网络 init script）：

```sh
./gradlew :android:app:assembleDebug :android:app:testDebugUnitTest \
  :android:app:lintDebug :android:engine:test :contract:test \
  -Proborazzi.test.record=true --console=plain --no-daemon
```

本机实跑额外设置了 `JAVA_HOME`、`ANDROID_HOME`、`ANDROID_USER_HOME`、`GRADLE_USER_HOME`，以及 `--init-script build/home-nav-qa/test-network.init.gradle`（仅指定 Maven 镜像、临时缓存与本机代理，不改源码仓库）。最终原始日志：`build/home-nav-qa/final-verification-3.log`，**BUILD SUCCESSFUL，36 秒**。

| 范围 | 测试数 | 失败 / 错误 / 跳过 |
|---|---:|---:|
| Android app（含界面断言、渲染、导航、偏好、阅读来源及统计） | 65 | 0 / 0 / 0 |
| Android engine | 27 | 0 / 0 / 0 |
| contract | 30 | 0 / 0 / 0 |
| 合计 | **122** | **0 / 0 / 0** |

Lint 任务通过：**0 errors，21 warnings**。告警涉及 targetSdk、应用图标、备份规则、既有字符串复数/未使用资源及旧偏好的 KTX 风格等；本轮没有把“通过”写成零告警。详情在 `android/app/build/reports/lint-results-debug.html`。

Roborazzi 使用 record 模式验证渲染并产图，本轮没有宣称执行像素基线对比。昵称弹窗在此版本 Robolectric 上无法稳定进入 idle，最初该项失败；键盘输入、保存、取消和重开验证已转为下面的真实模拟器脚本，JVM 的偏好持久化测试仍保留。依赖曾因 Maven 重定向 TLS 失败、中文缓存路径未解码失败，使用校验后的官方依赖和 ASCII 临时缓存解决。

## 实际模拟器验收

Android 17 / API 37，arm64，1080 × 2400，420 dpi；独立 QA 模拟器 `emulator-5578`。从本项目先前已授权的 QA 设备复制数据到本轮独立目录，未改变原模拟器。设备处于离线演示状态。

可重复执行的原生键盘/导航验收：

```sh
python3 android/qa/home_navigation_smoke.py \
  --adb "$ANDROID_HOME/platform-tools/adb" \
  --serial emulator-5578 --output build/home-nav-qa/smoke
```

脚本实际退出码 0，7 项检查通过：三栏目标页、昵称保存、取消保留原昵称、角色持久化、旋转、强制关闭重开、系统 PDF 选择取消。使用专用 QA 模拟器；脚本会修改演示昵称/角色与应用语言，不清空数据。

补充验收已通过：

- 学前“有点模糊”选择 → 已有章节讲解 → 重点阅读提示 → 对应原文展开/收起 → Classic 巩固。
- 故意答错后显示解释和重试信息；切换到个人主页再续学，保存的 session JSON 原样一致。
- 强制关闭 app，重新打开并从巩固档案续学，答案与反馈仍原样一致。
- Tab 切换恢复独立滚动位置，断言时不滚动寻找目标；系统返回键从个人主页回到讲解。
- PDF 选择进入确认页；点击生成说明只显示服务未连接，明确未上传、未解析、未调用 AI。
- 中文、英文；竖屏、横屏；130% 系统字号；个人页控件可读、可滚动且底栏可用。
- 最终 QA 设备 crash buffer 为空。

状态恢复验证的区别：

| 场景 | 实际方法 | 结果 |
|---|---|---|
| 配置变化 | `wm user-rotation lock 1 / 0` | 昵称、角色、个人页保留 |
| 进程重建 | Home 进入后台，再 `run-as … kill -9 <本 app PID>`，从启动入口回到任务 | 新 PID；个人页、昵称及角色恢复 |
| 真正关闭重开 | `am force-stop com.anything2game.app` 后启动 | Tab、昵称、角色；学习答题从档案继续恢复 |
| 真实低内存回收 | 未制造系统内存压力 | **未测** |
| 实体 Android 手机 | 未连接 | **未测** |

未执行真实后端 / 付费 AI / 登录 / 云同步 / 实际上传解析、服务端测试及全量 Android 机型兼容测试。旧框架续学映射由单元测试覆盖；本次设备练习使用 Classic。

原始补充日志位于忽略的 `build/home-nav-qa/`：`profile-qa.log`、`learning-qa.log`、`progress-qa.log`、`final-device-check.log`、`final-captures.log`。英文截图首轮定位曾因正确保留页面滚动位置找不到顶部标题；回到顶部重拍成功，记录保留。

## 截图索引

均为实际运行截屏。`ReaderQA` 是测试昵称，统计来自预生成示例的本机练习记录；来源可追溯不代表内容已验证。

| 页面 | 截图 |
|---|---|
| 讲解主页 + 中央突出导航 | [01-learn-zh.png](screens/home-nav/01-learn-zh.png) |
| 巩固档案 + 左侧导航 | [02-practice-zh.png](screens/home-nav/02-practice-zh.png) |
| 个人主页 + 右侧导航 | [03-profile-zh.png](screens/home-nav/03-profile-zh.png) |
| 个人页账户/数据说明 | [04-profile-account-zh.png](screens/home-nav/04-profile-account-zh.png) |
| 横屏个人页 | [05-profile-landscape-zh.png](screens/home-nav/05-profile-landscape-zh.png) |
| 130% 字号 | [06-profile-large-text-zh.png](screens/home-nav/06-profile-large-text-zh.png) |
| 章节级本机学前自评 | [07-preparation-zh.png](screens/home-nav/07-preparation-zh.png) |
| 章节讲解 | [08-reading-zh.png](screens/home-nav/08-reading-zh.png) |
| PDF 未接服务说明 | [09-pdf-info-zh.png](screens/home-nav/09-pdf-info-zh.png) |
| 英文个人主页 | [10-profile-en.png](screens/home-nav/10-profile-en.png) |

## APK 与改动范围

APK：`android/app/build/outputs/apk/debug/app-debug.apk`（debug 包，不入 Git）。SHA-256：`f39e414ca96a3a8f8b80388acc72635589147b764d0346cd416e9cc2808117da`。

```sh
adb -s emulator-5578 install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5578 shell am start -n com.anything2game.app/.MainActivity
```

实体手机可开启 USB 调试后用其设备序列号替换；本轮未实测实体手机安装。

新增 `HomeScreen.kt`、`Navigator.kt`、`HomePreferences.kt`、`ReadingScreen.kt`、中英 `home.xml`、五个测试文件及 `android/qa/home_navigation_smoke.py`。调整 AppRoot / AppDependencies、列表统计与角色选择布局、文件确认文案；更新 Android README、构建缓存忽略、设计/验收文档和截图。未改变 contract 或后端 API。

B/C 待确认事项见 [设计与数据边界](home-navigation.md#仍需-b--c-对齐)。

# 文档摸底与对话讲解

2026-11-19，依据用户最新修正及「基础选择题＋不知道」选择。用户随后授权补齐 B 尚缺的能力，以能够端到端运行与测试为目标。此文记录本次实现；不是已获 B/C 确认的最终 schema 或正式产品设计。

## 本轮产品行为

中间「讲解」：选择 PDF / 分享文字 → 确认文件和发送目标 → 服务端提取文字 → 文档相关基础问题 → 概念级理解起点 → 持续对话讲解。左侧「巩固」单独生成、缓存和运行经典练习；中间不再从简短摘要直接跳进游戏。右侧本机个人主页保留。

此决定替代上一版 home-navigation.md 的章节自评＋摘要方案，也优先于 onboarding-and-focus.md 中问题先行、答后展开讲解的旧提案。基础摸底不是巩固题，三道题不构成完整能力测量。答对仅代表该题有依据，答错标为需要澄清，「不知道」单独记为尚未判断。

## A：Android 改动

- 沿用 Kotlin / Compose / ViewModel / 文件存储；未新增依赖或安装软件。保留纸色与卡片风格。
- 新讲解页按题收集答案，全部完成后展示概念证据；先讲需要澄清或未判断的概念，再连接答对的概念。对话气泡、换个说法、举例、继续、自由追问和对应原文弹窗。
- 保留离线示例：`contract/examples/tutor.v1.example.json`（随 retrieval 预设内容包以 `tutor.json` 分发，见 ADR-017；预设课程各自带 `tutor.<name>.json`）。绑定现有自拟资料的 packageId/contentVersion/documentVersion，含独立基础题、选项、答案、讲解与引用。它不是从选中的 PDF 生成，也不代表团队已决定正式离线判题。
- `TutorRepository` 隔离页面、缓存与 HTTP。新会话在 `files/tutor-sessions/`，通过 AtomicFile 保存后再推进页面。损坏/不支持版本的缓存明确报错，不静默覆盖或删除。
- 旧章节自评、session/mastery、昵称和角色不迁移成测评结论、不删除。固定内容换版使用独立会话键；原记录保留。
- 真实上传需要用户在确认页操作。接入已有安装令牌接口；令牌仅保存在应用私有偏好中，按服务器地址隔离，不打印、不跟随 HTTP 重定向。增加 INTERNET 权限用于自有后端，不直连模型供应商。
- Debug 默认 `http://127.0.0.1:18080`，仅 debug 配置放行两个明确的回环主机；其余地址以及 release 使用 HTTPS。release 默认没有服务器地址，构建时可提供 `-Pa2gApiBaseUrl=https://your-api.example`。
- 已上传文档在讲解主页可继续，在左侧可单独生成巩固。下载的包经过现有 PackageValidator / PackCodec 校验再安装，保留已有游戏进度。部分生成成功的包使用已有 PARTIAL 语义，仅包含成功章节。
- 支持提交失败后同请求 ID 重试、重新读取会话、取消并丢弃迟到 UI 结果。取消已完成的服务端操作不会回滚；文件上传取消不等于删除服务器文件。

## B：本轮代为补齐的后端能力

复用已有 PDFBox 提取、文本分块、鉴权、配额、模型适配器、编译任务及持久层，不重写原后端。

新增独立文档 TutorService 与 draft 共享 DTO：

| 方法与路径 | 作用 |
| --- | --- |
| POST `/v1/documents/{documentId}/tutor` | 建立/复用该文档版本和语言的基础摸底 |
| GET `/v1/tutors/{tutorId}` | 恢复题目、评估与历史 |
| POST `/v1/tutors/{tutorId}/assessment` | 校验完整回答，产生概念证据 |
| POST `/v1/tutors/{tutorId}/turns` | 结合回答、评估、历史与文档进行下一轮讲解 |
| POST `/v1/tutors/{tutorId}/cancel` | 标记请求取消，阻止尚未提交的迟到回复 |

- 正确选项保存在服务器记录，不在学前返回中下发。选项/问题 ID、题数、引用和文档归属均校验；不同 owner 读取时返回 404。
- `diagnostic.v1` 与 `tutor.v1` 两个版本化 prompt；编译 tier 负责基础题，flash tier 负责讲解。带入实际选项回答、有限评估和最近 12 条消息。不使用原来缺少历史的 `/ask` 冒充教师会话。
- 每次教学响应必须有本文件的原文引用；引用不存在、越界或无法判断时拒绝整段无依据回复。引用匹配只验证来源关联，不是语义事实核验。
- 真实模型分支已经接线并用受控测试替身测试；本轮**没有调用真实或付费模型**。本机强制三个 provider 均为 fake。
- `mock` 会话始终保持脚本模式，服务器配置变更也不会悄悄变成真实模型调用。mock 自由输入只保存并提示无法语义回答。任何 compile/flash tier 为 fake 时，本轮创建的 tutor 都为 mock。
- 文件持久化与 PostgreSQL 均新增 TutorRecord；`V2__document_tutors.sql` 为新表迁移。CAS revision 防止并发或取消后的旧回复覆盖。删除文档级联删除会话。
- 请求 ID 与请求摘要绑定，重复提交不会重复加消息；旧 revision 返回冲突。单实例同文档创建串行，已完成创建可复用。跨实例首次并发生成仍可能重复调用 provider，最终仅保留一个会话，需要上线前进一步做共享生成租约。
- 新模型调用至多 60 秒；客户端读取超时 90 秒。每会话最多 40 轮；取消记录最多 100 条，避免历史无限增长。

## 数据与错误约定（draft，待 B 审核）

创建：

```json
{"language":"zh","clientRequestId":"create-001"}
```

题目片段（响应的 questions；不含 correctOptionId）：

```json
{"id":"q1","conceptId":"retrieval","concept":"检索练习","prompt":"检索练习指什么？","options":[{"id":"a","text":"从记忆中提取"},{"id":"b","text":"一直看着材料重读"}],"sourceRefs":[{"chunkId":"c2","quote":"Retrieval practice means pulling information out of memory"}]}
```

回答：

```json
{"expectedRevision":0,"answers":{"q1":"a","q2":"__unknown__","q3":"b"},"clientRequestId":"assessment-001"}
```

未作答不得提交；`__unknown__` 表示不知道，不能替代缺失回答。评估状态是 `DEMONSTRATED` / `NEEDS_CLARIFICATION` / `NOT_YET_ASSESSED`，不直接写入正式 mastery。随后对话：

```json
{"expectedRevision":1,"intent":"START","question":"","clientRequestId":"turn-001"}
```

意图还有 `REPHRASE`、`EXAMPLE`、`CONTINUE`、`QUESTION`；仅 QUESTION 接收 1–1000 字的 question。取消请求体使用 `{"clientRequestId":"turn-001"}`。响应 `schema` 为 `a2g.tutor/draft-v1`，含文档 ID/版本、来源、有限评估、消息和 revision。

| 情况 | 处理 |
| --- | --- |
| 缺字段、未知选项、未答完、请求 ID 被不同内容复用 | 400 BAD_REQUEST，不推进会话 |
| 失效身份 / 不属于自己或已删除的文档 | 401 UNAUTHORIZED / 404 NOT_FOUND |
| 模型超时 / 输出损坏或诊断引用错误 | 504 MODEL_TIMEOUT / 502 GENERATION_FAILED，可重试 |
| 旧版本、请求已取消、重复评估、模型模式不兼容 | 409 TUTOR_STALE / TUTOR_CANCELLED / TUTOR_ALREADY_ASSESSED / TUTOR_MODE_CHANGED，重新读取 |
| 超配额 / PDF 太大、损坏、加密或没有文字层 | 复用现有 QUOTA_EXCEEDED / FILE_TOO_LARGE / UNSUPPORTED_TYPE / ENCRYPTED_PDF / NO_TEXT_LAYER 等 |
| 客户端缓存损坏或包版本不匹配 | 明确停止读取，保留原文件；不把旧自评迁移为评估 |

## 可运行方式

在仓库根目录，使用已有 JDK 21、Android SDK 37 和 Gradle wrapper：

```sh
./gradlew :android:app:assembleDebug :android:app:testDebugUnitTest :android:app:lintDebug :android:engine:test :contract:test :server:test :server:installDist
```

本机测试服务器（单独终端；构建更新前先停止旧服务）：

```sh
A2G_HOST=127.0.0.1 A2G_PORT=18080 A2G_STORE=file A2G_DATA_DIR="$PWD/build/local-tutor-data" A2G_COMPILE_PROVIDER=fake A2G_FRAME_PROVIDER=fake A2G_FLASH_PROVIDER=fake A2G_DEFAULT_FRAMES=classic server/build/install/server/bin/server
```

模拟器或已授权 USB Android 设备：

```sh
adb -s DEVICE_SERIAL reverse tcp:18080 tcp:18080
adb -s DEVICE_SERIAL install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s DEVICE_SERIAL push android/qa/fixtures/tutor-notes.pdf /sdcard/Documents/tutor-notes.pdf
```

打开讲解主页，选择此 PDF 并确认发送。离线固定示例不需要服务器。Android 设备连接本机服务时，仍会实际发送文件和请求，只是模型使用 mock。

## 尚需 B / C 确认与上线限制

- B：draft 字段/错误码、单文档单语言一份摸底是否合适，重新摸底与版本迁移、对话结束/40 轮限制、长期数据删除与云同步。
- B：完整长文覆盖策略。当前基础摸底仅取前 8 个片段、每片至多 2000 字符；UI 明确为入门片段摸底，不能称为整篇文章理解评估。
- B：真实模型的题目质量、适配理解水平、语义可靠性、提示注入抗性、超时与成本均需模型实测；目前只验证协议、控制流和引用边界。来源可追溯不等于内容已验证。
- B：云端多实例首次生成租约；文件 store 仅支持单实例。PostgreSQL 迁移/并发测试需要可用 Docker 或测试数据库。
- B：上传请求响应丢失前服务器可能已保存文档；重试可能留下重复文档。已确认上传会在当前导入的 SavedStateHandle 中复用；取消不删除远端文档，尚无文档管理删除 UI。
- C：核心名词选择、题目难度、理解标签用词、示例内容真实性边界，以及大字号、横屏、对话和输入法截图。

实际验收结果见 [document-tutor-acceptance.md](document-tutor-acceptance.md)。上一轮 home-navigation-acceptance.md 保留为历史记录。

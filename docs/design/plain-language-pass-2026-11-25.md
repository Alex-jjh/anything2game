# Plain-language pass, 2026-11-25

The rest of the app (everything outside the Playground and Spaces/Materials rewrite) used internal words and read like translated English in places. Every user-visible text was rewritten in English and Chinese so a high-school student understands it on first read.

One word per idea, on every screen: Space = 学习空间, route = 学习路线, file = 文件, materials = 资料, explanation = 讲解, practice = 练习, round = 第 1 轮, a few quick questions = 几道小问题 (never 摸底 / diagnostic).

Gone from the screen: mock, fixture, receipt, member, revision, verified, assessment, diagnostic, facet, frame, session, pack, payload, endpoint, provider, model ids, content versions, chunk ids, raw error codes and exception names. `PlainLanguageGuardTest` and `PlainLanguageScreensTest` keep it that way (see the end).

## Strings changed per screen

A string changed in both languages counts once.

| Screen | Strings |
|---|---|
| Practice list | 5 |
| Practice (play and recap) | 17 |
| Welcome and profile | 4 |
| Me tab | 20 |
| Quick questions and focus | 13 |
| Explainer (conversation) | 23 |
| Import and sources | 16 |
| Materials and files | 2 |
| Spaces and route map | 12 |
| Playground and games | 16 |
| Characters | 3 |
| Error messages (new: one plain sentence per error code) | 19 |
| Texts built in code (contract / server / client) | 7 |

Total: 131 resource strings changed, 19 new, 7 built in code.

## Practice list

| String | English: before → after | Chinese: before → after |
|---|---|---|
| `library_title` | Your case files → **Your practice** | 你的案卷 → **你的练习** |
| `library_subtitle` | Anything you have to read, playable in five minutes. → **Anything you have to read, turned into a five-minute game.** | 要读的东西，五分钟打成一关。 → **要读的东西，五分钟就能练一遍。** |
| `badge_pre_generated` | Pre-generated sample → **Pre-made sample** | 预生成示例 → **现成示例** |
| `empty_library` | No packages yet. → **Nothing here yet.** | 还没有学习包。 → **这里还没有内容。** |
| `load_failed` | Couldn't load this package: %1$s → **Couldn’t open this practice. %1$s** | 无法加载学习包：%1$s → **打不开这份练习。%1$s** |

## Practice (play and recap)

| String | English: before → after | Chinese: before → after |
|---|---|---|
| `action_submit` | Lock in → **Check my answer** | unchanged |
| `swipe_supported` | Supported → **Backed up** | unchanged |
| `swipe_not_supported` | Not supported → **No proof** | unchanged |
| `swipe_instruction` | Swipe right if the material supports it, left if it doesn't. → **Swipe right if the material backs it up, left if it doesn't.** | unchanged |
| `ask_unavailable_server` | Asking needs the server, which this build doesn't connect to yet. Your place is kept. → **Asking questions isn't available in this version of the app yet. Your place is saved.** | 提问需要连接服务器，这个版本还没有接入。你的进度会保留。 → **这个版本暂时还不能提问。你的进度已保存。** |
| `recap_shaky` | %1$d to revisit tomorrow → **%1$d to try again tomorrow** | unchanged |
| `recap_all_solid` | Every clue is solid. → **You’ve got every clue.** | unchanged |
| `unsupported_mechanic` | This build can't play this kind of level yet. → **This version of the app can't play this kind of level yet.** | unchanged |
| `frame_case_desc` | Collect evidence, then catch the AI summary overclaiming — with the page. → **Collect evidence, then catch an AI summary that goes too far, using the original page.** | unchanged |
| `frame_companion_desc` | A partner introduces each clue in a few cited lines. → **A partner introduces each clue in a few lines, with the source shown.** | unchanged |
| `failed_body` | Out of credibility. Replay the level — clues you missed come back in another form where the course has one. → **You're out of credibility. Play the level again. Clues you missed may come back in a different form.** | unchanged |
| `stamp_supported` | SUPPORTED → **BACKED UP** | unchanged |
| `stamp_not_supported` | NO BASIS → **NO PROOF** | unchanged |
| `stamp_overclaim` | OVERCLAIM → **TOO STRONG** | unchanged |
| `boss_missed` | Final challenge missed — replay the level to take it on again → **Final challenge missed. Play the level again to have another go** | unchanged |
| `action_show_evidence` | unchanged | 调证据 → **查看原文** |
| `ask_unavailable_offline` | unchanged | 当前离线，联网后再提问。 → **现在没有网络，联网后再提问。** |

## Welcome and profile

| String | English: before → after | Chinese: before → after |
|---|---|---|
| `profile_sample_body` | Lin has been using the app for a few days: three courses played, one clue to review today, one case left half-way. → **Lin has been using the app for a few days: three courses played, one clue to review today, one case half-way through.** | unchanged |
| `profile_switch` | unchanged | 切换身份 → **切换用户** |
| `profile_local_note` | unchanged | 不需要账号。每个身份的进度都单独保存在这台手机上，切换不会删除任何内容。 → **不需要账号。每个用户的进度都单独保存在这部手机上，切换不会删除任何内容。** |
| `profile_guest_body` | unchanged | 以访客身份，直接玩预设课程。 → **以访客身份，直接玩现成的课程。** |

## Me tab

| String | English: before → after | Chinese: before → after |
|---|---|---|
| `home_load_error` | Some local records could not be read. They have not been changed or deleted. Statistics may be incomplete; please retry. → **Some saved records couldn't be read. Nothing was changed or deleted. The numbers may be incomplete, so please retry.** | 部分本机记录暂时无法读取；未修改或删除原记录。统计可能不完整，请重试。 → **有些保存在手机里的记录读不出来，没有被修改或删除。统计可能不完整，请重试。** |
| `home_resume` | Continue your session → **Pick up where you left off** | unchanged |
| `home_stat_documents` | Documents → **Files** | 本机资料 → **文件** |
| `home_stat_practiced` | Practiced units → **Topics practiced** | unchanged |
| `home_stats_note` | Local records, including presets. Practiced does not mean mastered. → **Counted from this phone, including the sample courses. Practiced doesn't mean you've learned it.** | 仅统计本机记录，含预生成示例；已练习不等于已掌握。 → **只统计这部手机上的记录，包括现成示例。练过不等于已经学会。** |
| `home_account_body` | You are using a local profile. Your name, character and progress are saved on this device. Accounts and cloud sync are not connected yet. → **No account needed. Your name, character and progress are saved on this phone. Signing in and syncing to other devices aren't available yet.** | 当前使用本机资料，无需登录。昵称、角色和学习记录保存在此设备；尚未接入账户与云同步。 → **不用登录。你的昵称、角色和学习记录都保存在这部手机上。暂时不能登录账号，也不能同步到其他设备。** |
| `home_local_profile` | LOCAL PROFILE → **SAVED ON THIS PHONE** | 本机个人资料 → **只在这部手机上** |
| `home_learn_subtitle` | Start with the basics. Understand through conversation. → **Answer a few quick questions, then get an explanation.** | 先摸清基础，再通过对话读懂文档。 → **先答几道小问题，再听讲解，把文档读懂。** |
| `home_practice_subtitle` | Practice and review by document, independent of introductory questions. → **Practice and review one file at a time.** | 按资料做题与复习，与基础摸底分别记录。 → **按文件做练习、复习。** |
| `home_read_first` | FOUNDATIONS → CONVERSATION → **A FEW QUESTIONS, THEN AN EXPLANATION** | 基础摸底 → 对话讲解 → **先答小问题，再听讲解** |
| `home_upload_title` | Bring a document to the conversation → **Bring a document to the explanation** | 把下一篇文档带进对话 → **把一份文档带进来讲解** |
| `home_read_first_action` | Read the source → **Read the original** | unchanged |
| `home_classic` | BY DOCUMENT · CHOOSE HOW TO PLAY → **BY FILE · CHOOSE HOW TO PLAY** | 按资料归档 · 可选玩法 → **按文件 · 选一种玩法** |
| `home_level_solid` | Consolidated → **Learned** | unchanged |
| `home_progress` | Learning progress → **Your progress** | unchanged |
| `history_units (one)` | Earlier practice history · %d unit with unknown content version → **Earlier practice · %d topic from older content** | unchanged |
| `history_units (other)` | Earlier practice history · %d units with unknown content version → **Earlier practice · %d topics from older content** | 早期练习记录 · %d 个单元的内容版本未知 → **更早的练习 · %d 个知识点来自旧内容** |
| `home_practice_title` | unchanged | 知识巩固 → **练习** |
| `home_upload_body` | unchanged | 选择一份 PDF。你确认后，它会发送到我们的服务器，由服务器准备几道起点题和对应的讲解。 → **选择一份 PDF。你确认后，它会发送到我们的服务器，由服务器准备几道开场小问题和对应的讲解。** |
| `nav_practice` | unchanged | 巩固 → **练习** |

## Quick questions and focus

| String | English: before → after | Chinese: before → after |
|---|---|---|
| `tutor_diagnostic_title` | Before we talk → **A few quick questions** | 先认识这篇文档 → **先答几道小问题** |
| `tutor_assessment_title` | Your starting point → **Where you are now** | 从你的理解开始 → **你现在的基础** |
| `tutor_coverage_note` | Introductory questions cover selected excerpts, not the entire document or your overall ability. Sources are traceable, not independently verified. → **These questions only cover a few passages, not the whole document, and they don't measure how much you know. You can see where each one comes from, but we haven't checked that it's correct.** | 基础摸底仅覆盖选取的文档片段，不代表整篇文章或你的整体能力。来源可追溯不等于内容已验证。 → **这些小问题只涉及文档里的几段，不是全文，也不能代表你的整体水平。每道题都能看到出处，但内容有没有错，我们没有核实过。** |
| `tutor_question_count` | FOUNDATION %1$d / %2$d → **QUESTION %1$d / %2$d** | 基础摸底 %1$d / %2$d → **第 %1$d / %2$d 题** |
| `tutor_diagnostic_note` | Answer from your current understanding. Not sure is welcome. Tapping an answer takes you to the next question. This is preparation for a conversation, not a scored practice game. → **Answer with what you know now. “I don't know yet” is fine. Tap an answer to go to the next question. This isn't a test: it only helps us decide where to start explaining.** | 按你现在的理解回答即可，也可以选不知道。点一个答案就会进入下一题。这里只用来找到讲解起点，不计入巩固成绩。 → **按你现在懂的回答就行，选“不知道”也可以。点一个答案就进入下一题。这不是考试，只是帮我们决定从哪里开始讲。** |
| `tutor_unknown` | I do not know yet → **I don't know yet** | unchanged |
| `tutor_evaluate` | See my starting point → **See where I am** | 查看理解起点 → **看看我的基础** |
| `tutor_assessment_note` | We will explain uncertain concepts first. A correct answer is evidence for this item only, not proof of mastery. Not sure stays separate from an incorrect answer. → **We'll start with the ideas you weren't sure about. One right answer doesn't mean you've learned the topic, and “I don't know yet” isn't counted as wrong.** | 我们先讲需要澄清或尚未判断的概念。本题答对不等于已经掌握；不知道与答错分开记录。 → **我们先讲你还不确定的概念。答对一道题不等于已经学会，选“不知道”也不算答错。** |
| `tutor_correct` | CORRECT ON THIS ITEM → **RIGHT** | 本题答对 → **答对了** |
| `tutor_clarify` | NEEDS CLARIFICATION → **WORTH GOING OVER** | 需要澄清 → **需要讲一讲** |
| `tutor_not_assessed` | NOT YET ASSESSED → **NOT SURE YET** | 尚未判断 → **还不确定** |
| `focus_other_note` | Saved on this device. Offline it cannot change the explanation; it can only steer an AI explainer when you are online. → **Saved on this phone. Without internet it can't change the explanation. With an AI explanation, it can steer what we cover.** | 只保存在本机。离线时它不会改变讲解；联网并接入 AI 讲解者后才能用来引导讲解。 → **只保存在这部手机上。没有网络时它改变不了讲解；用 AI 讲解、并且联网时，它才能影响讲解的内容。** |
| `focus_summary_note` | Your note: “%1$s” (saved on this device; this explainer cannot read it) → **Your note: “%1$s” (saved on this phone; this explanation can't use it)** | 你的备注：“%1$s”（只保存在本机；当前讲解者读不到） → **你的备注：“%1$s”（只保存在这部手机上，这份讲解用不到它）** |

## Explainer (conversation)

| String | English: before → after | Chinese: before → after |
|---|---|---|
| `tutor_chat_title` | Talk through the document → **Go through the document together** | unchanged |
| `tutor_model_badge` | DOCUMENT TUTOR · AI → **AI EXPLAINER** | 文档对话讲解 · AI → **AI 讲解** |
| `tutor_start` | Start the conversation → **Start the explanation** | 开始对话讲解 → **开始讲解** |
| `tutor_rephrase` | Rephrase → **Say it another way** | unchanged |
| `tutor_script_note` | This explainer follows a script written ahead of time. Your questions are saved, but it cannot answer them yet. → **This explanation was written in advance. You can type a question and it will be saved, but it can't answer yet.** | 这位讲解员按预先写好的内容讲解。你的追问会保存，但暂时不能回答。 → **这份讲解是提前写好的。你可以输入问题，会被保存下来，但现在还不能回答。** |
| `tutor_all_introduced` | All introductory concepts are covered. You can keep asking or return to your shelf. → **That's every idea covered. You can keep asking, or go back to your files.** | 基础概念已逐一讲解，可以继续追问，也可以返回资料页。 → **要讲的概念都讲完了。可以继续提问，也可以回到文件列表。** |
| `tutor_error` | Could not complete this step (%1$s). Saved records are retained. Retry or reload to reconcile with the server. → **That didn't work. %1$s Nothing you've done is lost. Try again, or reload.** | 这一步未完成（%1$s）。已保存记录仍保留；可重试，或重新读取以核对服务器状态。 → **这一步没成功。%1$s 你已经做的不会丢。可以再试一次，或重新加载。** |
| `tutor_reload` | Reload session → **Reload** | 重新读取会话 → **重新加载** |
| `tutor_offline` | Offline: showing saved answers and conversation. Reconnect for new server replies. → **You're offline. You can see what's saved. Connect to the internet to get new replies.** | 当前离线：正在展示已保存的回答和对话。新的服务端回复需要恢复连接。 → **现在没有网络，只能看已保存的内容。联网后才能收到新的回复。** |
| `tutor_unavailable` | This preset has no dedicated diagnostic yet. Its original passages are below. Pick a course that has an explanation, or import a document for the tutor flow. → **This sample doesn't have an explanation yet. Its original text is below. Pick a course that has one, or add your own PDF.** | 这个预生成资料暂时没有专用摸底题，下面保留其原文。可选择已有讲解的课程，或导入文档体验对话流程。 → **这个现成示例还没有讲解，原文在下面。可以选有讲解的课程，或者添加你自己的 PDF。** |
| `tutor_limit` | This session has reached its 40-turn limit. Existing conversation is retained. → **This conversation has reached its limit of 40 questions and replies. Everything so far stays saved.** | 本次会话已到 40 轮上限，现有对话仍保留。 → **这段对话已到 40 问 40 答的上限，已有内容仍保留。** |
| `tutor_scripted_note` | Written for this course ahead of time. Nothing is uploaded and no AI is running; your answers here stay on this device. → **Written in advance for this course. Nothing is uploaded, no AI is used, and your answers stay on this phone.** | 为这门课预先写好的内容。不上传文件、不调用 AI；你的作答只保存在本机。 → **为这门课提前写好的。不上传文件，不用 AI，你的回答只保存在这部手机上。** |
| `tutor_course_body` | A few foundation questions, then an explanation that starts where you are. Pre-written for this course; no upload, no AI. → **A few quick questions, then an explanation that starts where you are. Written in advance; no upload, no AI.** | 几道基础摸底，再从你的起点开始讲解。为这门课预先写好，不上传、不调用 AI。 → **先答几道小问题，再从你的基础开始讲。内容是提前写好的，不上传，也不用 AI。** |
| `tutor_practice_separate` | Practice is generated separately. It does not reuse your diagnostic as a score. → **Practice questions are made separately. Your answers to the quick questions don't count as a score.** | 巩固题单独生成，不把基础摸底当作练习成绩。 → **练习题是另外生成的。前面小问题的作答不计入成绩。** |
| `explainer_log` | Log → **History** | unchanged |
| `wrapup_line` | %1$s went through all %2$d foundation concepts with you. → **%1$s went through all %2$d key ideas with you.** | %1$s 陪你把 %2$d 个基础概念都过了一遍。 → **%1$s 陪你把 %2$d 个要点都过了一遍。** |
| `wrapup_note` | These are the foundations picked from the document, not the whole of it. Tap a page to reread the passage; the full conversation stays in Log. → **These are key ideas picked from the document, not all of it. Tap a page to reread the passage; the whole conversation is in History.** | 这些是从文档里挑出的基础概念，不是全文。点页码可以回看原文；完整对话保留在“记录”里。 → **这些是从文档里挑出的要点，不是全文。点页码可以回看原文；完整对话保留在“记录”里。** |
| `wrapup_shelf` | Back to shelf → **Back to my files** | 回到书架 → **回到文件列表** |
| `explainer_diag_line` | %1$d quick questions first. “I do not know yet” is a fine answer. → **%1$d quick questions first. “I don't know yet” is a fine answer.** | unchanged |
| `tutor_teacher` | unchanged | 文档讲解 → **讲解员** |
| `tutor_send` | unchanged | 发送追问 → **发送问题** |
| `explainer_mode_answer` | unchanged | 追问回复 → **回答** |
| `explainer_ask` | unchanged | 追问 → **提问** |

## Import and sources

| String | English: before → after | Chinese: before → after |
|---|---|---|
| `import_received_title` | Prepare a document conversation → **Get this file ready for an explanation** | unchanged |
| `tutor_upload_consent` | Confirm to send this document to the server below. It reads the text and prepares a few starting questions. This is a practice server with no real AI, so the questions are simple stand-ins. A server set up differently may use a real AI. → **If you continue, this document is sent to the server shown below. It reads the text and prepares a few starting questions. This is a practice server with no real AI, so the questions are simple stand-ins. A different server might use a real AI.** | 确认后，文档将发送到下方服务器，由它读取文字并准备几道起点题。这是练习用服务器，没有真正的 AI，题目只是简单的占位；换用其他服务器时可能会用到真正的 AI。 → **点确认后，这份文档会发送到下面的服务器，由它读出文字并准备几道开场小问题。这是练习用的服务器，没有真正的 AI，所以问题都很简单，只是占位。换成别的服务器时，可能会用真正的 AI。** |
| `tutor_no_endpoint` | No server configured in this build. The offline fixed example is available. → **This version of the app isn't connected to a server. You can still try the built-in example.** | 此构建尚未配置服务器，可体验离线固定示例。 → **这个版本还没有连接服务器。你仍然可以试试自带的示例。** |
| `tutor_uploaded` | The document has been uploaded and its text extracted. → **The document is uploaded and we've read its text.** | 文档已上传并提取文字。 → **文档已上传，文字也读出来了。** |
| `tutor_preparing` | Preparing document-specific introductory questions… → **Writing a few questions about this document…** | 正在根据文档准备基础摸底题… → **正在根据文档准备开场小问题…** |
| `tutor_upload_start` | Confirm and prepare → **Confirm and start** | 确认并准备摸底 → **确认并开始** |
| `tutor_import_error` | Unable to continue (%1$s). Check the server connection and file. Successful uploads are reused during this import. → **Can't continue. %1$s Check your internet and the file, then try again. A file that already went up won't be sent twice.** | 暂时无法继续（%1$s）。请检查服务器连接和文件；本次导入中已成功上传的文档会复用。 → **没能继续。%1$s 请检查网络和文件后再试。已经传好的文件不会重复上传。** |
| `tutor_import_cancel_note` | Cancel stops this screen from accepting late results. A document already received by the server is retained; cancellation is not deletion. → **Cancel stops this screen. If the server already got your document, it keeps it; cancelling doesn't delete it.** | 取消后，本页面不接收迟到结果。服务器已收到的文档仍会保留，取消不等于删除。 → **取消会停止这一页的等待。如果服务器已经收到文档，它会保留；取消不会删除文档。** |
| `tutor_separate_example` | Preview with a separate self-authored example. It is not generated from the selected file. → **Or try a separate example we wrote ourselves. It isn't made from the file you picked.** | 也可以切换到独立的自拟示例预览。示例并非从所选文件生成。 → **也可以先试试我们自己写的示例。它不是用你选的文件做的。** |
| `tutor_demo_action` | Try the fixed tutorial → **Try the built-in example** | 体验固定示例讲解 → **试试自带的示例** |
| `source_attach_mismatch` | This isn't the exact file this course was measured on (its fingerprint doesn't match), so nothing can be marked on it. Showing the extracted text. Expected: %1$s, %2$s; this file is %3$s. If that exact download is still refused, the course's page layout needs re-measuring. → **This doesn't look like the same file this course was made from, so we can't mark passages on it. Showing its text instead. We expected %1$s, %2$s; this file is %3$s. If you downloaded the right file and still see this, the course needs updating on our side.** | 这不是制作本课程时测量的那个文件（文件指纹不一致），无法在上面标出原句。继续显示提取的文字。需要的文件：%1$s，%2$s；这个文件为 %3$s。如果下载的正是这个文件仍被拒绝，说明本课程的页面版式需要重新测量。 → **这个文件和制作本课程时用的不是同一份，所以没法在上面标出原句，先显示它的文字。我们需要的是 %1$s，%2$s；你选的这个是 %3$s。如果你下载的就是对的文件还是这样，说明这门课需要我们这边调整。** |
| `source_attach_too_large` | That file is over 20 MB. Showing the extracted text. → **That file is over 20 MB. Showing its text instead.** | 文件超过 20 MB。继续显示提取的文字。 → **文件超过 20 MB。先显示它的文字。** |
| `source_attach_not_pdf` | That file isn't a PDF. Showing the extracted text. → **That file isn't a PDF. Showing its text instead.** | 这个文件不是 PDF。继续显示提取的文字。 → **这个文件不是 PDF。先显示它的文字。** |
| `source_attach_unreadable` | That file couldn't be read or kept. Showing the extracted text. → **That file couldn't be read or kept. Showing its text instead.** | 无法读取或保存这个文件。继续显示提取的文字。 → **无法读取或保存这个文件。先显示它的文字。** |
| `source_attach_unplaced` | PDF attached. This passage has no position on its page, so its text is shown here. → **PDF attached. We can't tell where this passage sits on the page, so its text is shown here.** | PDF 已附上。这段原文在页面上没有位置信息，因此在这里显示文字。 → **PDF 已附上。我们没法确定这段话在页面上的位置，所以在这里显示文字。** |
| `tutor_uploading` | unchanged | 正在上传并读取文档… → **正在上传，并读取文档里的文字…** |

## Materials and files

| String | English: before → after | Chinese: before → after |
|---|---|---|
| `material_practice` | Practise → **Practice** | unchanged |
| `file_kind_course` | Course pack → **Course** | 课程包 → **课程** |

## Spaces and route map

| String | English: before → after | Chinese: before → after |
|---|---|---|
| `spaces_error` | Could not finish · %1$s → **That didn't work. %1$s** | 无法完成 · %1$s → **没能完成。%1$s** |
| `route_fixture_badge` | Test content → **Sample content** | 测试内容 → **示例内容** |
| `spaces_new` | unchanged | 新建空间 → **新建学习空间** |
| `spaces_name` | unchanged | 空间名称 → **学习空间名称** |
| `spaces_rename` | unchanged | 重命名空间 → **重命名学习空间** |
| `spaces_open` | unchanged | 进入空间 → **进入学习空间** |
| `spaces_remove` | unchanged | 从这个空间移除 → **从这个学习空间移除** |
| `spaces_upload_info` | unchanged | 这些文件会发送到服务器，用来生成讲解，并加入这个空间。 → **这些文件会发送到服务器，用来生成讲解，并加入这个学习空间。** |
| `spaces_included` | unchanged | 会用这个空间里的文件来制定你的学习路线。 → **会用这个学习空间里的文件来制定你的学习路线。** |
| `spaces_no_existing` | unchanged | 现有资料都已加入此空间。 → **现有资料都已加入此学习空间。** |
| `spaces_unavailable` | unchanged | 此空间暂时无法打开。 → **这个学习空间暂时打不开。** |
| `route_empty_body` | unchanged | 生成路线后，第一步会出现在地图上。 → **制定学习路线后，第一步就会出现在地图上。** |

## Playground and games

| String | English: before → after | Chinese: before → after |
|---|---|---|
| `games_generated` | Generated with %1$s → **Made with %1$s** | 由 %1$s 生成 → **由 %1$s 制作** |
| `games_fixture` | Test provider · %1$s → **Practice server · no real AI** | 测试生成服务 · %1$s → **练习用服务器 · 没有真正的 AI** |
| `games_imported` | Installed archive · provider unverified → **Added from a file · source not checked** | 已安装的内容包 · 生成来源未验证 → **从文件添加 · 来源没有核对过** |
| `activity_missing` | This content version is unavailable. → **This activity isn't available any more.** | 此内容版本暂不可用。 → **这个活动现在打不开了。** |
| `job_knowledge` | Organizing knowledge → **Reading your topic** | 整理知识 → **了解你的主题** |
| `job_package` | Packaging content → **Putting it together** | 打包内容 → **整理成成品** |
| `job_ready` | Ready to install → **Ready to add** | 可安装 → **可以添加了** |
| `job_install` | Install and open → **Add and open** | 安装并打开 → **添加并打开** |
| `job_failed` | Generation failed → **Couldn't make it** | 生成失败 → **没做成** |
| `job_partial` | Partially generated → **Only partly made** | 部分生成 → **只做出了一部分** |
| `job_partial_note` | Only a complete result can be installed. → **Only a finished game can be added.** | 仅可安装完整结果。 → **只有做完整的游戏才能添加。** |
| `job_regenerate` | Generate the whole activity again → **Make it again from the start** | 重新生成整个活动 → **从头再做一次** |
| `job_problem` | Request or installation failed · %1$s → **Something went wrong. %1$s** | 请求或安装失败 · %1$s → **出了点问题。%1$s** |
| `job_queued` | Queued → **In line** | 已排队 → **排队中** |
| `games_save_failed` | Could not save this move · %1$s → **Couldn't save this move. %1$s** | 未能保存此操作 · %1$s → **这一步没能保存。%1$s** |
| `job_waiting` | unchanged | 等待连接服务 → **等待连接服务器** |

## Characters

| String | English: before → after | Chinese: before → after |
|---|---|---|
| `avatar line · Big words, no rece` | Big words, no receipts. → **Big words. Where’s the proof?** | unchanged |
| `avatar line · Objection! Receipt` | Objection! Receipts, please. → **Objection! Show me the proof.** | unchanged |
| `avatar line · Got it — that’s an` | Got it — that’s an overclaim. → **Got it — that goes too far.** | unchanged |

## Error messages (new)

Before, a failure showed whatever was inside it: an error code (`TUTOR_STALE`, `CONNECTION_OR_FILE_ERROR`), a Java exception name, or the server's own English message, in both languages. Now `ui/common/Problems.kt` turns every code (the `ErrorCodes` of the contract plus the app's own) into one plain sentence, and any unknown code into "Something went wrong. Please try again." The generic wrappers (`spaces_error`, `job_problem`, `tutor_error`, `tutor_import_error`, `load_failed`, `games_save_failed`) now show that sentence.

| String | English | Chinese |
|---|---|---|
| `problem_generic` | Something went wrong. Please try again. | 出了点问题，请再试一次。 |
| `problem_offline` | Can't reach the server. Check your internet and try again. | 连不上服务器。检查一下网络，再试一次。 |
| `problem_not_connected` | This version of the app isn't connected to a server yet. | 这个版本的应用还没有连接服务器。 |
| `problem_server` | The server had a problem. Try again in a moment. | 服务器出了点问题，过一会儿再试。 |
| `problem_slow` | That took too long. Try again in a moment. | 等了太久，过一会儿再试。 |
| `problem_quota` | You've reached the limit for now. Try again later. | 暂时用完了可用的次数，稍后再试。 |
| `problem_not_found` | We couldn't find that. It may have been removed. | 找不到这个内容，可能已经被删除了。 |
| `problem_not_ready` | It isn't ready yet. Try again in a moment. | 还没准备好，过一会儿再试。 |
| `problem_denied` | The server didn't accept this request. Close the app, open it again and retry. | 服务器没有接受这次请求。关掉应用重新打开，再试一次。 |
| `problem_make_failed` | We couldn't make this one. Try again, or try a different topic. | 这个没能做出来。再试一次，或者换个主题。 |
| `problem_space_changed` | This Space was changed somewhere else, so your change wasn't saved. | 这个学习空间在别处被改过，所以你的改动没有保存。 |
| `problem_file_changed` | This file changed or was removed. Add it again. | 这个文件变了或已被删除。请重新添加。 |
| `problem_unsure_upload` | We're not sure this file was sent. | 不确定这个文件有没有传上去。 |
| `problem_conversation_changed` | The conversation changed in the meantime. Reload it and try again. | 这段对话中途有了变化。重新加载后再试。 |
| `problem_cancelled` | That request was cancelled. | 这次请求已取消。 |
| `problem_answered` | You've already answered these questions. | 这些问题你已经答过了。 |
| `problem_cancel_unsure` | We couldn't confirm the cancel. The server may still be working on it. | 没能确认取消成功，服务器可能还在处理。 |
| `problem_saved_data` | Something saved on this phone couldn't be read. | 手机里保存的内容有一部分读不出来。 |
| `problem_activity` | This activity isn't available right now. | 这个活动现在用不了。 |

## Texts built in code

| Where | English: before → after | Chinese: before → after |
|---|---|---|
| Reply to a typed question (course written in advance) | I can only walk through the prepared explanation here, so I can't answer new questions on this course. Your question is saved in the log. → **This explanation was written in advance, so I can't answer new questions here. Your question is saved in History.** | 这门课我只能按事先准备好的讲解来讲，没法回答新的问题。你的问题已记在记录里。 → **这份讲解是提前写好的，没法回答新的问题。你的问题已记在对话记录里。** |
| Your first line in the conversation | Start from my results → **Start from my answers** | 按我的摸底结果开始讲解 → **按我答的情况开始讲解** |
| Server: answer without enough evidence | The document does not provide enough evidence for a reliable answer. Check the source or ask another question. → **I can't find enough in the document to answer that reliably. Check the original text, or try another question.** | 现有文档依据不足，暂时不能可靠回答这个问题。可以回到原文核对或换一个问题。 → **文档里没有足够的依据，我没法可靠地回答。可以看看原文，或者换个问题。** |
| Practice server: the wrong option | The material contains no readable text. → **The document doesn't say this.** | 这段材料没有任何可阅读的文字。 → **文档里没有这样的内容。** |
| Practice server: explanation | ...This is rule-based test teaching, not AI analysis. → **...This explanation comes from a fixed rule on the practice server, not from AI.** | ...这是规则生成的测试讲解，不是 AI 概念分析。 → **...这段讲解由练习用服务器按固定规则生成，不是 AI 写的。** |
| Practice server: say it another way | ...Mock mode quotes rather than interprets it. → **...The practice server can only quote the text, not put it in other words.** | ...mock 模式只能引用原文，不能真正改写其含义。 → **...练习用服务器只能引用原文，没法换种说法。** |
| Practice server: example | A study example: read the statement, close the document, paraphrase, then compare. This demonstrates reading, not a new factual inference. → **A study tip: read the sentence, close the document, say it in your own words, then compare with the original. This is a way to study, not something new from the document.** | 演示学习方法：先读这句话，合上文档，用自己的话复述，再对照原文。此例演示阅读动作，不是从文档推导的新事实。 → **学习小技巧：先读这句话，合上文档，用自己的话复述，再对照原文。这只是一种学习方法，不是文档里的新内容。** |

## Other things that were on screen and are not any more

| Where | Before | After |
|---|---|---|
| Materials, practice list of a file with several versions | `Course title · <content version>` | course title only |
| Source reader (`MaterialReader`) | `3 · c12` (page and passage id) | Page 3 / 第 3 页 |
| Cross-examination evidence picker | `p.3` in both languages | Page 3 / 第 3 页 |

## Guard tests

- `journeys/PlainLanguageGuardTest`: (1) every string, plural and string-array resource, English and Chinese, against the banned words and raw error codes; (2) the explainer's own sentences built in code; (3) the real app driven through welcome, Spaces list, a route map and one of its steps, materials and a file menu, the Me tab, the practice list, play-as, a case briefing, a verdict, the cross-examination and the recap, then the explainer (focus step, every quick question, where you are now, explainer pages, the choice bar, the ask sheet), in English and in Chinese. Everything a student can read or hear (text and content descriptions) is checked; a course's own content (titles, passages, questions) is exempt.
- `PlainLanguageScreensTest`: every error code maps to a plain sentence in both languages, and the import screen is checked in each of its states (no server, uploading, uploaded, every error).
- Tests that asserted old literal texts now assert through `R.string` ids (`ReadingFlowTest`, `ExplainerFlowTest`, `GenerationJourneyTest`) or the new wording (`LearnJourneysTest`, `PresetAttachTest`, `TutorRulesTest`).

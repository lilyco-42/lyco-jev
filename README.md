# lyco-jev

在 [jev-chat/jev-chat-jarvis](https://github.com/jev-chat/jev-chat-jarvis)（7.2k★, MIT）之上做二开：把**真正的 Jev-Style 权重放到端侧**，并补齐识图与长期记忆。

## 目标与现状

| # | 目标 | 现状 |
|---|---|---|
| 1 | 模型尽量小、手机免费跑 | **已在设备上跑通**（x86_64 模拟器，2 个 instrumented 测试全过）：llama.cpp 加载真 GGUF → 渲染 → 解码 → 读 `->` 槽位 yes/no logits → 校准 → answers。过程中修掉一个真 bug：`llama_get_logits_ith` 收的是**batch token index**，不是输出序号，传错会在 `llama_context::get_logits_ith` 里 abort。**端侧判读层已落地**：`app/src/main/cpp/jev_jni.cpp`（llama.cpp 打分器）+ `LocalJevRenderer.kt`（`macjev-render-v1` 渲染）+ `LocalJudgeClient.kt`（读数 + 校准 + 复用云端同一套 `answers` 解析）。权重 Jev-Style-0.8B Q4_K_M = 0.53 GB，Apache-2.0。PC 上已用真权重验证判读正确性。 |
| 2 | OCR + YOLO | `capture/vision/YoloDecode.kt`（**纯逻辑、无 Android 类型、单测覆盖**）+ `YoloDetector.kt`（ONNX Runtime 与坐标映射）+ `VisionFusion.kt`（把 YOLO 框与 ML Kit OCR 行配对成一句可判读的描述）。解码支持 YOLOv8 `[1,4+nc,A]` 与 YOLOX `[1,A,5+nc]` 两种布局。资源由 `tools/fetch_vision_assets.ps1` 放入 `app/src/main/assets/models/yolo/`。 |
| 3 | 无障碍 + 悬浮窗 | 直接复用上游读屏 / 悬浮窗 / 适配器；新增 `PROVIDER_LOCAL` 与设置页「本地端侧（离线）」入口。自动回复**已接线**：`AutoReplyPolicy`（6 单测）+ `ChatAppAdapter.sendNode`（默认 null = 永不发送）+ `ChatCaptureService.sendNow`。**默认关闭**（`prefs.autoSend = false`），失败即不发。整条链已在**设备上端到端验证**（闸门 → 填字 → 按 viewId 找发送键 → 点击 → 界面观察到已发）。 |
| 4 | 长期记忆 + 滑动窗口 | **已实现**：`core/kb/EmbeddingIndex.kt`（bge-small-zh-v1.5 int8, 22.9 MB）+ `MemoryRetriever.kt`（语义相似度 + 半衰期滑窗），`ContextBuilder` 在 `prefs.semanticMemory` 打开且资源存在时改用检索，否则**逐字回退**到上游子串匹配。

## 端侧判读是怎么接进去的

上游把判断接口抽象成 `POST /v1/systemone`，返回一个 `answers` 对象。端侧路径不新增协议，只在 `JudgeClient.postDecisions` 里分流：

```kotlin
if (prefs.judgeProvider == Prefs.PROVIDER_LOCAL) {
    return LocalJudgeClient(prefs.appContext).decide(state, questions)
}
```

因此**题面（`JevQuestions.kt`）、状态构造（`buildState`）、结果解析全部共用一份代码**，本地/云端只差一次前向还是一次 HTTP。

读数严格按参考实现 `macjev-readout-v1`：

```
score(option k) = logit(" yes") - logit(" no")   # 在第 k 个 " ->" 的最后一个 token
P = softmax(score / T)                            # T = readout_config.json 的 global = 0.880
```

## 构建

前置：JDK 17、Android SDK（NDK 30.0.15729638）、CMake 3.22.1。

```powershell
# 1) 端侧打分器（arm64）。首次会 FetchContent 拉取 llama.cpp 的固定提交 441df11f。
powershell -ExecutionPolicy Bypass -File tools\build_native.ps1

# 2) 重量化权重（0.53 GB，不进 git）
curl.exe -sSL -o app\src\main\assets\models\jev-style\Jev-Style-0.8B-Decision-v3-Q4_K_M.gguf ^
  https://huggingface.co/chaoliangUNSW/Jev-Style-0.8B-Decision-v3-GGUF/resolve/main/Jev-Style-0.8B-Decision-v3-Q4_K_M.gguf

# 3) 打包
.\gradlew.bat :app:assembleDebug
```

## CI（GitHub Actions）

仓库：**https://github.com/lilyco-42/lyco-jev**（public，走免费 Actions 额度）。

[.github/workflows/android.yml](.github/workflows/android.yml) —— 骨架抄自 [android/nowinandroid 的 Build.yaml](https://github.com/android/nowinandroid/blob/main/.github/workflows/Build.yaml)（`checkout@v4 → setup-java@v5 → gradle/actions/setup-gradle@v4 → upload-artifact@v4`），补上这个项目特有的两步：装 `ndk;30.0.15729638` + `cmake;3.22.1`（CMake FetchContent 会在 CI 上现场编 llama.cpp），以及拉 ~43 MB 模型资源（已在仓库里就跳过）。

当前**整条流水线绿，8m6s**（[run 36984257408](https://github.com/lilyco-42/lyco-jev/actions/runs/36984257408)，commit `4871be2`）：

```
Task :app:testDebugUnitTest          BUILD SUCCESSFUL in 50s
Task :app:buildCMakeDebug[arm64-v8a]
Task :app:buildCMakeDebug[x86_64]
Task :app:assembleDebug              BUILD SUCCESSFUL in 1m 30s
Starting 10 tests on emulator-5554 - 16
Finished 10 tests on emulator-5554 - 16
Task :app:connectedDebugAndroidTest  BUILD SUCCESSFUL in 1m 38s
artifacts: app-debug 561 MiB · unit-test-reports · instrumented-test-reports
```

**真机（模拟器）那半边也在 CI 里跑**：`reactivecircus/android-emulator-runner@v2`（KVM 开启与用法抄自该 action README 的示例），API 36 / google_apis / x86_64，跑 **10 个 instrumented 测试**（自动发送 2 + 冒烟 1 + 视觉 1 + 记忆 2 + OCR 3）。

`app-debug` 产物现在 **561 MiB**（zip）——因为 Jev-Style 权重已经打进 assets，见下面「权重打包进 APK」。CI 的 debug 包保留 arm64-v8a + x86_64 两个 ABI（模拟器要跑 x86_64）；分发给手机用的 arm64-only 包由 `-PdistAbi` 单独构建（623,029,300 B）。

`LocalJudgeInstrumentedTest` **故意不在 CI 里**：它需要 0.53 GB 的 GGUF，那个不进 git。本地这样跑：

```powershell
adb push Jev-Style-0.8B-Decision-v3-Q4_K_M.gguf /data/local/tmp/
.\gradlew.bat :app:connectedDebugAndroidTest `
  "-Pandroid.testInstrumentationRunnerArguments.class=com.jev.probe.jev.LocalJudgeInstrumentedTest"
```

**CI 抓到 4 个跨平台问题**，本地 Windows 一个都看不出来：

| # | 症状 | 根因 | 修法 |
|---|---|---|---|
| 1 | `Set up Android SDK` 失败 | `android-actions/setup-android@v3` 驱动 runner 自带 sdkmanager 时退出 1（且 `yes \| sdkmanager --licenses` 在 pipefail 下会因 SIGPIPE 假失败） | 删掉该 action，直接驱动预装 SDK |
| 2 | `exit code 126` | 从 Windows push 的 `gradlew` 记录为 100644，Linux 上不可执行 | `git update-index --chmod=+x gradlew`，并在 workflow 里 `chmod +x` 兜底 |
| 3 | `Cannot convert URL 'H:/android/keys/...' to a file` | Gradle 的 `file()` 在非 Windows 上把 `H:` 当 **URL scheme**，在建 keystore 就抛，`exists()` 根本轮不到 | 改用 `java.io.File` |
| 4 | `Unresolved reference: io` | Kotlin DSL 里 `java` 解析成 **Gradle 的 Java 扩展**，遮蔽了 `java` 包 | 顶部 `import java.io.File` |

第 3、4 条是上游遗传的 Windows-only 假设，开源给非 Windows 用户本来就是坏的。

仓库体积控制在 **2.4 MB / 118 文件**：`app/.cxx`（823 MB）、`app/build`（719 MB）、`app/src/main/assets/models`（43 MB 编码器/检测器）、以及 0.53 GB 的判读权重全部 gitignore —— 权重**不进 git**，由 CI 现拉、校验 sha256 后打进 APK。

## 分发与首次使用（v1.4 系列）

### 权重打包进 APK（v1.4-lyco.3）

`assets/models/jev-style/Jev-Style-0.8B-Decision-v3-Q4_K_M.gguf`（529,296,864 B）+ `androidResources { noCompress += "gguf" }`。装完首次判读**不再联网**。

代价：`LocalJevModel.ensure()` 是把 asset **解压**到 `filesDir`，所以手机上会有**两份** —— APK 594 MiB + 解压 505 MiB ≈ **1.1 GB**。

试过省掉这份重复（直接 mmap APK 里那个未压缩的 asset）：asset 在 zip 内部，`openFd()` 给的 fd 指向**整个 APK** 而不是那个条目，`/proc/self/fd/N` 那招不成立；手动算偏移量可行但复杂，且当时**没有真机可验证**，所以选了稳的解压路线。

### 默认走端侧（v1.4-lyco.2）

```kotlin
// Prefs.kt  改之前 —— 新装默认走云端，端侧藏在设置第 7 项
get() = sp.getString(K_JUDGE_PROVIDER, PROVIDER_OPENROUTER) ?: PROVIDER_OPENROUTER
```

这个项目的前提就是端侧、离线、免费，默认却是 OpenRouter —— 是做错了。改成 `PROVIDER_LOCAL`；已手动选过的用户不受影响（存储值优先）。同时给首次准备加了提示（原来静默拉/解 0.53 GB，界面看起来就是卡住一分钟）。

### 首次准备不能静默（v1.4-lyco.4）

用户报「点测试，一点效果都看不到，下载进度也没有」—— 两个都是真 bug：

1. **测试按钮的 worker 没有 try/catch**。任何异常（native 加载失败、磁盘写满、解压出错）都会让线程直接死掉，`main.post` 永不执行，标签**永久停在「测试中…」** —— 是「什么都没发生」，不是「报了错」。
2. **准备那 505 MB 完全没有进度上报**。半 GB 复制而界面不动，和卡死观感上一样。

修法：`ensure(ctx, onProgress)` 每约 8 MB 报一次（解压/下载两条路都报）、worker 全程 try/catch（失败必给耗时与原因）、**解压前空间预检**。

```
准备端侧模型 123/505 MB（24%）
失败（1234ms）：手机存储不足：端侧模型需要约 505 MB，当前可用 210 MB
```

### 下载目录：两个 manifest 曾经分叉

站点页面是 `fetch('/downloads/manifest.json', {cache:'no-store'})` 渲染的（文件在 `/var/www/studio/downloads/manifest.json`），OSS 桶里另有一份。**两者一度不一致**：只更新并验证了 OSS 那份，于是"manifest serves lyco-jev: YES"为真、页面却看不到它。现在由 `tools/oss_add_lyco_to_site_manifest.py` 同时写两边，不再分叉。

另一个坑：pingap 里**光加 `[locations.*]` 不够**，名字还必须列进 `[servers.https].locations`（那个列表同时是匹配优先级），否则请求**静默落到 `main` 兜底**、返回 uvicorn 的 404 —— `/downloads/` 就是这么 404 的。稳定入口 `lain42.top/lyco-jev-dl/`（hardlink，不带版本号）与 `dl.lain42.top/downloads/lyco-jev/`（OSS）都是版本无关地址，换版本只重指一次。

## 刻意没做

- **默认自动发送**：上游硬约束是「只填入、绝不发送」，本 fork 保留该默认（`autoSend = false`）。开启后每一轮仍要过 `AutoReplyPolicy`：总开关、会话白名单、危险等级上限（默认 3）、每小时最多 3 条；且**只有 adapter 显式交出 `sendNode` 的 App 才会被点发送**（当前仅 QQ，`id/send_btn`），其余一律退回「只填入」。没有判断结果时**失败即不发**。整条链（闸门 → `ACTION_SET_TEXT` → 按 viewId 找发送键 → `ACTION_CLICK` → 界面观察到 `sent:…`）已在**设备上端到端验证**（x86_64 模拟器，CI 每次 push 都跑，见 [AutoSendClickInstrumentedTest](lyco-jev/app/src/androidTest/java/com/jev/probe/AutoSendClickInstrumentedTest.kt)）；**真机 ARM64 仍未实测**。

### 把纯逻辑抽出来测，顺带抓到第二个真 bug（Round 8）

原来 YOLO 的解码算术和 ONNX/Android 代码混在一起，没法单测。抽成 `YoloDecode`（零 Android 依赖）并补上测试后，立刻发现 **anchors-major 布局（YOLOX）是坏的**：

- 旧代码把 `flatten()` 的 `(rows, cols)` 命名成 `(attrCount, anchors)`，于是 YOLOX 的张量被当成 `attrs=100 / anchors=85`；
- 遍历只扫 **85 个 anchor（应为 100）**，且 stride 用了行数而不是行宽 —— 每个属性都从错误的格子读；
- **它不抛异常，只是静默地什么都检测不到。**

复现：[yolox_regression_demo.py](tools/jev_local/yolox_regression_demo.py)

```
old  attrs_major=False attrs=100 anchors_scanned=85   objectness_hits=[] boxes=[]
new  attrs_major=False attrs=85  anchors_scanned=100  objectness_hits=[42] boxes=[(320.0, 240.0)]
```

修法：轴序由**属性个数**判定（`4+nc` = YOLOv8，`5+nc` = YOLOX），不再靠尺寸比例猜；stride 一律取行宽。回归用例直接构造真实 YOLOX 形状（100×85）钉住它。

同类地，目标 4 的滑窗衰减抽成 `MemoryScore`（`recency` / `blend`）并单测。

**单测总数 38 个全绿**（新增 `YoloDecodeTest` 6 个、`MemoryScoreTest` 4 个）。

### 第三个真 bug：输入尺寸写死 640，模型要 416×544（Round 9）

纯逻辑单测看不到这个，只有真跑一次 ONNX 才暴露：

```
ORT_INVALID_ARGUMENT: Got invalid dimensions for input: index 2 Got: 640 Expected: 544
```

而且真机上量到的输入是 **416×544（非正方形）**，写死 640 会让**整条识图链路在运行时直接抛异常**——编译通过、解码单测全绿、功能全死。

修法：输入尺寸从 **ONNX 会话元数据**里读（`session.inputInfo`），不再写常量，于是任何 checkpoint 都能直接换。

### 真机 ONNX 验证（3 个 instrumented 测试全过）

```
JEVVIS-IT: graph input 416x544
JEVVIS-IT: raw rows=4641 cols=85 attrsMajor=false attrs=85 anchors=4641 classes=80
JEVVIS-IT: detections=[] letterbox scale=0.65 pad=(0,116)
JEVMEM-IT: cos(related)=0.5208 cos(unrelated)=0.4694
JEVMEM-IT: query=[今天又惹她生气了，因为我临时改成加班] top=[n1:被放鸽子]
```

- YOLOX 的 anchors-major 布局在**真图**上得到确认：4641 anchors × 85 attrs，属性数正好 `5+80`。
- 目标 4 的核心主张被证实：查询与 `n1` **没有任何共同子串**，仍被正确检索出来（这正是上游子串匹配做不到的）。

### 记忆注入阈值：量出来再定（Round 10）

上一轮那个"0.28 形同虚设"的局限，用**同一份 int8 ONNX** 在 14 条笔记 × 10 条标注查询上量了一遍（[eval_memory.py](tools/jev_local/eval_memory.py)，不需要设备）：

| 指标 | 数值 |
|---|---|
| top-1 命中 | **9/10**（唯一没中的那条是我故意写得很绕，与干扰项**打平**在 0.624） |
| 相关笔记余弦 | 0.578 – 0.777（均值 0.687） |
| 最佳干扰项余弦 | 均值 0.553，最高 0.624 |
| 旧阈值 0.28 保留 | **13.8 / 14 条**（等于不过滤） |
| 新阈值 0.50 保留 | 4.6 / 14 条（注入上限就是 5），且**目标一条没丢** |

所以把 `MIN_NOTE_SCORE` 从 0.28 提到 **0.50**。也试了相对阈值（"取与最高分相差 delta 以内"），**否决**：它会塌缩到约 1.2 条，把互补的上下文全丢掉。

**并且必须说清它买到了什么**：干扰项均值 0.553 **高于** 0.50，所以这个阈值管的是**注入条数的上限**，不保证注入的内容相关——真正决定相关性的是排序。我最初的测试写成了"这个阈值会挡掉测出来的干扰项区间"，测试直接失败把我纠正了；现在注释和测试都按实际情况写。

策略本身抽成纯函数 `MemoryScore.selectByScore` 并有单测覆盖。

### 融合几何也测了（Round 11）

`VisionFusion` 原来长在 Android 的 `Rect`/`RectF` 上，JVM 单测构造不出来（stub 会抛）。把几何抽成 Android 无关的 `Rect4` + 纯函数 `describe(objects, texts)`，只留一个 bitmap 重载接触 Android。现在有 8 个单测覆盖：标签计数、文字顺序、**"文字落在物体内"才算配对**（擦边不算）、完全包含/完全分离的覆盖率、空输入、配对数上限。

**单测总数 49 个全绿**（新增 `VisionFusionTest` 8 个）。

## 设备验证与已知问题

```
SUITE com.jev.probe.jev.LocalJudgeInstrumentedTest  tests=2 failures=0 errors=0
  - nativeScorerReadsYesNoLogits (26.8s)   tiny scores = [-1.2151833, -0.527092]
  - oneQuestionRunsEndToEnd     (522.1s)   true_intent -> choice=close_topic, p=0.468
```

### 延迟：模拟器结论已被推翻

模拟器上 31 token 要 18–21 s（约 0.6 s/token），当时据此判断"不可用"。改用官方 `jev-score`（llama.cpp，WSL，16 线程，AVX-512）在**同一台机器**上实测：

| 环境 | 输入 | 耗时 | 吞吐 |
|---|---|---|---|
| torch bf16 (Windows CPU) | 891 tok | 6.2 s | ~144 tok/s |
| **llama.cpp Q4_K_M (WSL 16 线程)** | **1139 tok / 3 题** | **2.7 s** | **~420 tok/s** |
| llama.cpp Q4_K_M (Android x86_64 模拟器) | 31 tok | 18.2 s | ~1.7 tok/s |

模拟器比同机原生慢约 **250×**（guest 无 AVX-512 + QEMU 开销），所以那个数字对真机没有参考价值。真机仍是 ARM64 + NEON/dotprod，需实测，但设计**不再是明显不可行**。

### 4 比特量化：经检验是清白的

同一份 state、同一套题，Q4_K_M 与 bf16 的 top-1 完全一致，概率差异约 1–10% 相对（danger 期望 2.35 vs 2.34，`should_reply_now` p(true) 0.210 vs 0.230）。

### 真正的 bug 是题面：App 的 prompt 不适应开源权重

同权重、同运行时、同 state，**只换题面**：

| 题面 | top-1 | p |
|---|---|---|
| `spec.py` 精简 criteria | **confirm_you_care** | 0.463 |
| App 的 `JevQuestions.kt` 完整 criteria + `BACKGROUND_NOTE` | **close_topic** | 0.327 |

而这句「上次说好陪我看的那个电影，你还记得是哪部吗」正是 App 自己 instruction 里点名的 `confirm_you_care` 场景。原因就是：**App 的题面是为云端 TypeSafe Jev 校准的，直接搬到 Jev-Style 0.8B 上不成立**。

### 已重新校准（Round 4）

用 [calibrate.py](tools/jev_local/calibrate.py)（10 条回归用例 + 消融，单次约 2.7 s）定位并修好了：

| 题面 | 命中 |
|---|---|
| App 原题面（云端 Jev 口径） | **6/10** |
| 只把 criteria 改短（instruction 不动） | 8/10 |
| 全短 | 8/10 |
| **v4：criteria 短且只写正向、不含"不是另一个标签"** | **9/10** |

两条经验，已被 [JevQuestionsCalibrationTest](lyco-jev/app/src/test/java/com/jev/probe/jev/JevQuestionsCalibrationTest.kt) 钉住：

1. **criteria 每条只留一句正向短句**（长段落值 2 个用例）。
2. **任何一条 criteria 都不许提到别的标签的信号词**。旧 `close_topic` 里写着 "Not a breakup, not 'don't contact me', not sarcastic 'I'm used to it'" —— 结果那句「没事，我习惯了」**以 0.807 被判成了 close_topic**。把 "I'm used to it" 挪进 `confirm_you_care` 才修好。

校准结果已写入 `JevQuestions.kt`，并用 [verify_kotlin_prompt.py](tools/jev_local/verify_kotlin_prompt.py)（直接从 Kotlin 源码里抽出题面再打分）确认**出厂代码本身是 9/10**，不是只在我本地草稿上成立。

#### Round 5：三题都过了一遍

| 题目 | 起始 | 现在 | 说明 |
|---|---|---|---|
| `true_intent` | 6/10 | **10/10** | v6：把记忆测试的触发语写进 instruction（"…or push you to answer with '你说啊' / 'then say it', that is a memory test"）——正是这一句把 `prove_you_know` 从 casual_chat 拉回来 |
| `should_reply_now` | 4/7，且**对一切都答 false** | **5/7** | v2：删掉大段"什么时候答 FALSE"，改成平衡的正向问法；"明天三点前把表格发我" 现在能答 true |
| `danger_level` | 5/7 | **5/7（不改）** | 试了短指令（4/7）、5 档量表（2/7）、针对性提示（5/7，修好 breakup 却弄坏 thanked）。结论：**10 档量表是对的**，残留是边界样本上的向下偏置 |

两处已写入 `JevQuestions.kt`，并由 `verify_kotlin_prompt.py` 从源码复验：

```
true_intent       10/10  misses=[]
should_reply_now  5/7    misses=['fact_pending', 'own_fault']
```

残留：`should_reply_now` 对"事实已在对话里但发问者没复述"的情形仍偏保守；`danger_level` 的 `breakup` 落在 6.94（≥7 才及格），硬加提示会把 `thanked` 顶出 0–2 区间，属于零和，故未改。

### 改题面后必跑：题面验收套件

四道题（`true_intent` / `should_reply_now` / `danger_level` / `best_reply`）合并成一个带阈值的门，**从 `JevQuestions.kt` 源码抽题面**打分，退出码非 0 即失败。阈值与依据见 [acceptance.md](../docs/acceptance.md)。

```bash
wsl -e bash -lc 'export https_proxy=http://172.22.112.1:7897 http_proxy=http://172.22.112.1:7897; \
  cd /mnt/d/Code/lyco_jev/tools/jev_local && \
  ~/.local/bin/uv run --python 3.12 --with tokenizers --with numpy python suite.py; echo EXIT=$?'
```

当前：`true_intent 10/10 · should_reply_now 5/7 · danger_level 5/7 · best_reply 4/4 → SUITE PASSED`

（`calibrate.py` / `calibrate2.py` / `calibrate3.py` / `rank_calibrate.py` 是探索期的单点脚本，保留用于复现结论；日常只跑 `suite.py`。）

**判读质量要单独校准**：同一段对话，PC 上 bf16 参考实现给 `confirm_you_care` 0.47，端上 Q4_K_M + 完整题面给 `close_topic` 0.468。输入并非同一份（PC 用的是精简 criteria 文本、bf16；端上是 App 完整 criteria、4 比特），所以这不是管线错误，但**4 比特量化 + 题面改动会翻转 top-1**，需要用真机 + 真题面做一次校准。

## 许可证注意（重要）

Ultralytics 的 **YOLOv8/v11 权重是 AGPL-3.0**，放进这个 MIT fork 会让整个 App 必须按 AGPL 发布。因此 `fetch_vision_assets.ps1` 强制用 `-ModelUrl` 显式指定权重，默认示例取的是 **Apache-2.0 的 YOLOX**（`cj-mills/yolox-coco-baseline-onnx` 的 `coco-yolox_tiny.onnx`，19.5 MB）。
- **微信**：8.0.52 起对普通无障碍服务混淆节点，社区绕法（伪装成 `SelectToSpeakService`）在新版本上未验证，不作为第一目标。

# 研究：Operit 的模型能力架构，以及 CLEOS 该怎么用

> 起因：玖玖指出我在"按功能配模型 / 图片识别 / 视频理解"这几块一直在闭门造车，
> 参考了 Operit 却只看 README 就回来写代码。
>
> 这份文档是**把源码读透之后**的结论：它怎么做的、为什么这么做、
> **哪些适合 CLEOS、哪些不适合**，以及我们该落成什么样。

---

## 一、Operit 的四层结构

```
① 模型配置（ModelConfigData）   —— 一条配置 = 地址 + 型号 + 参数 + **能力声明**
② 功能映射（FunctionalConfigManager） —— FunctionType → (配置 id, 型号下标)
③ 能力调度（EnhancedAIService） —— 把能力状态算出来，交给提示词层和工具层
④ 取值路径（read_file / 附件处理） —— 按能力选：直发 / 识图模型 / 本地 OCR
```

**关键：它不在代码里硬编码"发什么"，而是把"能力状态"告诉模型，让模型自己决定。**

---

## 二、逐层看它怎么做的

### ① 能力是配置上的**声明**，默认全 `false`

`data/model/ModelConfigData.kt:178`：

```kotlin
val enableDirectImageProcessing: Boolean = false,   // 能不能直接吃图
val enableDirectAudioProcessing: Boolean = false,   // 能不能直接吃音频
val enableDirectVideoProcessing: Boolean = false,   // 能不能直接吃视频
val enableToolCall: Boolean = true,                 // 能不能用原生工具调用
```

**注意 `Direct` 这个词，和默认值全是 `false`。**

它的含义是**"这个模型自己能直接吃这种媒体"**，不是"你把它指派到这个功能了"。
**没证明过的能力，一律当作没有。**

**这一条直接解释了玖玖遇到的问题**：她在「图像识别」配了模型，
但那条配置上**没有开 `enableDirectImageProcessing`**（我们的配置里压根没这个字段），
所以 `hasImageRecognitionConfigured()` 返回 `false`，调度走不到识图那条路。

### ② 功能映射：永远有默认值，删配置会自己收拾

`data/preferences/FunctionalConfigManager.kt`：

```kotlin
data class FunctionConfigMapping(val configId: String = DEFAULT_CONFIG_ID, val modelIndex: Int = 0)
const val DEFAULT_CONFIG_ID = "default"
```

**三个值得抄的点**：

1. **没配的功能指向 `"default"`，不是"没有"** —— 永远有个能用的默认，不会出现"这个功能谁都指不上"
2. **`normalizeMapping` 从"全默认"起手，只覆盖认识的功能名** ——
   **加新功能自动有默认值，删掉的功能自动消失**，不需要为每次加功能写迁移
3. **`remapDeletedConfigReferences`** —— 删一条配置时，**自动把引用它的功能退回默认**，
   并**返回受影响的清单**（`affectedFunctions`）让人知道

**第 3 条正是玖玖说的"存了之后就很难受"该有的东西。** 我们现在删配置什么都没发生，
引用的功能就悬空了。

### ③ 能力状态是**喂给提示词的**，不是给代码 if 用的

`api/chat/EnhancedAIService.kt:2685`：

```kotlin
val hasImageRecognition = multiServiceManager.hasImageRecognitionConfigured()
val chatModelHasDirectImage = config.enableDirectImageProcessing
```

这两个值一路传到 `SystemPromptConfig.getSystemPromptWithCustomPrompts(...)`。

而 `core/config/SystemToolPrompts.kt:506` 用它们决定**工具长什么样**：

```kotlin
val shouldExposeIntent =
    (hasBackendImageRecognition && !chatModelHasDirectImage) ||
    (hasBackendAudioRecognition && !chatModelHasDirectAudio) ||
    (hasBackendVideoRecognition && !chatModelHasDirectVideo)
```

**成立时**：`read_file` 多出一个 `intent` 参数，描述也改了：

> "For media files, you can also provide an **'intent'** parameter to use a backend recognition model for analysis."

**不成立时**：这个参数根本不暴露。

**这就是整套设计的精髓**：

- **不自动转述** —— 系统**不**"每次收到图就调一次识图模型"
- **给模型一个工具** —— 告诉它"你有个办法看媒体文件，代价是要写清楚你想知道什么"
- **模型自己决定** —— 要不要看、看什么（`intent` 是模型根据用户的问题写的）

**比"自动转述"好在哪**：用户只是发了张图当背景、问题却和画面无关时，**不必浪费一次识图调用**；
而真要问画面里的东西时，**带过去的 intent 是精确的**（"图里的公式是什么"而不是"描述这张图"）。

### ④ 取值三级降级：直发 → 识图模型 → 本地 OCR

`core/tools/defaultTool/standard/StandardFileSystemTools.kt:1067`：

```kotlin
"jpg", "jpeg", "png", "gif", "bmp" -> {
    val intent = ...      // 模型写的意图
    val directImage = ... // 模型要求"直接给我看"

    // 情况1：模型要求直发 → 返回图片链接，让它自己看
    if (directImage) { ... return ToolResult(content = "<link type=\"image\" id=\"...\">") }

    // 情况2：有意图 → 调后端识图模型，带意图分析
    if (!intent.isNullOrBlank()) {
        val r = enhancedService.analyzeImageWithIntent(path, intent)
        return ToolResult(content = r)
    }

    // 情况3：都没有 → **本地 OCR**
    val ocrText = OCRUtils.recognizeText(context, bitmap)
}
```

**第三级是我完全没想到的**：

**本地 OCR 不需要模型、不需要联网、不要 Key、不花钱。**
发一张带字的图（截图、文档照、聊天记录截图）—— **绝大多数场景 OCR 就够了**。

`ConversationService.analyzeImageWithIntent(path, userIntent, ...)` 里，
图片链接和用户意图是拼在一起发出去的：

```kotlin
val prompt = if (userIntent.isNullOrBlank()) "$imageLink\n<默认提示>" else "$imageLink\n$userIntent"
```

### ⑤ 连接测试和能力探测是两件事，结果分三态

`api/chat/llmprovider/ModelConfigConnectionTester.kt`：

```kotlin
enum class ModelConnectionTestType { CHAT, TOOL_CALL, IMAGE, AUDIO, VIDEO }
enum class ModelConnectionTestOutcome { PASSED, UNVERIFIED, FAILED }
```

- **`PASSED`** —— 答对了暗号，**这个能力是真的**
- **`UNVERIFIED`** —— 请求成功但答错，**不能确认**（这不算失败！）
- **`FAILED`** —— 抛异常，**协议/连接层面不通**

报告里也分成两个语义：`success`（没有 FAILED，连得上）和 `verified`（全部 PASSED，能力都验过）。

**按能力开测**：只有 `config.enableDirectImageProcessing` 为真才测图片那项。

**暗号藏在素材里，不在提示词里**（`MediaCapabilityProbe`：图里印 `7K2Q`、音频念 `375`、视频显示 `M4XP`）——
**模型不可能靠猜蒙对**。而且音频那条连"念出来的英文数字"（`three seven five`）也算对。

---

## 二·补、把整条链路走通之后（第二轮研究）

上面那些是"某一层怎么做"。这一轮把**从配置到真正发出去**的整条链走通了，
有几个发现推翻了或者说细化了之前的判断。

### 补① 媒体在消息里是**标记文本**，不是对象

用户发的图/音/视频，进了消息之后是**一段 `<link type="image" id="...">` 标记**，
混在用户的那句话里。真正把它变成 API 里的 content 数组，是在 **provider 层**：

`api/chat/llmprovider/OpenAIProvider.kt:900`：

```kotlin
val mediaLinks = MediaLinkParser.extractMediaLinks(contentText)
val imageLinks = if (hasImages) MediaLinkParser.extractImageLinks(contentText) else emptyList()
val audioLinks = mediaLinks.filter { it.type == "audio" }
val videoLinks = mediaLinks.filter { it.type == "video" }

// 不支持就把标记从文本里**删掉**，并记一条 WARN
if (imageLinks.isNotEmpty() && !supportsVision) {
    AppLogger.w("检测到图片链接，但当前Provider不支持图片处理，已移除图片")
}

// 什么都不支持时，回一句人看得懂的话
if (!hasAnySupportedRichContent) {
    return when {
        imageLinks.isNotEmpty() -> context.getString(R.string.openai_image_omitted)   // "（图片已省略）"
        ...
    }
}

// 支持哪种才拼哪种
if (supportsVision) imageLinks.forEach { put("type", "image_url") }
if (supportsVideo)  videoLinks.forEach { put("type", "video_url") }    // ← 视频格式和 CLEOS 一致
if (supportsAudio)  audioLinks.forEach { put("type", "input_audio") }
if (supportsFiles)  fileLinks.forEach { put("type", "input_file") }
```

**这个设计好在**：

- **能力判断只在一个地方**（provider 层），不是散在业务代码里
- **不支持时给一句人话**（"图片已省略"），**既不静默丢弃、也不硬发过去等 API 报错**
- **媒体是标记文本，所以工具能看见它、模型也能看见它** —— 这就是 `read_file(intent)` 那条路能存在的前提

**顺带验证**：它的视频格式也是 `{"type":"video_url","video_url":{"url":"data:<mime>;base64,..."}}`
—— **和我在 0.1.34 里写的一致**，这条没做错。

### 补② 能力是怎么传下去的

```
ModelConfigData.enableDirectImageProcessing
      ↓
AIServiceFactory:310   val supportsVision = config.enableDirectImageProcessing
      ↓
OpenAIProvider(supportsVision = supportsVision, supportsAudio = ..., supportsVideo = ...)
      ↓
provider 决定拼进 content 还是换成"（已省略）"
```

**一条直线，没有任何一处"猜"它能不能。**

### 补③ 那三个能力位**在 Operit 里几乎从不打开**

我把全仓库搜了一遍：**没有任何地方把 `enableDirectImageProcessing` 等设成 `true`**，
唯一的例外是一键工具 `AutoGlmOneClickToolScreen:130`（它会开图片那个）。

**也就是说**："意图式识图"（`read_file(intent=...)`）是**设计好了但默认关着的功能**。
它的用户要自己开——**而界面上没有开它的开关**。

**这反而让我更确定了该学什么**：

| 学 | 不学 |
|---|---|
| **能力声明 + 默认 false** 的思路 | **把它做成"用户得自己找地方开"** —— 玖玖要的是省事 |
| **能力只在 provider/调度一处判断** | 分散到各处 |
| **不支持时说人话**（"图片已省略"） | 静默丢弃或让它报错 |
| **媒体是标记文本**，工具和模型都能看见 | —— |

**对 CLEOS 的做法**：能力**不该让用户手动开**，而该**靠"测试"自动确定**——
跑一次探针，通了就把这个能力记上。这正好把它的"声明"和"探测"两套东西合起来用。

### 补④ 本地 OCR 用的是 Google ML Kit

`util/OCRUtils.kt`：

```kotlin
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
```

**中文/日文/韩文/拉丁都支持，离线跑，不要 Key。** 还有 `Quality.LOW`（直接走 MLKit API，快）和高质量两档。

**对 CLEOS 要验证的一件事**：ML Kit 有 **bundled**（模型打进 APK）和 **unbundled**（首次从 Google 下载）两种依赖。
国内环境下 **unbundled 可能下不动**，所以要选 bundled 版本 —— **代价是 APK 会大几 MB**。

---

## 三、哪些适合 CLEOS，哪些不适合

### 适合，直接学

| 它的做法 | 为什么适合我们 |
|---|---|
| **能力是配置上的声明，默认 false** | 治我们的老毛病：以为配了就能用 |
| **没配的功能指向 default** | 我们现在的"跟着主模型走"就是这个意思，但要显式化 |
| **加功能自动有默认值**（normalizeMapping） | 我们每加一个 Feature 都要改迁移，该学它 |
| **删配置自动退回默认 + 报受影响清单** | 玖玖抱怨的"存了之后就很难受" |
| **能力状态写进提示词** | **最值得学的一条**，见下 |
| **`read_file(intent=...)` 让模型主动要求分析** | 按需、带意图，比自动转述好 |
| **本地 OCR 兜底** | 不要 Key、不要网、不花钱，截图场景够用 |
| **三态测试结果** | "答错"和"连不上"是两回事，混在一起会误导人 |
| **暗号在素材里** | 20 行代码，能省掉无数次"它到底看没看到"的猜疑 |

### 不适合，要么不做要么改造

| 它的做法 | 为什么不照搬 |
|---|---|
| **38 种 provider 枚举** | 我们有 `ApiPresets` + 自定义地址就够，枚举反而要跟着上游改 |
| **一条配置放多个模型（逗号分隔 + index）** | 它的历史包袱，我们用一条配置一个型号更清楚 |
| **Key 池 / 轮询** | CLEOS 是单机聊天应用，用不上 |
| **MNN / llama.cpp 本地推理** | 另一个量级的事，不碰 |
| **`useToolCallApi` 那套 XML/原生双协议** | 我们的工具调用已经跑通了，不动 |
| **所有参数（temperature 等 20 多个）都做进配置** | 它的用户是折腾型的，玖玖要的是省事 |
| **把能力状态塞进系统提示词的一大段 XML** | 我们的 prompt 结构不同，要按 CLEOS 的方式写（见下） |

---

## 四、CLEOS 的落地方案

### 数据层

**`ModelProfile` 加能力字段**（默认全 false，和它一致）：

```kotlin
val directImage: Boolean = false,
val directAudio: Boolean = false,
val directVideo: Boolean = false,
```

**功能映射**：我们已经有了 `featureProfileIds`（`Feature` → 配置 id），
要补的是**能力校验**：指向的配置没这能力 → **视为没配**。

**删配置要收拾**：照 `remapDeletedConfigReferences`，
删一条配置时把引用它的功能退回默认，**并把受影响的列出来**。

### 调度层：`CapabilityRouter`

把现在散在 `prepare` 里的 if 收进一个地方：

```
来了一张图
  ├─ 聊天模型 directImage？ → 直接发
  ├─ 「图像识别」指向的配置 directImage？ → 交给它转述
  ├─ 有本地 OCR？ → 本地认字（不要 Key、不要网）
  └─ 都不行 → 明说看不到
```

**每一级都要能说出"我为什么走了这条"** —— 这是我们现在最缺的。

### 提示词层（学它最值得学的一条）

**不自动转述。** 改成：

- **聊天模型自己能看图** → 图直接挂上去，正常聊
- **自己不能看，但配了识图模型** → **挂一个"看媒体"的能力告诉模型**：
  "对方发了图，你可以用 `look` 工具，里面写清楚你想知道什么"
- **模型调用时带着 `intent`** → 我们拿这个 intent 去调识图模型

**工具名和参数名要按 CLEOS 的风格取**（不用它的 `read_file` + `intent`，
我们的 `Tools.kt` 里已经有自己的体系）。

### 视频

**和图片走同一套**，只是第三级换掉：视频没有"本地 OCR 兜底"，
所以视频的降级是：**直发 video_url → 不识图不问 → 明说发不了**。

### 测试（照它那套）

**每个功能一个「测试」按钮**，跑对应的探针：

| 测什么 | 暗号 | 怎么算过 |
|---|---|---|
| 连接 | — | 回一句话 |
| 工具调用 | — | 一轮完整的调用往返 |
| **看图** | 画一张印着随机码的图 | **只回那个码** |
| **看视频** | 抽一帧印码，或一小段带码的视频 | 只回那个码 |
| 听声音 | 一段念数字的音频 | 回那三个数字 |

**结果分三态**：过了 / 没验证 / 不通。

**图我们自己画**（Canvas 印个随机码），不用打包素材 —— 而且**每次码都不同，蒙不了**。

---

## 五、施工顺序

1. **能力字段 + 删配置收拾**（数据层，不动界面行为）
2. **`CapabilityRouter`**（把散着的 if 收进去，行为不变，但能说出为什么）
3. **提示词层改成"给工具"**（真正的行为变化）
4. **本地 OCR 兜底**
5. **测试探针 + 每个功能的「测试」按钮**
6. **模型配置页：能力开关 + 测试入口**

**每步都能出包，每步都有测试。**

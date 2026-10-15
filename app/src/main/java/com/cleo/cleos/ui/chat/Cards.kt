package com.cleo.cleos.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.glass.GlassShape
import com.cleo.cleos.glass.GlassSurface
import com.cleo.cleos.glass.LocalGlassPalette
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * TA 做的一张卡片。**它只说"要什么"，样式由 App 画** —— 这是从 ChatGPT 那边学来的那条：
 * 让模型描述结构，别让它写样式。好处有三样：
 *
 * · **长相统一**：不管它怎么写，出来的都是这个 App 的玻璃样子；
 * · **不会崩**：没有代码在跑，字段缺了最多是少一行字；
 * · **手机上好按**：按钮尺寸、间距、点按反馈都是 App 管的，它不用猜。
 *
 * 要走自由发挥（小游戏、画图）的时候，还有 ```html 那条路（LiveWidget）。
 */
sealed interface ChatCard {
    val title: String?

    /** 选一个：点了哪一个，那句话就当成对方说的发出去。 */
    data class Choice(override val title: String?, val body: String?, val options: List<String>) : ChatCard

    /** 是 / 不是。 */
    data class Confirm(override val title: String?, val body: String?, val yes: String, val no: String) : ChatCard

    /** 让他写一句：卡片上带个输入框。 */
    data class Ask(override val title: String?, val body: String?, val placeholder: String, val send: String) : ChatCard

    /** 只是给人看的清单，不用点。 */
    data class Notes(override val title: String?, val items: List<Item>) : ChatCard {
        data class Item(val title: String, val detail: String?)
    }
}

object Cards {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * 读一张卡片。**认不出来就返回 null**，调用方把原文当普通文字显示 ——
     * 它写错一个逗号，代价是"这张卡没出来"，不是"这条消息炸了"。
     */
    fun parse(raw: String): ChatCard? = runCatching {
        val o = json.parseToJsonElement(raw.trim()).jsonObject
        val title = text(o, "title")
        val body = text(o, "body") ?: text(o, "text")
        // 没写 type 时按有的字段猜：有 options 就是选择题，有 items 就是清单。
        val type = text(o, "type")?.lowercase()
            ?: if (o["options"] != null) "choice" else if (o["items"] != null) "list" else null
        when (type) {
            "choice", "choose", "pick" -> {
                val options = strings(o["options"])
                if (options.isEmpty()) null else ChatCard.Choice(title, body, options.take(MAX_OPTIONS))
            }
            "confirm", "yesno" -> ChatCard.Confirm(
                title,
                body,
                yes = text(o, "yes") ?: text(o, "confirm") ?: "好",
                no = text(o, "no") ?: text(o, "cancel") ?: "算了",
            )
            "input", "ask", "fill" -> ChatCard.Ask(
                title,
                body,
                placeholder = text(o, "placeholder") ?: "写点什么…",
                send = text(o, "send") ?: "发过去",
            )
            "list", "notes", "info" -> {
                val items = (o["items"] as? kotlinx.serialization.json.JsonArray)?.mapNotNull { element ->
                    when (element) {
                        is JsonObject -> text(element, "title")?.let { ChatCard.Notes.Item(it, text(element, "detail") ?: text(element, "body")) }
                        is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotBlank() }?.let { ChatCard.Notes.Item(it, null) }
                        else -> null
                    }
                }.orEmpty()
                if (items.isEmpty()) null else ChatCard.Notes(title, items.take(MAX_ITEMS))
            }
            else -> null
        }
    }.getOrNull()

    private fun text(o: JsonObject, key: String): String? =
        (o[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.take(LABEL_MAX)

    private fun strings(element: kotlinx.serialization.json.JsonElement?): List<String> = when (element) {
        null -> emptyList()
        is kotlinx.serialization.json.JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
        is JsonPrimitive -> listOfNotNull(element.contentOrNull?.trim()?.takeIf { it.isNotEmpty() })
        else -> emptyList()
    }.map { it.take(LABEL_MAX) }

    private const val LABEL_MAX = 80
    private const val MAX_OPTIONS = 6
    private const val MAX_ITEMS = 12
}

/**
 * 把一张卡片画出来 —— 用的是和这个 App 别处一样的玻璃和字。
 *
 * [onAnswer] 只在人真的点了之后才调用：选的那个选项、是/不是、或者他填进去的那句，
 * 当成对方说的话走。所以卡片不是"另一种回答方式"，它就是**帮人少打几个字**。
 */
@Composable
internal fun ChatCardView(card: ChatCard, onAnswer: (String) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalGlassPalette.current
    var answered by remember(card) { mutableStateOf<String?>(null) }
    var typed by remember(card) { mutableStateOf("") }

    GlassSurface(
        modifier = modifier.fillMaxWidth(),
        shape = GlassShape.Rounded(20.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            card.title?.let { Text(it, color = palette.content, fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            when (card) {
                is ChatCard.Choice -> card.body?.let { Text(it, color = palette.contentSecondary, fontSize = 13.sp, lineHeight = 19.sp) }
                is ChatCard.Confirm -> card.body?.let { Text(it, color = palette.contentSecondary, fontSize = 13.sp, lineHeight = 19.sp) }
                is ChatCard.Ask -> card.body?.let { Text(it, color = palette.contentSecondary, fontSize = 13.sp, lineHeight = 19.sp) }
                is ChatCard.Notes -> Unit
            }

            when (card) {
                is ChatCard.Choice -> FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    card.options.forEach { option ->
                        CardAction(option, chosen = answered == option, enabled = answered == null) {
                            answered = option
                            onAnswer(option)
                        }
                    }
                }

                is ChatCard.Confirm -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CardAction(card.yes, chosen = answered == card.yes, enabled = answered == null, accent = true) {
                        answered = card.yes
                        onAnswer(card.yes)
                    }
                    CardAction(card.no, chosen = answered == card.no, enabled = answered == null) {
                        answered = card.no
                        onAnswer(card.no)
                    }
                }

                is ChatCard.Ask -> {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = { typed = it.take(500) },
                        placeholder = { Text(card.placeholder) },
                        singleLine = true,
                        enabled = answered == null,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (answered == null) {
                        TextButton(
                            enabled = typed.isNotBlank(),
                            onClick = {
                                val said = typed.trim()
                                answered = said
                                onAnswer(said)
                            },
                        ) { Text(card.send) }
                    }
                }

                is ChatCard.Notes -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    card.items.forEach { item ->
                        Row(verticalAlignment = Alignment.Top) {
                            Text("·", color = palette.contentSecondary, fontSize = 14.sp, modifier = Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title, color = palette.content, fontSize = 14.sp)
                                item.detail?.let { Text(it, color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 17.sp) }
                            }
                        }
                    }
                }
            }

            answered?.let { Text("已经回过了", color = palette.contentSecondary, fontSize = 11.sp) }
        }
    }
}

/** 卡片上的一颗按钮：玻璃里就用普通填充，别套第二层玻璃。 */
@Composable
private fun CardAction(label: String, chosen: Boolean, enabled: Boolean, accent: Boolean = false, onClick: () -> Unit) {
    val palette = LocalGlassPalette.current
    val ink = if (accent) palette.accentContent else palette.content
    val fill = when {
        chosen -> palette.accent.copy(alpha = 0.28f)
        accent -> palette.accent.copy(alpha = if (enabled) 0.22f else 0.10f)
        else -> palette.content.copy(alpha = if (enabled) 0.10f else 0.05f)
    }
    Row(
        modifier = Modifier
            .background(fill, RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = ink, fontSize = 14.sp)
    }
}

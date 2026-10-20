package com.cleo.cleos.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cleo.cleos.glass.LocalGlassPalette

/**
 * 电话和语音回复用哪个模型 —— **这个 TA 专属**的那份。
 *
 * 这是模型中心之前就有的老结构（独立的地址 + 型号，不挂在服务商下面）。
 * 这次整合没有把它搬进新体系，只是把入口从「模型」页挪到这里，**配置一个字没动**，
 * 所以老用户升级上来行为完全一样；想换的话还是在这一页改。
 *
 * 解析顺序见 `ChatRepository.spokenOrChat`：旧配置 > 模型中心的「语音/电话」绑定 > 全局默认。
 */
@Composable
internal fun SpokenModelPage(vm: SettingsViewModel) {
    val palette = LocalGlassPalette.current

    Section("电话和语音用的模型 · 这个 TA 的") {
        Text(
            "这是给这个 TA 单独准备的语音模型，只影响打电话和回你语音时它说的话。" +
                "平时打字、写信、前情提要还是用模型中心里的那个。",
            color = palette.contentSecondary,
            fontSize = 12.sp,
            lineHeight = 18.sp,
        )
        Spacer(Modifier.height(4.dp))
        ListCard {
            ExplainedSwitch(
                "打电话、发语音时换个模型",
                "电话里和回你语音时，用另一个模型",
                "打开后，打电话时 TA 说的话，和你发语音过去后 TA 回的那一条，由下面这个模型来写，比如想让 Claude 来说。" +
                    "平时打字、主动找你、写信、前情提要还是用模型中心的那个。" +
                    "模型中心里「语音/电话」如果也单独选了，这一条优先。",
                vm.spokenOn,
            ) { vm.spokenOn = it }
        }

        if (vm.spokenOn) {
            Section("模型") {
                ModelProfilesEditor(vm.spoken)
                ServiceChips(vm.spoken)
                ConnectionFields(vm.spoken)
                if (vm.spoken.baseUrl.isBlank() || vm.spoken.model.isBlank()) {
                    Text(
                        "地址和模型都填好才会换，之前还用模型中心那个。",
                        color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp,
                    )
                }
            }
        } else {
            Text(
                "关着的时候，打电话和语音回复都用模型中心里「语音/电话」挑的那个；" +
                    "那格也没挑，就用现在用的模型。",
                color = palette.contentSecondary, fontSize = 12.sp, lineHeight = 18.sp,
            )
        }
    }
}

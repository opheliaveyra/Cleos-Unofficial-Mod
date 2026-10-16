package com.cleo.cleos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 切模型时列出来的那些选项。
 *
 * **这个测试是为了"列表是空的"那次加的。** 原来只列"手动保存过的配置"，
 * 结果主模型明明配好了、列表却是空的 —— 人还得回去再填一遍地址，那跟没做一样。
 * 所以第一条就是：什么都不保存，主模型也必须在列表里。
 */
class ModelChoicesTest {
    private fun profile(name: String, url: String, model: String, forgotten: Boolean = false) =
        ModelProfile(id = name, name = name, baseUrl = url, model = model, forgotten = forgotten)

    @Test
    fun theMainModelIsAlwaysThereEvenWithNothingSaved() {
        val choices = ModelChoices.of(
            chatBaseUrl = "https://api.deepseek.com",
            chatModel = "deepseek-flash",
            spokenOn = false,
            spokenBaseUrl = null,
            spokenModel = null,
            profiles = emptyList(),
        )
        assertEquals(1, choices.size)
        assertEquals("deepseek-flash", choices[0].model)
        assertEquals(ModelChoices.WHERE_CHAT, choices[0].where)
    }

    @Test
    fun theSpokenModelShowsUpOnlyWhenItIsOn() {
        fun choices(on: Boolean) = ModelChoices.of(
            chatBaseUrl = "https://a/v1", chatModel = "chat",
            spokenOn = on, spokenBaseUrl = "https://b/v1", spokenModel = "speak",
            profiles = emptyList(),
        )
        assertEquals(1, choices(false).size)
        val on2 = choices(true)
        assertEquals(2, on2.size)
        assertEquals("speak", on2[1].model)
        assertEquals(ModelChoices.WHERE_SPOKEN, on2[1].where)
    }

    @Test
    fun savedProfilesComeLast() {
        val choices = ModelChoices.of(
            chatBaseUrl = "https://a/v1", chatModel = "chat",
            spokenOn = false, spokenBaseUrl = null, spokenModel = null,
            profiles = listOf(profile("备用", "https://c/v1", "other")),
        )
        assertEquals(listOf("chat", "other"), choices.map { it.model })
        assertEquals(ModelChoices.WHERE_SAVED, choices[1].where)
    }

    @Test
    fun theSameAddressAndModelIsListedOnce() {
        // 主模型和某个保存过的配置其实是同一个：只出现一次，不然列表里两行一模一样。
        val choices = ModelChoices.of(
            chatBaseUrl = "https://a/v1", chatModel = "chat",
            spokenOn = false, spokenBaseUrl = null, spokenModel = null,
            profiles = listOf(profile("就是我", "https://a/v1", "chat")),
        )
        assertEquals(1, choices.size)
        assertEquals(ModelChoices.WHERE_CHAT, choices[0].where)
    }

    @Test
    fun halfFilledEntriesAreLeftOut() {
        // 地址或型号空着的一律不列 —— 列出来点了也没用，那种配置发出去只会报错。
        val choices = ModelChoices.of(
            chatBaseUrl = "  ", chatModel = "chat",
            spokenOn = true, spokenBaseUrl = "https://b/v1", spokenModel = "",
            profiles = listOf(profile("半成品", "", "x"), profile("另一个", "https://c/v1", "  ")),
        )
        assertTrue(choices.isEmpty())
    }

    @Test
    fun forgottenProfilesAreNotOffered() {
        val choices = ModelChoices.of(
            chatBaseUrl = "https://a/v1", chatModel = "chat",
            spokenOn = false, spokenBaseUrl = null, spokenModel = null,
            profiles = listOf(profile("旧的", "https://d/v1", "gone", forgotten = true)),
        )
        assertEquals(1, choices.size)
    }

    @Test
    fun aProfileWithNoNameFallsBackToTheModelName() {
        val choices = ModelChoices.of(
            chatBaseUrl = null, chatModel = null,
            spokenOn = false, spokenBaseUrl = null, spokenModel = null,
            profiles = listOf(profile("", "https://e/v1", "m-1")),
        )
        assertEquals("m-1", choices[0].label)
    }

    @Test
    fun thePresetNameIsUsedWhenTheAddressIsOne() {
        val choices = ModelChoices.of(
            chatBaseUrl = "https://api.deepseek.com", chatModel = "deepseek-flash",
            spokenOn = false, spokenBaseUrl = null, spokenModel = null,
            profiles = emptyList(),
            nameOf = { if (it == "https://api.deepseek.com") "DeepSeek" else null },
        )
        assertEquals("DeepSeek", choices[0].label)
    }
}

package com.cleo.cleos.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 批量添加模型的两处纯逻辑：**手动粘贴怎么拆**，以及**哪些真的该新增**（去重、不覆盖已有）。
 *
 * 这两处写错的后果都很具体：粘十行只进去一行，或者把已有配置又加一遍、
 * 之后人看到两条一模一样的型号，不知道删哪条。
 */
class ModelBatchTest {
    @Test
    fun pastedIdsAreOnePerLine() {
        assertEquals(listOf("a", "b", "c"), parseModelIds("a\nb\nc"))
    }

    @Test
    fun blankLinesAndSpacesAreDropped() {
        assertEquals(listOf("mimo-v2.6-pro", "deepseek-v4-flash"), parseModelIds("  mimo-v2.6-pro  \n\n   \ndeepseek-v4-flash\n"))
    }

    @Test
    fun theSameIdPastedTwiceIsAddedOnce() {
        assertEquals(listOf("a", "b"), parseModelIds("a\nb\na"))
    }

    @Test
    fun whatIsAlreadyThereIsNotAddedAgain() {
        assertEquals(listOf("c"), modelsToAdd(setOf("a", "b"), listOf("a", "b", "c")))
    }

    @Test
    fun whatIsAlreadyThereIsNotOverwritten() {
        // 已有的型号原样留着（名称、参数、功能绑定都不动）—— 这里返回空就是"一条也不动"。
        assertEquals(emptyList<String>(), modelsToAdd(setOf("a"), listOf("a")))
    }

    @Test
    fun duplicatesInsideTheSelectionCollapse() {
        assertEquals(listOf("a", "b"), modelsToAdd(emptySet(), listOf("a", "a", " b ", "b")))
    }
}

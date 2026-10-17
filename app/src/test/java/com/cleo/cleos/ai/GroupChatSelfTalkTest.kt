package com.cleo.cleos.ai

import com.cleo.cleos.data.db.GroupMessageEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 群里这两件新事里能单独测的部分：**自主互聊轮到谁**，以及**语音没转成文字时怎么说**。
 *
 * 抽出来测是因为它们都会悄悄错：轮换写错就成了同一位自问自答；
 * 语音失败时不说，模型会当成没人开口，答案会答非所问。
 */
class GroupChatSelfTalkTest {
    @Test fun firstTurnHasNobodyBeforeIt() {
        assertEquals(1L, GroupChatRules.nextSelfTalkSpeaker(listOf(1L, 2L, 3L), null))
    }

    @Test fun theNextOneIsNotTheSameAsTheLast() {
        assertEquals(2L, GroupChatRules.nextSelfTalkSpeaker(listOf(1L, 2L, 3L), 1L))
    }

    @Test fun afterTheLastOneItGoesBackToTheFirst() {
        assertEquals(1L, GroupChatRules.nextSelfTalkSpeaker(listOf(1L, 2L), 2L))
    }

    @Test fun nobodyToSpeakWhenTheOnlyMemberJustDid() {
        assertNull(GroupChatRules.nextSelfTalkSpeaker(listOf(4L), 4L))
    }

    @Test fun aVoiceThatWasWrittenDownReadsAsItsWords() {
        val row = GroupMessageEntity(groupId = 1L, role = "user", content = "路上有点堵", createdAt = 0L, audio = "{}")
        assertEquals("路上有点堵", GroupChatRules.words(row))
    }

    @Test fun aVoiceThatCouldNotBeWrittenDownSaysSo() {
        val row = GroupMessageEntity(groupId = 1L, role = "user", content = "", createdAt = 0L, audio = "{}")
        assertEquals("（这条语音没能转成文字，内容不知道 —— 可以请对方再说一遍）", GroupChatRules.words(row))
    }

    @Test fun aTypedMessageWithNoWordsStaysEmpty() {
        val row = GroupMessageEntity(groupId = 1L, role = "user", content = "", createdAt = 0L)
        assertEquals("", GroupChatRules.words(row))
    }

    @Test fun aPendingReplyHasNothingInThePromptYet() {
        val row = GroupMessageEntity(groupId = 1L, role = "assistant", content = "", createdAt = 0L, status = "pending")
        assertEquals("", GroupChatRules.words(row))
    }
}

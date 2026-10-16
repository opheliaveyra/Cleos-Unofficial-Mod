package com.cleo.cleos.data.db

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片要不要先交给另一个模型转述。
 *
 * 这条判断错了有两种后果，都不好看：
 * · **该转述却没转** —— 聊天模型不会看图，用户发来的图它压根没看见；
 * · **不该转述却转了** —— 多绕一道更慢，而且图里的细节会丢在转述里。
 */
class VisionRulesTest {
    private fun model(url: String, name: String, heard: Boolean = false) = TaModel(url, name, forHeard = heard)

    @Test
    fun theSameModelMeansNoDetour() {
        // 没配看图模型时，modelFor(Vision) 退回来的就是主模型 —— 两边一模一样，直接发图。
        assertFalse(VisionRules.separate(model("https://a/v1", "m"), model("https://a/v1", "m")))
    }

    @Test
    fun anotherAddressMeansDescribe() {
        assertTrue(VisionRules.separate(model("https://b/v1", "m"), model("https://a/v1", "m")))
    }

    @Test
    fun anotherModelNameAtTheSameServiceAlsoMeansDescribe() {
        // 同一个服务上换个更会看图的型号，也算分开 —— 这正是最常见的用法。
        assertTrue(VisionRules.separate(model("https://a/v1", "vision-pro"), model("https://a/v1", "cheap-chat")))
    }

    @Test
    fun theNoteSaysWhereThatTextCameFrom() {
        // 转述的那段会当成"用户说的话"继续往下走，得有个抬头让人看出它不是 TA 自己说的。
        assertTrue(VisionRules.NOTE.isNotBlank())
        assertTrue(VisionRules.NOTE.contains("转述"))
    }
}

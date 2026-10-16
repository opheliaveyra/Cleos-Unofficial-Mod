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

    @Test
    fun aMissingKeyIsNamedAsTheReason() {
        // 配了模型但没 Key —— 得**指名道姓**说清楚，不然人只会看到主模型答非所问，
        // 根本想不到是识别模型那一步没过（这正是玖玖遇到的那个）。
        val why = VisionRules.whyNotDescribed(TaModel("https://a/v1", "glm-4.6v", forHeard = false), hasKey = false)
        assertTrue("要说没配 Key", why.contains("Key"))
        assertTrue("要点出是哪个模型", why.contains("glm-4.6v"))
    }

    @Test
    fun noModelAtAllIsToldDifferently() {
        val why = VisionRules.whyNotDescribed(TaModel("", "", forHeard = false), hasKey = false)
        assertTrue("要说没配模型", why.contains("图像识别"))
        assertTrue("要告诉人去哪儿配", why.contains("设置"))
    }

    @Test
    fun aModelThatJustFailedPointsAtWhatToCheck() {
        val why = VisionRules.whyNotDescribed(TaModel("https://b/v1", "some-vl", forHeard = false), hasKey = true)
        assertTrue("模型名和服务都可能有问题", why.contains("模型名") && why.contains("通不通"))
        assertTrue("要把地址带出来", why.contains("https://b/v1"))
    }
}

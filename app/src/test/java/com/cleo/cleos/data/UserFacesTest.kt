package com.cleo.cleos.data

import com.cleo.cleos.data.db.ChatGroupEntity
import com.cleo.cleos.data.db.CompanionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「我」的头像怎么选：**角色 / 群专属 > 全局默认**。
 *
 * 这条规则最怕的是"有的地方读专属、有的地方还在读全局"，所以只走 [UserFaces] 一个入口；
 * 群聊那条尤其要钉死：**群没有唯一对应角色，不跟着谁发言变**。
 */
class UserFacesTest {
    private fun ta(face: String?) =
        CompanionEntity(apiBaseUrl = "https://a/v1", apiModel = "m", createdAt = 0L, userAvatar = face)

    private fun group(face: String?) =
        ChatGroupEntity(title = "一个群", createdAt = 0L, updatedAt = 0L, userAvatar = face)

    @Test
    fun theCompanionFaceWinsOverTheGlobalOne() {
        assertEquals("mine-a.png", UserFaces.forCompanion(ta("mine-a.png"), AppSettings(userAvatar = "global.png")))
    }

    @Test
    fun withoutOneTheGlobalFaceIsUsed() {
        assertEquals("global.png", UserFaces.forCompanion(ta(null), AppSettings(userAvatar = "global.png")))
        assertEquals("global.png", UserFaces.forCompanion(null, AppSettings(userAvatar = "global.png")))
    }

    @Test
    fun aBlankFaceCountsAsNotSet() {
        assertEquals("global.png", UserFaces.forCompanion(ta(""), AppSettings(userAvatar = "global.png")))
        assertEquals("global.png", UserFaces.forGroup(group("  "), AppSettings(userAvatar = "global.png")))
    }

    @Test
    fun aGroupHasItsOwnFace() {
        assertEquals("group-1.png", UserFaces.forGroup(group("group-1.png"), AppSettings(userAvatar = "global.png")))
    }

    @Test
    fun aGroupDoesNotFollowAnyCompanion() {
        // 群的头像只看这个群自己 —— 哪位 TA 发言都不影响它。
        val settings = AppSettings(userAvatar = "global.png")
        assertEquals("global.png", UserFaces.forGroup(group(null), settings))
        assertEquals("group-1.png", UserFaces.forGroup(group("group-1.png"), settings))
    }

    @Test
    fun withNothingSetThereIsNoFace() {
        assertNull(UserFaces.forCompanion(ta(null), AppSettings()))
        assertNull(UserFaces.forGroup(null, AppSettings()))
    }
}

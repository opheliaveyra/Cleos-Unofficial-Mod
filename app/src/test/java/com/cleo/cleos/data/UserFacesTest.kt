package com.cleo.cleos.data

import com.cleo.cleos.data.db.ChatGroupEntity
import com.cleo.cleos.data.db.CompanionEntity
import kotlinx.serialization.json.Json
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

    // ---- 旧数据 / 备份往返 ----

    @Test
    fun anOlderRecordWithoutTheFieldFallsBackToTheGlobalFace() {
        // 旧版本存下来的 TA（备份里也是这个形状）：没有 userAvatar 这个字段 —— 读出来是空，
        // 于是继续用全局头像。
        val old = Json { ignoreUnknownKeys = true }
            .decodeFromString<CompanionEntity>("""{"apiBaseUrl":"https://a/v1","apiModel":"m","createdAt":1}""")
        assertNull(old.userAvatar)
        assertEquals("global.png", UserFaces.forCompanion(old, AppSettings(userAvatar = "global.png")))
    }

    @Test
    fun theFacesSurviveASaveAndLoad() {
        // 备份就是把实体写成 JSON 再读回来：专属头像得原样回来（不然恢复完就丢了）。
        val json = Json
        val a = ta("mine-a.png")
        val g = group("group-1.png")
        assertEquals("mine-a.png", json.decodeFromString<CompanionEntity>(json.encodeToString(a)).userAvatar)
        assertEquals("group-1.png", json.decodeFromString<ChatGroupEntity>(json.encodeToString(g)).userAvatar)
    }
}

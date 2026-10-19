package com.cleo.cleos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The keys, the activity-alias names and the labels on the picker: three lists that have to
 * agree. A typo in any one of them switches nothing, and nothing in the UI would show it.
 */
class AppIconsTest {
    @Test
    fun keysAreTheDefaultAndTheThirtyTwoPresetsInOrder() {
        assertEquals(33, AppIcons.KEYS.size)
        assertEquals(AppIcons.DEFAULT, AppIcons.KEYS.first())
        assertEquals((1..32).map { it.toString().padStart(2, '0') }, AppIcons.KEYS.drop(1))
    }

    @Test
    fun unknownOrMissingKeysFallBackToTheDefault() {
        // 老设置、手改过的备份、以后删掉的预设：桌面上总得有一个图标。
        assertEquals(AppIcons.DEFAULT, AppIcons.normalize(null))
        assertEquals(AppIcons.DEFAULT, AppIcons.normalize(""))
        assertEquals(AppIcons.DEFAULT, AppIcons.normalize("33"))
        assertEquals(AppIcons.DEFAULT, AppIcons.normalize("launcher01"))
        assertEquals("07", AppIcons.normalize("07"))
    }

    @Test
    fun everyKeyNamesItsAliasAndItsLabel() {
        // Manifest 里的组件名：.LauncherDefault 和 .Launcher01 … .Launcher32。
        assertEquals(".LauncherDefault", AppIcons.aliasOf(AppIcons.DEFAULT))
        assertEquals("默认", AppIcons.label(AppIcons.DEFAULT))
        for (key in AppIcons.KEYS.drop(1)) {
            assertEquals(".Launcher$key", AppIcons.aliasOf(key))
            assertEquals(key, AppIcons.label(key))
            assertTrue(AppIcons.aliasOf(key).matches(Regex("\\.Launcher\\d\\d")))
        }
    }
}

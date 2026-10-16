package com.cleo.cleos.data.db

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 数据库结构的历史不能自相矛盾。
 *
 * **这个测试是为了 0.1.28 那次闪退加的。** 那次我是这么搞砸的：
 *
 * 1. 给 `CompanionEntity` 加了 `featureModels` 字段；
 * 2. **版本号还没改（还是 37）就编译了一次** —— Room 于是把"带新字段的结构"
 *    导出成了 `37.json`，**把旧的那份覆盖掉了**；
 * 3. 然后才把版本改成 38，导出 `38.json`。
 *
 * 结果两个 schema 文件**一模一样**。手机上真实的库是 37（没有那个字段），
 * App 却以为"37 → 38 什么都没变"，启动时一校验就崩 —— **打开就闪退**。
 *
 * 所以这里盯两件事：**相邻版本的 schema 不能相同**，以及**每个版本该有的东西在不在**。
 */
class SchemaHistoryTest {
    /** Gradle 跑单元测试时的工作目录是 app/，schema 就在它底下。 */
    private fun schema(version: Int): String? =
        listOf(
            File("schemas/com.cleo.cleos.data.db.AppDatabase/$version.json"),
            File("app/schemas/com.cleo.cleos.data.db.AppDatabase/$version.json"),
        ).firstOrNull { it.isFile }?.readText()

    /** 所有导出过的版本号，从小到大。 */
    private fun versions(): List<Int> =
        listOf(
            File("schemas/com.cleo.cleos.data.db.AppDatabase"),
            File("app/schemas/com.cleo.cleos.data.db.AppDatabase"),
        ).firstOrNull { it.isDirectory }
            ?.listFiles()
            ?.mapNotNull { it.name.removeSuffix(".json").toIntOrNull() }
            ?.sorted()
            .orEmpty()

    @Test
    fun theSchemasAreActuallyThere() {
        // 这条先兜底：路径找错了的话，下面那些循环会"全都通过"，等于白测。
        val found = versions()
        assertTrue("一个 schema 都没找到，路径不对", found.isNotEmpty())
        assertTrue("没找到 38", found.contains(38))
    }

    @Test
    fun neighbouringVersionsAreNeverIdentical() {
        val found = versions()
        for (i in 1 until found.size) {
            val before = found[i - 1]
            val after = found[i]
            // 只比相邻的：中间缺版本号时（比如跳过 12）不硬凑。
            if (after != before + 1) continue
            val a = schema(before) ?: continue
            val b = schema(after) ?: continue
            assertNotEquals(
                "版本 $before 和 $after 的 schema 一模一样 —— 说明有一份被覆盖了：" +
                    "多半是改了 entity 却没先升版本号就编译，Room 把新结构导出成了旧版本的名字。" +
                    "那种包装到手机上会一开就闪退。",
                a,
                b,
            )
        }
    }

    @Test
    fun theFeatureModelsColumnArrivedWithVersion38() {
        val v37 = schema(37)
        val v38 = schema(38)
        assertTrue("没有 37 的 schema", v37 != null)
        assertTrue("没有 38 的 schema", v38 != null)
        assertFalse("37 里不该有 featureModels", v37!!.contains("featureModels"))
        assertTrue("38 里该有 featureModels", v38!!.contains("featureModels"))
    }
}

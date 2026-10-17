package com.cleo.cleos.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 备份格式兼容判断：**不拿版本号大小直接拒绝**。 */
class BackupCompatTest {
    private fun header(raw: String) = BackupCompat.header(raw)

    @Test
    fun anUpstreamV6BackupIsFine() {
        val v = BackupCompat.check(header("""{"format":"cleos-backup","version":6,"settings":{}}"""))
        assertTrue(v.ok)
        assertNull(v.warning)
        assertNull(v.reason)
    }

    @Test
    fun theVersionThisBuildWritesIsFine() {
        val v = BackupCompat.check(header("""{"format":"cleos-backup","version":${BackupFile.VERSION}}"""))
        assertTrue(v.ok)
    }

    @Test
    fun aNewerFormatIsStillTriedWithAWarning() {
        // 这就是反馈里那种：版本数字更大，但结构多半还能读 —— 以前直接拒绝，现在先试。
        val v = BackupCompat.check(header("""{"format":"cleos-backup","version":8,"settings":{}}"""))
        assertTrue(v.ok)
        assertTrue(v.warning!!.contains("格式版本"))
    }

    @Test
    fun somethingElseIsNotOurBackup() {
        val v = BackupCompat.check(header("""{"format":"someone-else","version":1}"""))
        assertEquals(false, v.ok)
        assertTrue(v.reason!!.contains("不是 Cleos"))
    }

    @Test
    fun garbageIsRejectedNotCrashed() {
        assertEquals(false, BackupCompat.check(header("not json")).ok)
        assertEquals(false, BackupCompat.check(header("[]")).ok)
        assertEquals(false, BackupCompat.check(header("""{"format":"cleos-backup"}""")).ok)
    }

    @Test
    fun unknownFieldsAroundTheHeaderDoNotMatter() {
        // 未知字段不该让"读头"失败：原版/新版都可能带我们不认识的顶层字段。
        val v = BackupCompat.check(header("""{"format":"cleos-backup","version":6,"futureThing":{"a":1},"another":[1,2]}"""))
        assertTrue(v.ok)
    }
}

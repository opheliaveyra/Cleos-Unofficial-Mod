package com.cleo.cleos.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app is willing to fetch. The address comes from a model, which may be repeating a
 * line it read inside a file or a page it just fetched, so this is the part that has to hold.
 */
class WebAddressTest {
    @Test
    fun onlyPlainWebAddressesGetThrough() {
        assertEquals("https://example.com/a", WebAddress.checked("https://example.com/a"))
        assertEquals("http://example.com", WebAddress.checked("http://example.com"))
        assertNull(WebAddress.checked("file:///etc/passwd"))
        assertNull(WebAddress.checked("ftp://example.com/x"))
        assertNull(WebAddress.checked("javascript:alert(1)"))
        assertNull(WebAddress.checked("data:text/html,<h1>x</h1>"))
        assertNull(WebAddress.checked(""))
        assertNull(WebAddress.checked("example.com"))
    }

    @Test
    fun thePhoneAndTheNetworkAroundItAreRefused() {
        for (bad in listOf(
            "http://localhost/admin",
            "http://127.0.0.1:8080/",
            "http://127.1.2.3/",
            "http://10.0.0.1/",
            "http://172.16.5.4/",
            "http://192.168.1.1/",
            "http://169.254.169.254/latest/meta-data/",
            "http://0.0.0.0/",
            "http://224.0.0.1/",
            "http://[::1]/",
            "http://router.local/",
            "http://something.internal/",
            "http://foo.localhost/",
        )) {
            assertNull(bad, WebAddress.checked(bad))
        }
        // A public address that merely looks unusual is fine.
        assertEquals("http://8.8.8.8/", WebAddress.checked("http://8.8.8.8/"))
        assertEquals("https://172.32.0.1/", WebAddress.checked("https://172.32.0.1/"))
    }

    @Test
    fun theHostIsReadOutOfTheAddress() {
        assertEquals("example.com", WebAddress.hostOf("https://example.com/a/b?c=d#e"))
        assertEquals("example.com", WebAddress.hostOf("https://user:pw@example.com/x"))
        assertEquals("example.com", WebAddress.hostOf("https://example.com:8443/x"))
        assertEquals("例子.中国", WebAddress.hostOf("https://例子.中国/路径"))
        assertEquals("::1", WebAddress.hostOf("http://[::1]:8080/"))
    }

    @Test
    fun aPublicNameIsNotMistakenForTheLocalNetwork() {
        // Whether the resolver works or not, example.com is not this phone or the network around
        // it; a lookup that fails comes back false rather than blocking the fetch.
        assertFalse(WebAddress.isLocal("example.com"))
        assertFalse(WebAddress.isLocal("news.ycombinator.com"))
        assertTrue(WebAddress.isLocal("localhost"))
    }
}

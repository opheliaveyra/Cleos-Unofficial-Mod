package com.cleo.cleos.ai

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** HTML turned into something worth reading. Strings in, text out: no phone needed. */
class WebTextTest {
    private val page = """
        <!DOCTYPE html>
        <html><head>
          <meta charset="utf-8">
          <title>今天的天气 - 某某网</title>
          <style>body { font: 14px sans-serif; }</style>
          <script>window.dataLayer = [];</script>
        </head>
        <body>
          <article>
            <h1>今天的天气</h1>
            <p>早上有雾，中午转晴。</p>
            <p>傍晚可能起风，出门带件外套。</p>
            <ul><li>最高 21 度</li><li>最低 9 度</li></ul>
          </article>
          <script>console.log("不该出现");</script>
        </body></html>
    """.trimIndent()

    @Test
    fun theArticleComesBackInReadingOrder() {
        val text = WebText.of(page)
        assertTrue(text, text.contains("今天的天气"))
        assertTrue(text, text.contains("早上有雾，中午转晴。"))
        assertTrue(text, text.contains("傍晚可能起风"))
        assertTrue("列表项要能认出来", text.contains("- 最高 21 度"))
        assertTrue(text.indexOf("早上有雾") < text.indexOf("傍晚可能起风"))
    }

    @Test
    fun scriptsAndStylesAndTheHeadAreNotText() {
        val text = WebText.of(page)
        assertFalse(text, text.contains("console.log"))
        assertFalse(text, text.contains("dataLayer"))
        assertFalse(text, text.contains("font: 14px"))
        assertFalse("标题在 head 里，正文不该混进来", text.contains("某某网"))
    }

    @Test
    fun theTitleIsReadFromTheHead() {
        assertEquals("今天的天气 - 某某网", WebText.titleOf(page))
    }

    @Test
    fun entitiesAreTurnedBackIntoCharacters() {
        assertEquals("a & b < c > d \"e\"", WebText.unescape("a &amp; b &lt; c &gt; d &quot;e&quot;"))
        assertEquals("中文 · 破折号 —", WebText.unescape("中文 &middot; 破折号 &mdash;"))
        assertEquals("你好", WebText.unescape("&#20320;&#22909;"))
        assertEquals("A", WebText.unescape("&#x41;"))
        assertEquals("不认识的 &nbspthing; 就留着", WebText.unescape("不认识的 &nbspthing; 就留着"))
    }

    @Test
    fun aPageWithoutAnArticleFallsBackToTheWholeBody() {
        val text = WebText.of("<html><body><div>没有 article 的页面</div><p>也读得出来</p></body></html>")
        assertTrue(text, text.contains("没有 article 的页面"))
        assertTrue(text, text.contains("也读得出来"))
    }

    @Test
    fun aPageThatIsAllJavaScriptComesBackEmptyRatherThanInvented() {
        val text = WebText.of("""<html><head><title>应用</title></head><body><div id="root"></div><script>render()</script></body></html>""")
        assertEquals("", text)
    }

    @Test
    fun blankLinesAndRunsOfSpacesAreTidied() {
        // Paragraphs keep one blank line between them — that is what makes a page readable —
        // but empty paragraphs do not stack up into a wall of them.
        assertEquals("第一行\n\n第二行", WebText.of("<p>第一行</p><p></p><p></p><p>第二行</p>"))
        // Runs of spaces collapse the way a browser shows them.
        assertEquals("一段 话", WebText.of("<p>一段   话</p>"))
    }

    @Test
    fun theCharsetComesFromTheHeaderFirst() {
        val gbk = "中文".toByteArray(Charset.forName("GBK"))
        assertEquals(Charset.forName("GBK"), WebText.charsetOf("text/html; charset=GBK", gbk))
        // Nothing in the header: whatever the page's own meta tag says.
        val withMeta = "<html><head><meta charset=\"gbk\"></head><body>中文</body></html>".toByteArray(Charset.forName("GBK"))
        assertEquals(Charset.forName("GBK"), WebText.charsetOf("text/html", withMeta))
        assertEquals(Charsets.UTF_8, WebText.charsetOf("text/html", "中文".toByteArray()))
    }
}

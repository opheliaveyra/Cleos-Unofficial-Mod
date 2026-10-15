package com.cleo.cleos.ai

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading results out of four different answers. The services each nest them differently —
 * webPages.value, results, organic, search_result — and the point of walking the document
 * rather than parsing it per service is that a fifth one, or a tidied-up fourth, still works.
 */
class WebSearchTest {
    private fun hits(json: String, count: Int = 5) = WebSearch.hitsOf(Json.parseToJsonElement(json), count)

    @Test
    fun theShapeBochaAnswersIn() {
        val answer = """
            {"code":200,"data":{"webPages":{"value":[
              {"name":"第一条","url":"https://a.example/1","snippet":"摘要一"},
              {"name":"第二条","url":"https://a.example/2","snippet":"摘要二"}
            ]}}}
        """.trimIndent()
        val found = hits(answer)
        assertEquals(2, found.size)
        assertEquals("第一条", found[0].title)
        assertEquals("https://a.example/1", found[0].url)
        assertEquals("摘要一", found[0].snippet)
    }

    @Test
    fun theShapeTavilyAnswersIn() {
        val answer = """{"results":[{"title":"Result","url":"https://b.example/x","content":"some words"}]}"""
        val found = hits(answer)
        assertEquals(1, found.size)
        assertEquals("Result", found[0].title)
        assertEquals("some words", found[0].snippet)
    }

    @Test
    fun theShapeSerperAnswersIn() {
        val answer = """{"organic":[{"title":"R","link":"https://c.example/y","snippet":"words"}]}"""
        val found = hits(answer)
        assertEquals(1, found.size)
        assertEquals("https://c.example/y", found[0].url)
        assertEquals("words", found[0].snippet)
    }

    @Test
    fun theShapeZhipuAnswersIn() {
        val answer = """{"search_result":[{"title":"标题","link":"https://d.example/z","content":"正文"}]}"""
        val found = hits(answer)
        assertEquals(1, found.size)
        assertEquals("标题", found[0].title)
        assertEquals("正文", found[0].snippet)
    }

    @Test
    fun entriesWithoutALinkOrATitleAreSkippedRatherThanGuessedAt() {
        val answer = """
            {"results":[
              {"title":"没有网址"},
              {"url":"https://e.example/only-url"},
              {"title":"正常","url":"https://e.example/ok","content":"在"},
              {"title":"不是网页","url":"ftp://e.example/file"}
            ]}
        """.trimIndent()
        val found = hits(answer)
        assertEquals(listOf("正常"), found.map { it.title })
    }

    @Test
    fun noResultsComesBackEmptyInsteadOfThrowing() {
        assertTrue(hits("""{"results":[]}""").isEmpty())
        assertTrue(hits("""{"code":200,"data":{}}""").isEmpty())
        assertTrue(hits("""{"message":"quota exceeded"}""").isEmpty())
        // Noisy but harmless: numbers, strings and nulls mixed in among the objects.
        assertTrue(hits("""{"a":[1,"two",null,{"nothing":true}]}""").isEmpty())
    }

    @Test
    fun onlyAsManyAsWereAskedForComeBack() {
        val answer = """{"results":[
          {"title":"1","url":"https://f.example/1"},{"title":"2","url":"https://f.example/2"},
          {"title":"3","url":"https://f.example/3"},{"title":"4","url":"https://f.example/4"}
        ]}"""
        assertEquals(2, hits(answer, count = 2).size)
        assertEquals(listOf("1", "2"), hits(answer, count = 2).map { it.title })
    }

    @Test
    fun theSamePageTwiceCountsOnce() {
        val answer = """{"results":[
          {"title":"一","url":"https://g.example/same"},{"title":"二","url":"https://g.example/same"}
        ]}"""
        assertEquals(1, hits(answer).size)
    }

    @Test
    fun everyServiceHasSomewhereToApplyForAKey() {
        for (service in SearchService.entries) {
            assertTrue(service.label, service.label.isNotBlank())
            assertTrue(service.keyUrl, service.keyUrl.startsWith("https://"))
            assertTrue(service.endpoint, service.endpoint.startsWith("https://"))
            assertTrue(service.hint, service.hint.isNotBlank())
        }
        assertEquals(SearchService.Bocha, SearchService.of("Bocha"))
        assertEquals(null, SearchService.of("Nope"))
        assertEquals(null, SearchService.of(""))
    }
}

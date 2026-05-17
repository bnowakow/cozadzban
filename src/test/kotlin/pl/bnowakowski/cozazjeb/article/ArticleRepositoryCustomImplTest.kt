// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.article

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

class ArticleRepositoryCustomImplTest {

    private val jdbc: NamedParameterJdbcTemplate = mock()
    private val repository = ArticleRepositoryCustomImpl(jdbc)

    @Test
    fun `findTopLanguages returns most used languages with bounded limit`() {
        whenever(
            jdbc.queryForList(
                any<String>(),
                any<Map<String, Any>>(),
                eq(String::class.java),
            ),
        ).thenReturn(listOf("pl", "en", "de"))

        val result = repository.findTopLanguages(3)

        assertEquals(listOf("pl", "en", "de"), result)

        val sqlCaptor = argumentCaptor<String>()
        val paramsCaptor = argumentCaptor<Map<String, Any>>()
        verify(jdbc).queryForList(sqlCaptor.capture(), paramsCaptor.capture(), eq(String::class.java))
        assertTrue(sqlCaptor.firstValue.contains("GROUP BY language"))
        assertTrue(sqlCaptor.firstValue.contains("ORDER BY COUNT(*) DESC, language ASC"))
        assertEquals(3, paramsCaptor.firstValue["limit"])
    }

    @Test
    fun `findTopLanguages never passes a negative limit`() {
        whenever(
            jdbc.queryForList(
                any<String>(),
                any<Map<String, Any>>(),
                eq(String::class.java),
            ),
        ).thenReturn(emptyList())

        repository.findTopLanguages(-1)

        val paramsCaptor = argumentCaptor<Map<String, Any>>()
        verify(jdbc).queryForList(any<String>(), paramsCaptor.capture(), eq(String::class.java))
        assertEquals(0, paramsCaptor.firstValue["limit"])
    }

    @Test
    fun `findPage sorts published date with nulls last and created date tie breaker`() {
        whenever(
            jdbc.query(
                any<String>(),
                any<Map<String, Any?>>(),
                any<RowMapper<Article>>(),
            ),
        ).thenReturn(emptyList())

        repository.findPage(0, 20, "publishedAt", "desc")

        val sqlCaptor = argumentCaptor<String>()
        verify(jdbc).query(sqlCaptor.capture(), any<Map<String, Any?>>(), any<RowMapper<Article>>())
        assertTrue(sqlCaptor.firstValue.contains("ORDER BY published_at DESC NULLS LAST, created_at DESC"))
    }

    @Test
    fun `findFaviconBackfillCandidates selects rows without local cached favicon`() {
        whenever(
            jdbc.query(
                any<String>(),
                any<Map<String, Any>>(),
                any<RowMapper<Article>>(),
            ),
        ).thenReturn(emptyList())

        val result = repository.findFaviconBackfillCandidates(25)

        assertEquals(emptyList<Article>(), result)
        val sqlCaptor = argumentCaptor<String>()
        val paramsCaptor = argumentCaptor<Map<String, Any>>()
        verify(jdbc).query(sqlCaptor.capture(), paramsCaptor.capture(), any<RowMapper<Article>>())
        assertTrue(sqlCaptor.firstValue.contains("favicon IS NULL"))
        assertTrue(sqlCaptor.firstValue.contains("favicon NOT LIKE '/favicons/%'"))
        assertEquals(25, paramsCaptor.firstValue["limit"])
    }

    @Test
    fun `updateFavicon updates only favicon by id with named parameters`() {
        whenever(jdbc.update(any<String>(), any<Map<String, Any>>())).thenReturn(1)

        val result = repository.updateFavicon(12L, "/favicons/example.png")

        assertEquals(true, result)
        val sqlCaptor = argumentCaptor<String>()
        val paramsCaptor = argumentCaptor<Map<String, Any>>()
        verify(jdbc).update(sqlCaptor.capture(), paramsCaptor.capture())
        assertTrue(sqlCaptor.firstValue.contains("SET favicon = :favicon"))
        assertTrue(sqlCaptor.firstValue.contains("WHERE id = :id"))
        assertEquals(12L, paramsCaptor.firstValue["id"])
        assertEquals("/favicons/example.png", paramsCaptor.firstValue["favicon"])
    }
}

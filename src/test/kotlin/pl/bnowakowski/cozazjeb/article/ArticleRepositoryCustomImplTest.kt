// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb.article

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

class ArticleRepositoryCustomImplTest {

    private val jdbc: NamedParameterJdbcTemplate = mock()
    private val repository = ArticleRepositoryCustomImpl(jdbc)

    @Test
    fun `findTopLanguages returns most used languages with bounded limit`() {
        whenever(
            jdbc.queryForList(
                org.mockito.kotlin.any<String>(),
                org.mockito.kotlin.any<Map<String, Any>>(),
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
                org.mockito.kotlin.any<String>(),
                org.mockito.kotlin.any<Map<String, Any>>(),
                eq(String::class.java),
            ),
        ).thenReturn(emptyList())

        repository.findTopLanguages(-1)

        val paramsCaptor = argumentCaptor<Map<String, Any>>()
        verify(jdbc).queryForList(org.mockito.kotlin.any<String>(), paramsCaptor.capture(), eq(String::class.java))
        assertEquals(0, paramsCaptor.firstValue["limit"])
    }
}

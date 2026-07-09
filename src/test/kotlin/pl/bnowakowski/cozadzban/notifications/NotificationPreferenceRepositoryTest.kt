// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.notifications

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate

class NotificationPreferenceRepositoryTest {

    @Test
    fun `login-required recipient query is scoped to active admins`() {
        val jdbc: NamedParameterJdbcTemplate = mock()
        whenever(jdbc.query(any<String>(), any<Map<String, Any>>(), any<RowMapper<NotificationRecipient>>()))
            .thenReturn(emptyList())
        val repository = NotificationPreferenceRepository(jdbc)

        repository.findPushoverRecipientsForFacebookLoginRequired()

        val sql = argumentCaptor<String>()
        org.mockito.kotlin.verify(jdbc).query(
            sql.capture(),
            any<Map<String, Any>>(),
            any<RowMapper<NotificationRecipient>>(),
        )
        assertTrue(sql.firstValue.contains("np.facebook_login_required_enabled = true"))
        assertTrue(sql.firstValue.contains("au.status = 'ACTIVE'"))
        assertTrue(sql.firstValue.contains("au.role = 'ADMIN'"))
    }

    @Test
    fun `proposal summary recipient query is scoped to active users`() {
        val jdbc: NamedParameterJdbcTemplate = mock()
        whenever(jdbc.query(any<String>(), any<Map<String, Any>>(), any<RowMapper<NotificationRecipient>>()))
            .thenReturn(emptyList())
        val repository = NotificationPreferenceRepository(jdbc)

        repository.findPushoverRecipientsForProposalSummary()

        val sql = argumentCaptor<String>()
        org.mockito.kotlin.verify(jdbc).query(
            sql.capture(),
            any<Map<String, Any>>(),
            any<RowMapper<NotificationRecipient>>(),
        )
        assertTrue(sql.firstValue.contains("np.facebook_proposals_submitted_enabled = true"))
        assertTrue(sql.firstValue.contains("au.status = 'ACTIVE'"))
    }

    @Test
    fun `auto approved recipient query is scoped to active users`() {
        val jdbc: NamedParameterJdbcTemplate = mock()
        whenever(jdbc.query(any<String>(), any<Map<String, Any>>(), any<RowMapper<NotificationRecipient>>()))
            .thenReturn(emptyList())
        val repository = NotificationPreferenceRepository(jdbc)

        repository.findPushoverRecipientsForAutoApprovedProposals()

        val sql = argumentCaptor<String>()
        org.mockito.kotlin.verify(jdbc).query(
            sql.capture(),
            any<Map<String, Any>>(),
            any<RowMapper<NotificationRecipient>>(),
        )
        assertTrue(sql.firstValue.contains("np.facebook_proposals_auto_approved_enabled = true"))
        assertTrue(sql.firstValue.contains("au.status = 'ACTIVE'"))
    }
}

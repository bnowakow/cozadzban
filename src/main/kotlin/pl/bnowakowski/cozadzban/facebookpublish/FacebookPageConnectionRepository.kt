// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository

@Repository
class FacebookPageConnectionRepository(private val jdbc: NamedParameterJdbcTemplate) {
    fun all(): List<FacebookPageConnection> = jdbc.query(
        """SELECT language, page_id, page_name, enabled, time_zone, monthly_link_limit,
                  minimum_post_age_hours, scheduler_interval_minutes, connected_at, updated_at
             FROM facebook_page_connection ORDER BY language""",
        emptyMap<String, Any>(), mapper,
    )

    fun find(language: String): FacebookPageConnection? = jdbc.query(
        """SELECT language, page_id, page_name, enabled, time_zone, monthly_link_limit,
                  minimum_post_age_hours, scheduler_interval_minutes, connected_at, updated_at
             FROM facebook_page_connection WHERE language = :language""",
        mapOf("language" to language), mapper,
    ).firstOrNull()

    fun encryptedToken(language: String): String? = jdbc.query(
        "SELECT access_token_encrypted FROM facebook_page_connection WHERE language = :language",
        mapOf("language" to language),
    ) { rs, _ -> rs.getString(1) }.firstOrNull()

    fun upsert(language: String, page: AvailableFacebookPage, encryptedToken: String) {
        jdbc.update(
            """INSERT INTO facebook_page_connection(language, page_id, page_name, access_token_encrypted)
               VALUES (:language, :pageId, :pageName, :encryptedToken)
               ON CONFLICT (language) DO UPDATE SET page_id = :pageId, page_name = :pageName,
                   access_token_encrypted = :encryptedToken, enabled = false,
                   connected_at = now(), updated_at = now()""",
            mapOf("language" to language, "pageId" to page.id, "pageName" to page.name, "encryptedToken" to encryptedToken),
        )
    }

    fun updateSettings(language: String, settings: FacebookPageSettings): Boolean = jdbc.update(
        """UPDATE facebook_page_connection SET enabled = :enabled, time_zone = :timeZone,
               monthly_link_limit = :monthlyLinkLimit, minimum_post_age_hours = :minimumPostAgeHours,
               scheduler_interval_minutes = :schedulerIntervalMinutes, updated_at = now()
           WHERE language = :language""",
        mapOf(
            "language" to language, "enabled" to settings.enabled, "timeZone" to settings.timeZone,
            "monthlyLinkLimit" to settings.monthlyLinkLimit, "minimumPostAgeHours" to settings.minimumPostAgeHours,
            "schedulerIntervalMinutes" to settings.schedulerIntervalMinutes,
        ),
    ) == 1

    fun delete(language: String): Boolean = jdbc.update(
        "DELETE FROM facebook_page_connection WHERE language = :language", mapOf("language" to language),
    ) == 1

    private val mapper = RowMapper<FacebookPageConnection> { rs, _ ->
        FacebookPageConnection(
            rs.getString("language"), rs.getString("page_id"), rs.getString("page_name"),
            rs.getBoolean("enabled"), rs.getString("time_zone"), rs.getInt("monthly_link_limit"),
            rs.getInt("minimum_post_age_hours"), rs.getInt("scheduler_interval_minutes"),
            rs.getTimestamp("connected_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
        )
    }
}

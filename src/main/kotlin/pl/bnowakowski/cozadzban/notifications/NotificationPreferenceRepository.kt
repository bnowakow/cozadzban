// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.notifications

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import pl.bnowakowski.cozadzban.user.Role

@Repository
class NotificationPreferenceRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun findByUserId(appUserId: Long): NotificationPreference? =
        jdbc.query(
            """
                SELECT app_user_id, provider, pushover_user_key_encrypted, pushover_user_key_suffix,
                       pushover_devices, facebook_login_required_enabled,
                       facebook_proposals_submitted_enabled, created_at, updated_at
                  FROM notification_preference
                 WHERE app_user_id = :appUserId
            """.trimIndent(),
            mapOf("appUserId" to appUserId),
            PREFERENCE_ROW_MAPPER,
        ).firstOrNull()

    fun upsert(
        appUserId: Long,
        pushoverUserKeyEncrypted: String,
        pushoverUserKeySuffix: String,
        pushoverDevices: Collection<String>,
        facebookLoginRequiredEnabled: Boolean,
        facebookProposalsSubmittedEnabled: Boolean,
    ): NotificationPreference =
        jdbc.query(
            """
                INSERT INTO notification_preference(
                    app_user_id, provider, pushover_user_key_encrypted, pushover_user_key_suffix,
                    pushover_devices, facebook_login_required_enabled,
                    facebook_proposals_submitted_enabled
                )
                VALUES (
                    :appUserId, 'PUSHOVER', :pushoverUserKeyEncrypted, :pushoverUserKeySuffix,
                    :pushoverDevices, :facebookLoginRequiredEnabled,
                    :facebookProposalsSubmittedEnabled
                )
                ON CONFLICT (app_user_id) DO UPDATE
                   SET pushover_user_key_encrypted = :pushoverUserKeyEncrypted,
                       pushover_user_key_suffix = :pushoverUserKeySuffix,
                       pushover_devices = :pushoverDevices,
                       facebook_login_required_enabled = :facebookLoginRequiredEnabled,
                       facebook_proposals_submitted_enabled = :facebookProposalsSubmittedEnabled,
                       updated_at = now()
                RETURNING app_user_id, provider, pushover_user_key_encrypted, pushover_user_key_suffix,
                          pushover_devices, facebook_login_required_enabled,
                          facebook_proposals_submitted_enabled, created_at, updated_at
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("appUserId", appUserId)
                .addValue("pushoverUserKeyEncrypted", pushoverUserKeyEncrypted)
                .addValue("pushoverUserKeySuffix", pushoverUserKeySuffix)
                .addValue("pushoverDevices", PushoverDevices.format(pushoverDevices))
                .addValue("facebookLoginRequiredEnabled", facebookLoginRequiredEnabled)
                .addValue("facebookProposalsSubmittedEnabled", facebookProposalsSubmittedEnabled),
            PREFERENCE_ROW_MAPPER,
        ).single()

    fun findPushoverRecipientsForFacebookLoginRequired(): List<NotificationRecipient> =
        jdbc.query(
            """
                SELECT np.app_user_id, au.email, au.role, np.pushover_user_key_encrypted,
                       np.pushover_devices
                  FROM notification_preference np
                  JOIN app_user au ON au.id = np.app_user_id
                 WHERE np.provider = 'PUSHOVER'
                   AND np.facebook_login_required_enabled = true
                   AND au.status = 'ACTIVE'
                   AND au.role = 'ADMIN'
            """.trimIndent(),
            emptyMap<String, Any>(),
            RECIPIENT_ROW_MAPPER,
        )

    fun findPushoverRecipientsForProposalSummary(): List<NotificationRecipient> =
        jdbc.query(
            """
                SELECT np.app_user_id, au.email, au.role, np.pushover_user_key_encrypted,
                       np.pushover_devices
                  FROM notification_preference np
                  JOIN app_user au ON au.id = np.app_user_id
                 WHERE np.provider = 'PUSHOVER'
                   AND np.facebook_proposals_submitted_enabled = true
                   AND au.status = 'ACTIVE'
            """.trimIndent(),
            emptyMap<String, Any>(),
            RECIPIENT_ROW_MAPPER,
        )

    companion object {
        private val PREFERENCE_ROW_MAPPER = RowMapper<NotificationPreference> { rs, _ ->
            NotificationPreference(
                appUserId = rs.getLong("app_user_id"),
                provider = NotificationProvider.valueOf(rs.getString("provider")),
                pushoverUserKeyEncrypted = rs.getString("pushover_user_key_encrypted"),
                pushoverUserKeySuffix = rs.getString("pushover_user_key_suffix"),
                pushoverDevices = PushoverDevices.parse(rs.getString("pushover_devices")),
                facebookLoginRequiredEnabled = rs.getBoolean("facebook_login_required_enabled"),
                facebookProposalsSubmittedEnabled = rs.getBoolean("facebook_proposals_submitted_enabled"),
                createdAt = rs.getTimestamp("created_at")?.toInstant(),
                updatedAt = rs.getTimestamp("updated_at")?.toInstant(),
            )
        }

        private val RECIPIENT_ROW_MAPPER = RowMapper<NotificationRecipient> { rs, _ ->
            NotificationRecipient(
                appUserId = rs.getLong("app_user_id"),
                email = rs.getString("email"),
                role = Role.valueOf(rs.getString("role")),
                pushoverUserKeyEncrypted = rs.getString("pushover_user_key_encrypted"),
                pushoverDevices = PushoverDevices.parse(rs.getString("pushover_devices")),
            )
        }
    }
}

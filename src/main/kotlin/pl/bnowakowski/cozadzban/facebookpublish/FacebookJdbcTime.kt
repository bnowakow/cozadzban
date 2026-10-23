// SPDX-License-Identifier: GPL-3.0-or-later
package pl.bnowakowski.cozadzban.facebookpublish

import org.springframework.jdbc.core.SqlParameterValue
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/** PgJDBC cannot infer a JDBC type for Instant passed through NamedParameterJdbcTemplate. */
internal fun utcTimestamp(value: Instant): SqlParameterValue = SqlParameterValue(
    Types.TIMESTAMP_WITH_TIMEZONE,
    OffsetDateTime.ofInstant(value, ZoneOffset.UTC),
)

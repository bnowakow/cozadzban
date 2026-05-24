// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban.facebookimport

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.Timestamp
import java.time.Instant

@Repository
class FacebookImportRunRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun recordBatch(
        importRunId: String,
        discoveredCount: Int,
        submittedCount: Int,
        skippedExistingCount: Int,
        logsCompressed: ByteArray?,
    ) {
        jdbc.update(
            """
                INSERT INTO facebook_import_run(
                    import_run_id, status, discovered_count, submitted_count,
                    skipped_existing_count, summary_logs_compressed
                )
                VALUES (
                    :importRunId, 'RUNNING', :discoveredCount, :submittedCount,
                    :skippedExistingCount, :logsCompressed
                )
                ON CONFLICT (import_run_id) DO UPDATE
                   SET discovered_count = facebook_import_run.discovered_count + :discoveredCount,
                       submitted_count = facebook_import_run.submitted_count + :submittedCount,
                       skipped_existing_count = facebook_import_run.skipped_existing_count + :skippedExistingCount,
                       summary_logs_compressed = COALESCE(:logsCompressed, facebook_import_run.summary_logs_compressed)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("importRunId", importRunId)
                .addValue("discoveredCount", discoveredCount.coerceAtLeast(0))
                .addValue("submittedCount", submittedCount.coerceAtLeast(0))
                .addValue("skippedExistingCount", skippedExistingCount.coerceAtLeast(0))
                .addValue("logsCompressed", logsCompressed),
        )
    }

    fun complete(
        importRunId: String,
        status: FacebookImportRunStatus,
        discoveredCount: Int,
        submittedCount: Int,
        skippedExistingCount: Int,
        failedCount: Int,
        logsCompressed: ByteArray?,
    ) {
        jdbc.update(
            """
                INSERT INTO facebook_import_run(
                    import_run_id, status, finished_at, discovered_count, submitted_count,
                    skipped_existing_count, failed_count, summary_logs_compressed
                )
                VALUES (
                    :importRunId, :status, now(), :discoveredCount, :submittedCount,
                    :skippedExistingCount, :failedCount, :logsCompressed
                )
                ON CONFLICT (import_run_id) DO UPDATE
                   SET status = :status,
                       finished_at = now(),
                       discovered_count = GREATEST(facebook_import_run.discovered_count, :discoveredCount),
                       submitted_count = GREATEST(facebook_import_run.submitted_count, :submittedCount),
                       skipped_existing_count = GREATEST(facebook_import_run.skipped_existing_count, :skippedExistingCount),
                       failed_count = GREATEST(facebook_import_run.failed_count, :failedCount),
                       summary_logs_compressed = COALESCE(:logsCompressed, facebook_import_run.summary_logs_compressed)
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("importRunId", importRunId)
                .addValue("status", status.name)
                .addValue("discoveredCount", discoveredCount.coerceAtLeast(0))
                .addValue("submittedCount", submittedCount.coerceAtLeast(0))
                .addValue("skippedExistingCount", skippedExistingCount.coerceAtLeast(0))
                .addValue("failedCount", failedCount.coerceAtLeast(0))
                .addValue("logsCompressed", logsCompressed),
        )
    }

    fun recordLoginRequired(
        importRunId: String,
        trigger: FacebookImportTrigger,
        profileUrl: String,
        detectedAt: Instant,
    ): Boolean {
        val updated = jdbc.queryForObject(
            """
                INSERT INTO facebook_import_run(
                    import_run_id, status, login_required_first_at, login_required_last_at,
                    login_required_count, login_required_trigger, login_required_profile_url
                )
                VALUES (
                    :importRunId, 'RUNNING', :detectedAt, :detectedAt,
                    1, :trigger, :profileUrl
                )
                ON CONFLICT (import_run_id) DO UPDATE
                   SET login_required_first_at = COALESCE(facebook_import_run.login_required_first_at, :detectedAt),
                       login_required_last_at = :detectedAt,
                       login_required_count = facebook_import_run.login_required_count + 1,
                       login_required_trigger = :trigger,
                       login_required_profile_url = :profileUrl
                RETURNING login_required_count
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("importRunId", importRunId)
                .addValue("trigger", trigger.name)
                .addValue("profileUrl", profileUrl)
                .addValue("detectedAt", Timestamp.from(detectedAt)),
            Int::class.java,
        ) ?: 0
        return updated == 1
    }
}

@Repository
class FacebookArticleProposalRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun existsByCanonicalArticleUrl(canonicalArticleUrl: String): Boolean =
        jdbc.queryForObject(
            """
                SELECT EXISTS(
                    SELECT 1
                      FROM facebook_article_proposal
                     WHERE canonical_article_url = :canonicalArticleUrl
                )
            """.trimIndent(),
            mapOf("canonicalArticleUrl" to canonicalArticleUrl),
            Boolean::class.java,
        ) == true

    fun findByCanonicalArticleUrl(canonicalArticleUrl: String): FacebookArticleProposal? =
        jdbc.query(
            SELECT_PROPOSAL_SQL + " WHERE canonical_article_url = :canonicalArticleUrl",
            mapOf("canonicalArticleUrl" to canonicalArticleUrl),
            PROPOSAL_ROW_MAPPER,
        ).firstOrNull()

    fun findById(id: Long): FacebookArticleProposal? =
        jdbc.query(
            SELECT_PROPOSAL_SQL + " WHERE id = :id",
            mapOf("id" to id),
            PROPOSAL_ROW_MAPPER,
        ).firstOrNull()

    fun insert(
        candidateId: String,
        importRunId: String,
        articleUrl: String,
        canonicalArticleUrl: String,
        facebookPostUrl: String?,
        guessedLanguage: String,
        logsCompressed: ByteArray?,
    ): FacebookArticleProposal =
        jdbc.query(
            """
                INSERT INTO facebook_article_proposal(
                    candidate_id, import_run_id, article_url, canonical_article_url,
                    facebook_post_url, guessed_language, logs_compressed
                )
                VALUES (
                    :candidateId, :importRunId, :articleUrl, :canonicalArticleUrl,
                    :facebookPostUrl, :guessedLanguage, :logsCompressed
                )
                RETURNING id, candidate_id, import_run_id, article_url, canonical_article_url,
                          facebook_post_url, guessed_language, corrected_language, status, article_id,
                          decided_by_user_id, decided_at, submitted_at, last_seen_at, logs_compressed
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("candidateId", candidateId)
                .addValue("importRunId", importRunId)
                .addValue("articleUrl", articleUrl)
                .addValue("canonicalArticleUrl", canonicalArticleUrl)
                .addValue("facebookPostUrl", facebookPostUrl)
                .addValue("guessedLanguage", guessedLanguage)
                .addValue("logsCompressed", logsCompressed),
            PROPOSAL_ROW_MAPPER,
        ).single()

    fun updateSeen(id: Long, importRunId: String, facebookPostUrl: String?, logsCompressed: ByteArray?) {
        jdbc.update(
            """
                UPDATE facebook_article_proposal
                   SET last_seen_at = now(),
                       import_run_id = :importRunId,
                       facebook_post_url = COALESCE(facebook_post_url, :facebookPostUrl),
                       logs_compressed = COALESCE(:logsCompressed, logs_compressed)
                 WHERE id = :id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id)
                .addValue("importRunId", importRunId)
                .addValue("facebookPostUrl", facebookPostUrl)
                .addValue("logsCompressed", logsCompressed),
        )
    }

    fun findPage(statusFilter: FacebookArticleProposalStatusFilter, page: Int, size: Int): List<FacebookArticleProposal> {
        val (whereClause, params) = statusWhereClause(statusFilter)
        params.addValue("limit", size.coerceAtLeast(1))
        params.addValue("offset", page.coerceAtLeast(0) * size.coerceAtLeast(1))
        return jdbc.query(
            """
                $SELECT_PROPOSAL_SQL
                $whereClause
                ORDER BY submitted_at DESC, id DESC
                LIMIT :limit OFFSET :offset
            """.trimIndent(),
            params,
            PROPOSAL_ROW_MAPPER,
        )
    }

    fun count(statusFilter: FacebookArticleProposalStatusFilter): Long {
        val (whereClause, params) = statusWhereClause(statusFilter)
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM facebook_article_proposal $whereClause",
            params,
            Long::class.java,
        ) ?: 0L
    }

    fun markAccepted(
        id: Long,
        articleId: Long,
        decidedByUserId: Long,
        correctedLanguage: String,
        logsCompressed: ByteArray?,
    ) {
        markDecision(
            id = id,
            status = FacebookArticleProposalStatus.ACCEPTED,
            articleId = articleId,
            decidedByUserId = decidedByUserId,
            correctedLanguage = correctedLanguage,
            logsCompressed = logsCompressed,
        )
    }

    fun markRejected(id: Long, decidedByUserId: Long, correctedLanguage: String, logsCompressed: ByteArray?) {
        markDecision(
            id = id,
            status = FacebookArticleProposalStatus.REJECTED,
            articleId = null,
            decidedByUserId = decidedByUserId,
            correctedLanguage = correctedLanguage,
            logsCompressed = logsCompressed,
        )
    }

    fun markFailed(id: Long, decidedByUserId: Long, correctedLanguage: String, logsCompressed: ByteArray?) {
        markDecision(
            id = id,
            status = FacebookArticleProposalStatus.FAILED,
            articleId = null,
            decidedByUserId = decidedByUserId,
            correctedLanguage = correctedLanguage,
            logsCompressed = logsCompressed,
        )
    }

    fun markAlreadyExists(id: Long, decidedByUserId: Long, correctedLanguage: String, logsCompressed: ByteArray?) {
        markDecision(
            id = id,
            status = FacebookArticleProposalStatus.ALREADY_EXISTS,
            articleId = null,
            decidedByUserId = decidedByUserId,
            correctedLanguage = correctedLanguage,
            logsCompressed = logsCompressed,
        )
    }

    private fun markDecision(
        id: Long,
        status: FacebookArticleProposalStatus,
        articleId: Long?,
        decidedByUserId: Long,
        correctedLanguage: String,
        logsCompressed: ByteArray?,
    ) {
        jdbc.update(
            """
                UPDATE facebook_article_proposal
                   SET status = :status,
                       article_id = :articleId,
                       decided_by_user_id = :decidedByUserId,
                       decided_at = now(),
                       corrected_language = :correctedLanguage,
                       logs_compressed = COALESCE(:logsCompressed, logs_compressed)
                 WHERE id = :id
            """.trimIndent(),
            MapSqlParameterSource()
                .addValue("id", id)
                .addValue("status", status.name)
                .addValue("articleId", articleId)
                .addValue("decidedByUserId", decidedByUserId)
                .addValue("correctedLanguage", correctedLanguage)
                .addValue("logsCompressed", logsCompressed),
        )
    }

    private fun statusWhereClause(statusFilter: FacebookArticleProposalStatusFilter): Pair<String, MapSqlParameterSource> {
        val params = MapSqlParameterSource()
        return when (statusFilter) {
            FacebookArticleProposalStatusFilter.PENDING -> "WHERE status IS NULL" to params
            FacebookArticleProposalStatusFilter.ACCEPTED,
            FacebookArticleProposalStatusFilter.REJECTED,
            FacebookArticleProposalStatusFilter.FAILED,
            FacebookArticleProposalStatusFilter.ALREADY_EXISTS -> {
                params.addValue("status", statusFilter.name)
                "WHERE status = :status" to params
            }
            FacebookArticleProposalStatusFilter.ALL -> "" to params
        }
    }

    private companion object {
        const val SELECT_PROPOSAL_SQL = """
            SELECT id, candidate_id, import_run_id, article_url, canonical_article_url,
                   facebook_post_url, guessed_language, corrected_language, status, article_id,
                   decided_by_user_id, decided_at, submitted_at, last_seen_at, logs_compressed
              FROM facebook_article_proposal
        """

        val PROPOSAL_ROW_MAPPER = RowMapper<FacebookArticleProposal> { rs, _ ->
            FacebookArticleProposal(
                id = rs.getLong("id"),
                candidateId = rs.getString("candidate_id"),
                importRunId = rs.getString("import_run_id"),
                articleUrl = rs.getString("article_url"),
                canonicalArticleUrl = rs.getString("canonical_article_url"),
                facebookPostUrl = rs.getString("facebook_post_url"),
                guessedLanguage = rs.getString("guessed_language"),
                correctedLanguage = rs.getString("corrected_language"),
                status = rs.getString("status")?.let(FacebookArticleProposalStatus::valueOf),
                articleId = rs.getLongOrNull("article_id"),
                decidedByUserId = rs.getLongOrNull("decided_by_user_id"),
                decidedAt = rs.getTimestamp("decided_at")?.toInstant(),
                submittedAt = rs.getTimestamp("submitted_at")?.toInstant() ?: Instant.EPOCH,
                lastSeenAt = rs.getTimestamp("last_seen_at")?.toInstant() ?: Instant.EPOCH,
                logsCompressed = rs.getBytes("logs_compressed"),
            )
        }

        fun java.sql.ResultSet.getLongOrNull(column: String): Long? {
            val value = getLong(column)
            return if (wasNull()) null else value
        }

        @Suppress("unused")
        fun timestamp(value: Instant?): Timestamp? = value?.let(Timestamp::from)
    }
}

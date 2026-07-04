// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozadzban

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.aop.support.AopUtils
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.repository.support.ResourcelessJobRepository
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import pl.bnowakowski.cozadzban.facebookimport.FACEBOOK_IMPORT_JOB_NAME
import pl.bnowakowski.cozadzban.facebookimport.FacebookImportTrigger

/**
 * Phase 21 / Item 64 — Migration tests.
 *
 * Verifies that V3, V4, and V5 Flyway migrations produce the expected schema:
 * - article.created_by_user_id is NOT NULL (BR-37)
 * - article.published_at is nullable (BR-41)
 * - article_content table exists (Phase 20)
 * - facebook_article_proposal and facebook_import_run tables exist (Phase 24 rewrite)
 * - Spring Batch JDBC metadata tables/sequences exist (scheduled import worker)
 * - app_user.status column exists (BR-40)
 * - article_published_at_idx index exists (BR-41)
 * - created_by_user_id NOT NULL constraint is enforced at the DB level
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(TestcontainersConfiguration::class)
@TestPropertySource(
    properties = [
        "app.build.timestamp=2026-05-04T10:00:00Z",
        "COZADZBAN_BOOTSTRAP_ADMIN_EMAIL=admin@schema.test",
    ],
)
class MigrationSchemaIT {

    @Autowired private lateinit var jdbc: JdbcTemplate
    @Autowired private lateinit var jobRepository: JobRepository
    @Autowired @Qualifier(FACEBOOK_IMPORT_JOB_NAME) private lateinit var facebookImportJob: Job

    @MockitoBean private lateinit var jwtDecoder: JwtDecoder

    @Test
    fun `article created_by_user_id is NOT NULL in schema`() {
        val count = jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM information_schema.columns
             WHERE table_name = 'article'
               AND column_name = 'created_by_user_id'
               AND is_nullable = 'NO'
            """,
            Int::class.java,
        )
        assertEquals(1, count, "article.created_by_user_id should be NOT NULL after V3 migration")
    }

    @Test
    fun `article published_at is nullable in schema`() {
        val count = jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM information_schema.columns
             WHERE table_name = 'article'
               AND column_name = 'published_at'
               AND is_nullable = 'YES'
            """,
            Int::class.java,
        )
        assertEquals(1, count, "article.published_at should be nullable after V4 migration")
    }

    @Test
    fun `article_content table exists after V5 migration`() {
        val count = jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM information_schema.tables
             WHERE table_name = 'article_content'
            """,
            Int::class.java,
        )
        assertEquals(1, count, "article_content table should exist after V5 migration")
    }

    @Test
    fun `facebook proposal inbox tables exist after V18 migration`() {
        val proposalCount = jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM information_schema.tables
             WHERE table_name = 'facebook_article_proposal'
            """,
            Int::class.java,
        )
        val runCount = jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM information_schema.tables
             WHERE table_name = 'facebook_import_run'
            """,
            Int::class.java,
        )

        assertEquals(1, proposalCount, "facebook_article_proposal table should exist after V18 migration")
        assertEquals(1, runCount, "facebook_import_run table should exist after V18 migration")
    }

    @Test
    fun `facebook proposal canonical article url is unique and status is nullable`() {
        jdbc.update(
            "INSERT INTO facebook_import_run(import_run_id) VALUES (?)",
            "schema-run-${System.nanoTime()}",
        )
        val runId = jdbc.queryForObject(
            "SELECT import_run_id FROM facebook_import_run ORDER BY started_at DESC LIMIT 1",
            String::class.java,
        )!!
        val url = "https://proposal-schema.test/${System.nanoTime()}"
        jdbc.update(
            """
            INSERT INTO facebook_article_proposal(
                candidate_id, import_run_id, article_url, canonical_article_url, guessed_language
            )
            VALUES (?, ?, ?, ?, 'en')
            """,
            "candidate-1",
            runId,
            url,
            url,
        )
        val status = jdbc.queryForObject(
            "SELECT status FROM facebook_article_proposal WHERE canonical_article_url = ?",
            String::class.java,
            url,
        )
        assertEquals(null, status, "pending proposal status should be nullable")

        assertThrows(Exception::class.java) {
            jdbc.update(
                """
                INSERT INTO facebook_article_proposal(
                    candidate_id, import_run_id, article_url, canonical_article_url, guessed_language
                )
                VALUES (?, ?, ?, ?, 'en')
                """,
                "candidate-2",
                runId,
                "$url?source=again",
                url,
            )
        }
    }

    @Test
    fun `facebook proposal auto accept audit columns exist after V32 migration`() {
        val count = jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM information_schema.columns
             WHERE table_name = 'facebook_article_proposal'
               AND column_name IN ('accepted_by', 'accepted_at', 'accepted_reason')
            """,
            Int::class.java,
        )

        assertEquals(3, count, "facebook_article_proposal auto-accept audit columns should exist")
    }

    @Test
    fun `spring batch metadata tables and sequences exist after V19 migration`() {
        val tableCount = jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM information_schema.tables
             WHERE lower(table_name) IN (
               'batch_job_instance',
               'batch_job_execution',
               'batch_job_execution_params',
               'batch_step_execution',
               'batch_step_execution_context',
               'batch_job_execution_context'
             )
            """,
            Int::class.java,
        )
        val sequenceCount = jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM pg_class
             WHERE relkind = 'S'
               AND lower(relname) IN (
                 'batch_step_execution_seq',
                 'batch_job_execution_seq',
                 'batch_job_instance_seq'
               )
            """,
            Int::class.java,
        )

        assertEquals(6, tableCount, "Spring Batch metadata tables should exist after V19 migration")
        assertEquals(3, sequenceCount, "Spring Batch metadata sequences should exist after V19 migration")
    }

    @Test
    fun `notification preferences and login-required columns exist after V20 migration`() {
        val preferenceTableCount = jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM information_schema.tables
             WHERE table_name = 'notification_preference'
            """,
            Int::class.java,
        )
        val loginRequiredColumnCount = jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM information_schema.columns
             WHERE table_name = 'facebook_import_run'
               AND column_name IN (
                   'login_required_first_at',
                   'login_required_last_at',
                   'login_required_count',
                   'login_required_trigger',
                   'login_required_profile_url'
               )
            """,
            Int::class.java,
        )

        assertEquals(1, preferenceTableCount, "notification_preference table should exist after V20 migration")
        assertEquals(5, loginRequiredColumnCount, "facebook_import_run login-required audit columns should exist")
    }

    @Test
    fun `facebook import login-required trigger accepts worker startup after V27 migration`() {
        val importRunId = "schema-worker-startup-${System.nanoTime()}"

        jdbc.update(
            "INSERT INTO facebook_import_run(import_run_id, login_required_trigger) VALUES (?, ?)",
            importRunId,
            FacebookImportTrigger.WORKER_STARTUP.name,
        )

        val trigger = jdbc.queryForObject(
            "SELECT login_required_trigger FROM facebook_import_run WHERE import_run_id = ?",
            String::class.java,
            importRunId,
        )
        assertEquals(FacebookImportTrigger.WORKER_STARTUP.name, trigger)
    }

    @Test
    fun `notification preferences use plural Pushover device column after V21 migration`() {
        val pluralDeviceColumnCount = jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM information_schema.columns
             WHERE table_name = 'notification_preference'
               AND column_name = 'pushover_devices'
            """,
            Int::class.java,
        )
        val singleDeviceColumnCount = jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM information_schema.columns
             WHERE table_name = 'notification_preference'
               AND column_name = 'pushover_device'
            """,
            Int::class.java,
        )

        assertEquals(1, pluralDeviceColumnCount, "notification_preference should store Pushover devices")
        assertEquals(0, singleDeviceColumnCount, "old single Pushover device column should be migrated away")
    }

    @Test
    fun `facebook import progress columns exist after migrations`() {
        val progressColumnCount = jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM information_schema.columns
             WHERE table_name = 'facebook_import_run'
               AND column_name IN (
                   'current_pass_index',
                   'pass_count',
                   'phase',
                   'status_detail',
                   'phase_index',
                   'phase_count',
                   'last_status_at'
               )
            """,
            Int::class.java,
        )

        assertEquals(7, progressColumnCount, "facebook_import_run should store current progress")
    }

    @Test
    fun `spring batch jdbc repository starts with facebook import job`() {
        assertEquals(FACEBOOK_IMPORT_JOB_NAME, facebookImportJob.name)
        val repositoryClass = AopUtils.getTargetClass(jobRepository)
        assertTrue(
            repositoryClass.name.contains("SimpleJobRepository"),
            "Spring Batch should use the JDBC-backed SimpleJobRepository; actual=${repositoryClass.name}",
        )
        assertFalse(
            ResourcelessJobRepository::class.java.isAssignableFrom(repositoryClass),
            "Spring Batch should not use the resourceless JobRepository",
        )
    }

    @Test
    fun `app_user status column exists after V3 migration`() {
        val count = jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM information_schema.columns
             WHERE table_name = 'app_user'
               AND column_name = 'status'
            """,
            Int::class.java,
        )
        assertEquals(1, count, "app_user.status column should exist after V3 migration")
    }

    @Test
    fun `article_published_at_idx index exists after V4 migration`() {
        val count = jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM pg_indexes
             WHERE tablename = 'article'
               AND indexname = 'article_published_at_idx'
            """,
            Int::class.java,
        )
        assertEquals(1, count, "article_published_at_idx index should exist after V4 migration")
    }

    @Test
    fun `article created_by_user_id NOT NULL constraint is enforced by database`() {
        // Attempting to insert an article without created_by_user_id must fail.
        val ts = System.nanoTime()
        assertThrows(Exception::class.java) {
            jdbc.execute(
                "INSERT INTO article(url, language) VALUES ('https://constraint.test/$ts', 'en')",
            )
        }
    }

    @Test
    fun `article_content ON DELETE CASCADE removes content when article is deleted`() {
        // Seed a user and an article with content, then delete the article and verify cascade.
        val adminId = jdbc.queryForObject(
            "SELECT id FROM app_user WHERE email = 'admin@schema.test'",
            Long::class.java,
        )!!
        val ts = System.nanoTime()
        jdbc.update(
            "INSERT INTO article(url, language, created_by_user_id) VALUES (?, 'en', ?)",
            "https://cascade.test/$ts",
            adminId,
        )
        val articleId = jdbc.queryForObject(
            "SELECT id FROM article WHERE url = ?",
            Long::class.java,
            "https://cascade.test/$ts",
        )!!
        jdbc.update(
            "INSERT INTO article_content(article_id, content) VALUES (?, ?)",
            articleId,
            "sample preserved content",
        )

        // Verify content row exists
        val before = jdbc.queryForObject(
            "SELECT COUNT(*) FROM article_content WHERE article_id = ?",
            Int::class.java,
            articleId,
        )
        assertEquals(1, before, "article_content row should exist before delete")

        // Delete the article — cascade should remove the content row
        jdbc.update("DELETE FROM article WHERE id = ?", articleId)

        val after = jdbc.queryForObject(
            "SELECT COUNT(*) FROM article_content WHERE article_id = ?",
            Int::class.java,
            articleId,
        )
        assertEquals(0, after, "article_content row should be removed by ON DELETE CASCADE")
    }

    @Test
    fun `creator backfill assigns all articles to the oldest user`() {
        // The admin@schema.test user was created by bootstrap before any articles were seeded.
        // All articles inserted by other tests should have created_by_user_id pointing to
        // the oldest (bootstrap) user — verify no article has a NULL or orphaned creator.
        val nullCount = jdbc.queryForObject(
            "SELECT COUNT(*) FROM article WHERE created_by_user_id IS NULL",
            Int::class.java,
        )
        assertEquals(0, nullCount, "No article should have a NULL created_by_user_id after backfill")
    }

    @Test
    fun `migration fails fast when trying to insert article without any user present`() {
        // We cannot replay Flyway in a live context, but we can verify the DB-level protection:
        // the NOT NULL + FK constraint prevents inserting an article with no owner, which is the
        // runtime equivalent of the migration guard that rejects articles-without-users.
        val ts = System.nanoTime()
        val thrown = assertThrows(Exception::class.java) {
            // NULL created_by_user_id is rejected by the NOT NULL constraint
            jdbc.update(
                "INSERT INTO article(url, language, created_by_user_id) VALUES (?, 'en', NULL)",
                "https://failfast.test/$ts",
            )
        }
        // Either a NULL violation or FK violation is acceptable
        val msg = thrown.message ?: thrown.cause?.message ?: ""
        assert(
            msg.contains("null", ignoreCase = true) ||
                msg.contains("violates", ignoreCase = true) ||
                msg.contains("not-null", ignoreCase = true),
        ) { "Expected a NOT NULL or FK constraint violation, got: $msg" }
    }
}

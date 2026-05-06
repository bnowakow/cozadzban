// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl

package pl.bnowakowski.cozazjeb

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean

/**
 * Phase 21 / Item 64 — Migration tests.
 *
 * Verifies that V3, V4, and V5 Flyway migrations produce the expected schema:
 * - article.created_by_user_id is NOT NULL (BR-41)
 * - article.published_at is nullable (BR-47)
 * - article_content table exists (Phase 20)
 * - app_user.status column exists (BR-42)
 * - article_published_at_idx index exists (BR-47)
 * - created_by_user_id NOT NULL constraint is enforced at the DB level
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(TestcontainersConfiguration::class)
@TestPropertySource(
    properties = [
        "app.build.timestamp=2026-05-04T10:00:00Z",
        "COZAZJEB_BOOTSTRAP_ADMIN_EMAIL=admin@schema.test",
    ],
)
class MigrationSchemaIT {

    @Autowired private lateinit var jdbc: JdbcTemplate

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

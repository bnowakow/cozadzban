-- Meta confirmed deletion of this post on 2026-11-04, but the subsequent local
-- confirmation failed because an untyped null parameter was used in the SQL.
UPDATE facebook_page_sync sync
SET status = 'CONFIRMED', completed_at = now(), last_error = NULL, updated_at = now()
FROM facebook_page_publication publication
WHERE sync.publication_id = publication.id
  AND publication.article_id_snapshot = 630
  AND publication.post_id = '1415412431653305_122107478625524760'
  AND sync.action = 'DELETE'
  AND sync.status = 'NEEDS_RECONCILIATION'
  AND sync.last_error = 'BadSqlGrammarException';

UPDATE facebook_page_publication publication
SET status = 'DELETED', updated_at = now()
WHERE publication.article_id_snapshot = 630
  AND publication.post_id = '1415412431653305_122107478625524760'
  AND EXISTS (
      SELECT 1 FROM facebook_page_sync sync
      WHERE sync.publication_id = publication.id
        AND sync.action = 'DELETE'
        AND sync.status = 'CONFIRMED'
  );

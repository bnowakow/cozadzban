-- Remove a duplicate Facebook import where the same short post was saved under two pfbid URLs.
-- Keep the earlier row and delete the later alias.

DELETE FROM article_content
 WHERE article_id IN (
     SELECT duplicate.id
       FROM article duplicate
       JOIN article canonical
         ON canonical.url = 'https://www.facebook.com/serwisdonaldpl/posts/pfbid0NrAXQE57R6yqPjam86nLnRYM5jdaSCLo7SwxJCh1w3ZPLykqUH41E4SJ9uM2HR2Ul'
      WHERE duplicate.url = 'https://www.facebook.com/serwisdonaldpl/posts/pfbid0NyhhziCpsmtnWwrhc9m4wfQ4ZP8h1K42QVox9Zt7U5pFmaYRvMF6KPeBgYnTaeRZl'
        AND duplicate.published_at = canonical.published_at
 );

DELETE FROM article
 WHERE id IN (
     SELECT duplicate.id
       FROM article duplicate
       JOIN article canonical
         ON canonical.url = 'https://www.facebook.com/serwisdonaldpl/posts/pfbid0NrAXQE57R6yqPjam86nLnRYM5jdaSCLo7SwxJCh1w3ZPLykqUH41E4SJ9uM2HR2Ul'
      WHERE duplicate.url = 'https://www.facebook.com/serwisdonaldpl/posts/pfbid0NyhhziCpsmtnWwrhc9m4wfQ4ZP8h1K42QVox9Zt7U5pFmaYRvMF6KPeBgYnTaeRZl'
        AND duplicate.published_at = canonical.published_at
 );

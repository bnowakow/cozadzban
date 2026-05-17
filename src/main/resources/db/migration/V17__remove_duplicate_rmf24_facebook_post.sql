-- Remove a duplicate RMF24 Facebook import saved under two pfbid URLs.
-- Keep the earlier row and delete the later alias.

DELETE FROM article_content
 WHERE article_id IN (
     SELECT duplicate.id
       FROM article duplicate
       JOIN article canonical
         ON canonical.url = 'https://www.facebook.com/rmf24/posts/pfbid0qX8Gq1thmkWNY64v6LHdjmw5xsYWX1KDC5mrtumXepgTviJY38DE12DYCVHYfvq9l'
      WHERE duplicate.url = 'https://www.facebook.com/rmf24/posts/pfbid02uCJeLfen5QD4ZMexNhcd1J3ALgqobpS84BLfZ8xEdeW1jJAXYfvbevbPRz1AvgrTl'
        AND duplicate.published_at = canonical.published_at
        AND duplicate.title = canonical.title
 );

DELETE FROM article
 WHERE id IN (
     SELECT duplicate.id
       FROM article duplicate
       JOIN article canonical
         ON canonical.url = 'https://www.facebook.com/rmf24/posts/pfbid0qX8Gq1thmkWNY64v6LHdjmw5xsYWX1KDC5mrtumXepgTviJY38DE12DYCVHYfvq9l'
      WHERE duplicate.url = 'https://www.facebook.com/rmf24/posts/pfbid02uCJeLfen5QD4ZMexNhcd1J3ALgqobpS84BLfZ8xEdeW1jJAXYfvbevbPRz1AvgrTl'
        AND duplicate.published_at = canonical.published_at
        AND duplicate.title = canonical.title
 );

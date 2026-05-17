-- Remove duplicate Facebook imports saved through alternate Facebook URL shapes.
-- Keep the earlier canonical rows and delete later aliases.

DELETE FROM article_content
 WHERE article_id IN (
     SELECT duplicate.id
       FROM article duplicate
       JOIN article canonical
         ON canonical.url = 'https://www.facebook.com/reel/2758125771253657/'
      WHERE duplicate.url = 'https://www.facebook.com/bartek.dobrowolski.nowakowski/posts/pfbid0t84F3bzWBM86PhPcsmXnXQhmx1pXa7X2wKFZC46897JSDhdqiUBH5fHuT5HZ1ZjTl'
        AND duplicate.published_at = canonical.published_at
 );

DELETE FROM article
 WHERE id IN (
     SELECT duplicate.id
       FROM article duplicate
       JOIN article canonical
         ON canonical.url = 'https://www.facebook.com/reel/2758125771253657/'
      WHERE duplicate.url = 'https://www.facebook.com/bartek.dobrowolski.nowakowski/posts/pfbid0t84F3bzWBM86PhPcsmXnXQhmx1pXa7X2wKFZC46897JSDhdqiUBH5fHuT5HZ1ZjTl'
        AND duplicate.published_at = canonical.published_at
 );

DELETE FROM article_content
 WHERE article_id IN (
     SELECT duplicate.id
       FROM article duplicate
       JOIN article canonical
         ON canonical.url = 'https://www.facebook.com/jakub.wiech.mikroblog/posts/pfbid0cmzW1Mr2ZtDhdBUVJEJWVzXNHRfkr3g8RbAEG5rhtV8ZCMzG9jXjZXFPQJeNNfFdl'
      WHERE duplicate.url = 'https://www.facebook.com/photo/?fbid=1496190555209039&set=a.248625223298918'
        AND duplicate.published_at = canonical.published_at
 );

DELETE FROM article
 WHERE id IN (
     SELECT duplicate.id
       FROM article duplicate
       JOIN article canonical
         ON canonical.url = 'https://www.facebook.com/jakub.wiech.mikroblog/posts/pfbid0cmzW1Mr2ZtDhdBUVJEJWVzXNHRfkr3g8RbAEG5rhtV8ZCMzG9jXjZXFPQJeNNfFdl'
      WHERE duplicate.url = 'https://www.facebook.com/photo/?fbid=1496190555209039&set=a.248625223298918'
        AND duplicate.published_at = canonical.published_at
 );

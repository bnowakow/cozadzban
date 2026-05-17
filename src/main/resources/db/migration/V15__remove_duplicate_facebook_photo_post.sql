-- Remove a duplicate Facebook import where the same post was later saved as a photo URL.
-- Keep the earlier profile-post URL and delete the later photo alias.

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

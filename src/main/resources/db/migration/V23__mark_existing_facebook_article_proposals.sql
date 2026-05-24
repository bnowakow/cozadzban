UPDATE facebook_article_proposal proposal
   SET status = 'ALREADY_EXISTS',
       article_id = article.id,
       decided_at = COALESCE(proposal.decided_at, now())
  FROM article
 WHERE proposal.status IS NULL
   AND proposal.canonical_article_url = article.url;

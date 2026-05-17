-- Facebook import marker text is a discovery signal, not an article quote.

UPDATE article
   SET quote = NULL
 WHERE quote IS NOT NULL
   AND lower(trim(quote)) = 'co za zjeb';

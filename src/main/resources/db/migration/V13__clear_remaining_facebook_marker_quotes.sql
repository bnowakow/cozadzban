-- Facebook import marker text is a discovery signal, not article quote content.
-- V12 handled an exact lower-case match; this catches already-applied databases
-- with casing, repeated whitespace, non-breaking spaces, translated marker text,
-- or Facebook plugin shell text around the marker.

UPDATE article
   SET quote = NULL
 WHERE quote IS NOT NULL
   AND (
       lower(regexp_replace(replace(quote, chr(160), ' '), '\s+', ' ', 'g')) LIKE '%co za zjeb%'
       OR lower(regexp_replace(replace(quote, chr(160), ' '), '\s+', ' ', 'g')) LIKE '%what a fucker%'
   );

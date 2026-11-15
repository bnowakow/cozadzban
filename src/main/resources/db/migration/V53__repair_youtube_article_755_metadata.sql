-- Repair the YouTube page chrome captured for this article at import time.
-- The URL guard keeps this editorial correction tied to the intended video.
UPDATE article
SET title = 'Coffeezilla: śledztwo dotyczące prezydenta Trumpa',
    lead = 'W filmie „Investigating The President” Coffeezilla analizuje interesy Donalda Trumpa i jego rodziny, w tym World Liberty Financial. W opisie podaje źródła i stanowiska stron; World Liberty Financial oraz DRW/Don Wilson zaprzeczyli przedstawionym zarzutom.',
    thumbnail = 'https://i.ytimg.com/vi/W4PIkmjwGsQ/maxresdefault.jpg'
WHERE id = 755 AND url = 'https://youtu.be/W4PIkmjwGsQ';

UPDATE article_content
SET content = 'Film Coffeezilli „Investigating The President” dotyczy interesów Donalda Trumpa i jego rodziny, w tym World Liberty Financial. W opisie filmu autor podaje dokumenty sądowe, wywiady i inne materiały źródłowe. World Liberty Financial oraz DRW/Don Wilson zaprzeczyli przedstawionym zarzutom. Donald Trump, Donald Trump Jr. i Eric Trump nie odpowiedzieli na prośby o komentarz. Autor podkreśla, że materiał jest komentarzem i opinią opartą na dokumentach, wywiadach i innych źródłach; nie stanowi sądowego stwierdzenia winy.',
    truncated = false,
    captured_at = now()
WHERE article_id = 755
  AND EXISTS (SELECT 1 FROM article WHERE id = 755 AND url = 'https://youtu.be/W4PIkmjwGsQ');

-- Replace the YouTube site chrome saved before oEmbed URL encoding was fixed.
-- The URL guard limits this correction to the intended video.
UPDATE article
SET title = 'The Downfall of Jensen Huang: NVIDIA’s Greed & Corruption',
    lead = 'Gamers Nexus analizuje powiązania czasowe między decyzjami USA o kontroli eksportu chipów, spotkaniami Jensena Huanga i Donalda Trumpa oraz działalnością Alice Huang. Autorzy przedstawiają też ustalenia dotyczące prób reeksportu procesorów H200 do Rosji przez firmy w Chinach.',
    thumbnail = 'https://i.ytimg.com/vi/YYU_geUwQoA/maxresdefault.jpg'
WHERE id = 756 AND url = 'https://youtu.be/YYU_geUwQoA?si=hlbU93CAj2nIPJt1';

UPDATE article_content
SET content = 'W filmie „The Downfall of Jensen Huang: NVIDIA’s Greed & Corruption” Gamers Nexus zestawia kalendarium amerykańskich ograniczeń eksportowych z kontaktami szefa NVIDII Jensena Huanga z Donaldem Trumpem i Alice Huang. Materiał omawia również informacje pozyskane przez redakcję o firmach w Chinach próbujących reeksportować układy H200 do Rosji. Autorzy zaznaczają, że nie znają jeszcze znaczenia wszystkich zbiegów czasowych.',
    truncated = false,
    captured_at = now()
WHERE article_id = 756
  AND EXISTS (SELECT 1 FROM article WHERE id = 756 AND url = 'https://youtu.be/YYU_geUwQoA?si=hlbU93CAj2nIPJt1');

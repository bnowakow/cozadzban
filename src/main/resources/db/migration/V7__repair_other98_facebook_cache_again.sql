-- V6 could only help databases where the row matched at that exact migration
-- moment. Run a fresh, broader repair so already-migrated databases overwrite
-- the stale Facebook teaser cache.

INSERT INTO article_content (article_id, content, truncated, captured_at)
SELECT
    id,
    $cache$Pete Hegseth has fired 24 generals. Now he brings his wife to Pentagon meetings. She has no security clearance.The Guardian published a major investigation Sunday.
The headline finding: Hegseth's third wife, Jennifer Rauchet, a former Fox News producer with no government role and no public security clearance, has been showing up to Pentagon meetings. She sits in the back of the room. Pentagon press secretary Kingsley Wilson claims
Rauchet has "never attended a meeting where sensitive information or classified information was discussed." That claim is hard to square with the fact that Hegseth was already caught sharing planned Yemen airstrike details with Rauchet on Signal earlier this year.
This is happening because there is almost no one else left.
Hegseth has fired or forcibly retired 24 generals and senior commanders since January 2025. Around 60% of those forced out have been Black or female. Army Chief of Staff General Randy George was fired last week for refusing Hegseth's order to strike four officers, two Black men and two women, from a promotions list.
Navy Secretary John Phelan was ousted in April. The first woman to serve as Chief of Naval Operations is gone. Admiral Lisa Franchetti, gone. Five former Defense Secretaries, including Jim Mattis, signed a joint letter to Congress calling the firings "reckless."
Day-to-day operation of the Pentagon has fallen to Deputy Secretary Steve Feinberg, a billionaire private equity executive with no military background, now responsible for three million employees.
Hegseth's brother Phil was appointed senior adviser at DHS in March 2025. Tim Parlatore, a personal attorney who has represented both Hegseth and Trump, is in the inner circle. Senator Chris Coons told reporters it was "not normal at all" for spouses to attend Pentagon meetings. Hegseth has reportedly told staff he is afraid Trump will fire him.
This is the man overseeing the war with Iran. The same Iran war Hegseth told the Senate this week is "in a ceasefire" that pauses the constitutional 60-day clock.
The same war that has killed 13 American troops, cost $25 billion, and left 11 American military bases damaged. The same war for which the Pentagon has been caught hiding casualty figures and erasing wounded service members from the official rolls.
Unbelievable.$cache$,
    FALSE,
    NOW()
FROM article
WHERE lower(url) IN (
    'https://www.facebook.com/theother98/posts/pfbid0yiddpvt2xxb2cm56g33f91qtrssyw1bpixpnnq7dlkhdcud5oehrl58mjmo3ierxl',
    'https://facebook.com/theother98/posts/pfbid0yiddpvt2xxb2cm56g33f91qtrssyw1bpixpnnq7dlkhdcud5oehrl58mjmo3ierxl'
)
OR lower(url) LIKE 'https://www.facebook.com/theother98/posts/pfbid0yiddpvt2xxb2cm56g33f91qtrssyw1bpixpnnq7dlkhdcud5oehrl58mjmo3ierxl?%'
OR lower(url) LIKE 'https://facebook.com/theother98/posts/pfbid0yiddpvt2xxb2cm56g33f91qtrssyw1bpixpnnq7dlkhdcud5oehrl58mjmo3ierxl?%'
ON CONFLICT (article_id) DO UPDATE SET
    content = EXCLUDED.content,
    truncated = FALSE,
    captured_at = NOW();

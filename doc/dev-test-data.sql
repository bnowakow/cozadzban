-- Dev test data with long titles for UI evaluation.
-- Run against the local DB:
--   psql -h localhost -U cozazjeb -d cozazjeb -f doc/dev-test-data.sql
--
-- Requires at least one app_user row (created by bootstrap admin on first run).

DO $$
DECLARE
    uid BIGINT;
BEGIN
    SELECT id INTO uid FROM app_user ORDER BY created_at ASC, id ASC LIMIT 1;

    INSERT INTO article (url, language, title, created_at, published_at, created_by_user_id) VALUES
    (
        'https://example.com/article/1',
        'en',
        'Why the Modern Software Engineer Must Embrace Uncertainty, Complexity, and the Art of Building Systems That Actually Work at Scale',
        now() - interval '1 day',
        now() - interval '1 day',
        uid
    ),
    (
        'https://example.com/article/2',
        'pl',
        'Jak skutecznie zarządzać projektem informatycznym w środowisku rozproszonym i dlaczego tradycyjne metodyki zawodzą w dobie mikroserwisów',
        now() - interval '2 days',
        now() - interval '2 days',
        uid
    ),
    (
        'https://example.com/article/3',
        'en',
        'The Complete and Exhaustive Guide to Kubernetes Networking, Service Meshes, Ingress Controllers, and Everything That Can Go Wrong',
        now() - interval '3 days',
        now() - interval '3 days',
        uid
    ),
    (
        'https://example.com/article/4',
        'de',
        'Warum maschinelles Lernen ohne solide Datenstrategie scheitert: Ein praxisnaher Überblick über häufige Fehler und wie man sie vermeidet',
        now() - interval '4 days',
        now() - interval '4 days',
        uid
    ),
    (
        'https://example.com/article/5',
        'en',
        'Short title',
        now() - interval '5 days',
        now() - interval '5 days',
        uid
    ),
    (
        'https://example.com/article/6',
        'en',
        'Understanding the Tradeoffs Between Eventual Consistency, Strong Consistency, and the CAP Theorem in Distributed Database Systems',
        now() - interval '6 days',
        NULL,
        uid
    ),
    (
        'https://example.com/article/7',
        'pl',
        'Bezpieczeństwo aplikacji webowych w 2026 roku: przegląd najważniejszych zagrożeń OWASP Top 10 i strategie ich mitygacji w ekosystemie Spring Boot',
        now() - interval '7 days',
        now() - interval '7 days',
        uid
    ),
    (
        'https://example.com/article/8',
        'en',
        'From Monolith to Microservices and Back Again: Lessons Learned After Five Years of Distributed Architecture in a Mid-Size Engineering Organization',
        now() - interval '8 days',
        now() - interval '8 days',
        uid
    )
    ON CONFLICT (url) DO NOTHING;
END $$;

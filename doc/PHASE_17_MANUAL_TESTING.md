# Phase 17 Manual Testing Guide — Publication metadata and language normalization

## Prerequisites

App and Postgres must be running:
```bash
docker compose up -d
./gradlew bootRun --args='--spring.profiles.active=local'
```

You need at least one allowlisted user with ADMIN role.  
Obtain a Google OIDC Bearer JWT and export:

```bash
export TOKEN="eyJ..."   # JWT for an ACTIVE ADMIN or USER
```

---

## Step 1: Verify database migration

Connect to Postgres and confirm the column and index exist:

```bash
docker compose exec postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -c "\d article"
# Expect: published_at | timestamp with time zone | nullable

docker compose exec postgres psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" \
  -c "SELECT indexname FROM pg_indexes WHERE tablename='article' AND indexname='article_published_at_idx';"
# Expect: 1 row returned
```

---

## Step 2: Language normalization

Language tag submitted in uppercase must be lowercased on create:

```bash
curl -s -X POST http://localhost:8080/api/articles \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com/article-en","language":"EN"}' | jq '.language'
# Expect: "en"
```

Mixed-case BCP-47 subtag is also normalized:

```bash
curl -s -X POST http://localhost:8080/api/articles \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com/article-pt","language":"PT-BR"}' | jq '.language'
# Expect: "pt-br"
```

---

## Step 3: Language validation

Invalid language tag must return 400:

```bash
curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8080/api/articles \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com/article-x","language":"english"}'
# Expect: 400

curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8080/api/articles \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com/article-y","language":"e"}'
# Expect: 400
```

---

## Step 4: Manual publishedAt on create (overrides enrichment)

```bash
curl -s -X POST http://localhost:8080/api/articles \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com/manual-date","language":"en","publishedAt":"2025-01-15T10:00:00Z"}' \
  | jq '.publishedAt'
# Expect: "2025-01-15T10:00:00Z"
```

---

## Step 5: Automatic publishedAt enrichment

Create an article from a real news URL that includes Open Graph `article:published_time` metadata (e.g. a BBC, Guardian, or CNN article).  
Do not supply `publishedAt` in the request body:

```bash
curl -s -X POST http://localhost:8080/api/articles \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://www.bbc.com/news/some-real-article","language":"en"}' \
  | jq '.publishedAt'
# Expect: non-null ISO-8601 string parsed from the page metadata
```

---

## Step 6: Patch publishedAt (set and clear)

Set a new publishedAt via PATCH:

```bash
export ARTICLE_ID=1   # replace with a real id from previous steps

curl -s -X PATCH "http://localhost:8080/api/articles/$ARTICLE_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/merge-patch+json" \
  -d '{"publishedAt":"2024-06-01T08:00:00Z"}' | jq '.publishedAt'
# Expect: "2024-06-01T08:00:00Z"
```

Clear publishedAt (set to null) via PATCH:

```bash
curl -s -X PATCH "http://localhost:8080/api/articles/$ARTICLE_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/merge-patch+json" \
  -d '{"publishedAt":null}' | jq '.publishedAt'
# Expect: null
```

---

## Step 7: publishedAt survives PUT (full replace)

```bash
curl -s -X PUT "http://localhost:8080/api/articles/$ARTICLE_ID" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"url":"https://example.com/updated","language":"pl","publishedAt":"2023-03-20T12:00:00Z"}' \
  | jq '{language,publishedAt}'
# Expect: { "language": "pl", "publishedAt": "2023-03-20T12:00:00Z" }
```

---

## Step 8: Sort by publishedAt

```bash
curl -s "http://localhost:8080/api/articles?sort=publishedAt,desc" | jq '[.content[].publishedAt]'
# Expect: dates in descending order (nulls may appear at end depending on DB ordering)
```

---

## Step 9: Language filter

```bash
curl -s "http://localhost:8080/api/articles?language=en" | jq '[.content[].language]'
# Expect: all items are "en"
```

---

## Step 10: Date range filters

```bash
curl -s "http://localhost:8080/api/articles?publishedFrom=2025-01-01T00:00:00Z&publishedTo=2025-12-31T23:59:59Z" \
  | jq '[.content[].publishedAt]'
# Expect: all non-null publishedAt values within 2025

curl -s "http://localhost:8080/api/articles?createdFrom=2025-01-01T00:00:00Z" \
  | jq '[.content[].createdAt]'
# Expect: all createdAt values on or after 2025-01-01
```

---

## Step 11: RSS uses publishedAt when present

Fetch the RSS feed and inspect `<pubDate>` elements:

```bash
curl -s http://localhost:8080/rss | grep -A1 "<pubDate>"
# For articles with publishedAt set: pubDate should reflect publishedAt
# For articles without publishedAt: pubDate should fall back to createdAt
```

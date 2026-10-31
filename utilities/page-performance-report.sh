#!/usr/bin/env bash
# Repeatable front-page performance report. Run on the deployment server from any
# directory: make performance-report (or utilities/page-performance-report.sh).
# Prints the report and saves a copy under logs/performance-reports/, because
# Zipkin keeps traces only in memory and loses them on restart or eviction.
#
# Side effect: requesting each generated image's .jpg creates its website copy
# if it does not exist yet, exactly as the first visitor would.
set -euo pipefail

repo_root=$(git -C "$(dirname "${BASH_SOURCE[0]}")" rev-parse --show-toplevel)
cd "$repo_root"

env_value() { grep -E "^$1=" .env 2>/dev/null | tail -n 1 | cut -d= -f2- || true; }

app_port=${APP_PORT:-$(env_value APP_PORT)}
base_url=${BASE_URL:-http://127.0.0.1:${app_port:-8086}}
public_url=${PUBLIC_URL:-https://cozadzban.pl}
lookback_hours=${LOOKBACK_HOURS:-168}
since=${SINCE:-}                 # ISO-8601 UTC; only count traces at or after this time
until_time=${UNTIL:-}            # ISO-8601 UTC; only count traces before this time
rate=${RATE:-1250k}              # curl --limit-rate; 1250k bytes/s is about 10 Mbit/s
article_count=${ARTICLES:-20}
out_dir=${OUT_DIR:-logs/performance-reports}
asset_dir=${ASSET_DIR:-docker-data/data/facebook-generated}
pg_user=${POSTGRES_USER:-$(env_value POSTGRES_USER)}
pg_db=${POSTGRES_DB:-$(env_value POSTGRES_DB)}
compose=(docker compose -f compose.yaml)

mkdir -p "$out_dir"
report="$out_dir/$(date -u +%Y-%m-%d_%H%M%S)Z.md"
tmp=$(mktemp -d)
trap 'rm -rf "$tmp"' EXIT

psql() { "${compose[@]}" exec -T postgres psql -U "$pg_user" -d "$pg_db" "$@"; }

{
echo "# Page performance report $(date -u +%FT%TZ)"
echo
echo "- Base URL: $base_url; public URL: $public_url"
echo "- App version: $(perl -ne 'print $1 if /^version\s*=\s*"([^"]+)"/' build.gradle.kts), commit $(git rev-parse --short HEAD)"
echo "- Zipkin window: last ${lookback_hours}h${since:+, since $since}${until_time:+, until $until_time}"
echo

echo "## 1. Real requests (Zipkin, sampled)"
echo
zipkin_port=$("${compose[@]}" port zipkin 9411 2>/dev/null | sed 's/.*://' || true)
if [ -z "$zipkin_port" ] || ! curl -sf -o "$tmp/traces.json" \
	"http://127.0.0.1:$zipkin_port/api/v2/traces?serviceName=co-za-dzban&limit=100000&lookback=$((lookback_hours * 3600000))"; then
	echo "Zipkin is not reachable; skipped."
else
	python3 -I - "$tmp/traces.json" "$since" "$until_time" <<'PY'
import datetime, json, sys
path, since, until = sys.argv[1], sys.argv[2], sys.argv[3]
def micros(value):
    if not value:
        return None
    parsed = datetime.datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=datetime.timezone.utc)
    return parsed.timestamp() * 1e6
low, high = micros(since), micros(until)
traces = json.load(open(path))
spans = [s for t in traces for s in t if "parentId" not in s and s.get("name", "").startswith("http ")
         and (low is None or s["timestamp"] >= low) and (high is None or s["timestamp"] < high)]
if not spans:
    print("No request traces in this window.")
    sys.exit()
first = min(s["timestamp"] for s in spans) / 1e6
last = max(s["timestamp"] for s in spans) / 1e6
fmt = lambda ts: datetime.datetime.fromtimestamp(ts, datetime.timezone.utc).strftime("%Y-%m-%d %H:%M")
print(f"{len(spans)} sampled requests from {fmt(first)} to {fmt(last)} UTC. "
      "Durations include the download to the visitor.\n")
groups = {}
for s in spans:
    groups.setdefault(s["name"][len("http "):], []).append(s.get("duration", 0) / 1000)
print("| Endpoint | Requests | Median | 90th percentile | Slowest |")
print("|---|---:|---:|---:|---:|")
def ms(v):
    return f"{v / 1000:.1f} s" if v >= 1000 else f"{v:.0f} ms"
for name, values in sorted(groups.items(), key=lambda kv: -len(kv[1])):
    values.sort()
    p50 = values[len(values) // 2]
    p90 = values[min(len(values) - 1, int(len(values) * 0.9))]
    print(f"| `{name}` | {len(values)} | {ms(p50)} | {ms(p90)} | {ms(values[-1])} |")
PY
fi
echo

echo "## 2. Front-page database queries"
echo
# Best of three runs, so a cold PostgreSQL cache does not distort comparisons.
query_ms() {
	for _ in 1 2 3; do
		psql -Atc "EXPLAIN (ANALYZE, FORMAT JSON) $1" | python3 -I -c 'import json,sys; print(json.load(sys.stdin)[0]["Execution Time"])'
	done | sort -n | head -n 1 | awk '{printf "%.2f ms", $1}'
}
# Same columns and order as ArticleRepositoryCustomImpl.findPage, used by the front page.
feed_columns="id, url, language, title, thumbnail, favicon, lead, quote, ai_summary, publisher_url,
	created_by_user_id, source_import_type, source_import_run_id, source_facebook_proposal_id, created_at, published_at"
echo "| Query | Execution time (best of 3) |"
echo "|---|---:|"
echo "| Articles in table | $(psql -Atc 'SELECT count(*) FROM article') rows |"
echo "| First page (\`LIMIT 50\`) | $(query_ms "SELECT $feed_columns FROM article ORDER BY published_at DESC NULLS LAST, created_at DESC LIMIT 50") |"
echo "| Count | $(query_ms "SELECT count(*) FROM article") |"
echo "| Top languages | $(query_ms "SELECT language, count(*) FROM article GROUP BY language ORDER BY 2 DESC LIMIT 3") |"
echo

echo "## 3. Server response times (from the server itself, best of 3)"
echo
best_of_3() {
	for _ in 1 2 3; do curl -s -o /dev/null -w '%{time_total}\n' "$@"; done | sort -n | head -n 1 |
		awk '{printf "%.0f ms", $1 * 1000}'
}
echo "| Request | Time |"
echo "|---|---:|"
echo "| Front page HTML \`/\` | $(best_of_3 "$base_url/") |"
echo "| Story page \`/stories/<newest>\` | $(best_of_3 "$base_url/stories/$(psql -Atc "SELECT id FROM article ORDER BY published_at DESC NULLS LAST, created_at DESC LIMIT 1")") |"
echo

echo "## 4. Images for the first $article_count front-page articles"
echo
psql -Atc "SELECT a.id, COALESCE(j.status, '') FROM article a
	LEFT JOIN facebook_article_image_generation j ON j.article_id = a.id
	ORDER BY a.published_at DESC NULLS LAST, a.created_at DESC LIMIT $article_count" >"$tmp/first.txt"
generated=$(awk -F'|' '$2 == "GENERATED" {print $1}' "$tmp/first.txt")
generated_count=$(printf '%s\n' "$generated" | grep -c . || true)
png_bytes=0; jpg_bytes=0; png_seconds=0; jpg_seconds=0
for id in $generated; do
	if [ -f "$asset_dir/article-$id.png" ]; then png_bytes=$((png_bytes + $(stat -c %s "$asset_dir/article-$id.png"))); fi
	read -r size seconds < <(curl -s -o /dev/null --limit-rate "$rate" -w '%{size_download} %{time_total}\n' "$base_url/generated-images/$id.jpg")
	jpg_bytes=$((jpg_bytes + size)); jpg_seconds=$(awk -v a="$jpg_seconds" -v b="$seconds" 'BEGIN {print a + b}')
	seconds=$(curl -s -o /dev/null --limit-rate "$rate" -w '%{time_total}\n' "$base_url/generated-images/$id.png")
	png_seconds=$(awk -v a="$png_seconds" -v b="$seconds" 'BEGIN {print a + b}')
done
echo "$generated_count of $article_count articles show a generated image."
echo "The others show a source thumbnail, which this report doesn't measure."
echo
echo "| Image format | Total size | Download at --limit-rate $rate |"
echo "|---|---:|---:|"
awk -v b="$png_bytes" -v s="$png_seconds" 'BEGIN {printf "| PNG original (served to the site before 0.62.33) | %.1f MB | %.1f s |\n", b / 1048576, s}'
awk -v b="$jpg_bytes" -v s="$jpg_seconds" 'BEGIN {printf "| JPEG website copy (current) | %.1f MB | %.1f s |\n", b / 1048576, s}'
echo
echo "JPEG copies on disk: $(find "$asset_dir" -maxdepth 1 -name 'article-*.jpg' 2>/dev/null | wc -l) of $(find "$asset_dir" -maxdepth 1 -name 'article-*.png' 2>/dev/null | wc -l)"
echo

echo "## 5. Caching headers"
echo
header() { curl -s -D - -o /dev/null "${@:2}" | tr -d '\r' | grep -i "^$1:" | cut -d' ' -f2- | head -n 1; }
sample=$(printf '%s\n' "$generated" | head -n 1)
asset=$(curl -s "$base_url/" | grep -oE 'VAADIN/build/[^"]+\.js' | head -n 1 || true)
echo "| Check | Result |"
echo "|---|---|"
if [ -n "$sample" ]; then
	versioned=$(curl -s "$base_url/stories/$sample" | grep -oE "/generated-images/$sample\.jpg\?v=[0-9]+" | head -n 1 || true)
	echo "| Story image URL | \`${versioned:-not found}\` |"
	[ -n "$versioned" ] && echo "| Versioned JPEG Cache-Control | $(header Cache-Control "$base_url$versioned") |"
	echo "| Unversioned PNG Cache-Control | $(header Cache-Control "$base_url/generated-images/$sample.png") |"
	modified=$(header Last-Modified "$base_url/generated-images/$sample.png")
	echo "| PNG revalidation (If-Modified-Since) | HTTP $(curl -s -o /dev/null -w '%{http_code}' -H "If-Modified-Since: $modified" "$base_url/generated-images/$sample.png") |"
fi
if [ -n "$asset" ]; then
	echo "| Vaadin asset Cache-Control (local) | $(header Cache-Control "$base_url/$asset") |"
	echo "| Vaadin asset Cache-Control (public) | $(header cache-control "$public_url/$asset") |"
	echo "| Vaadin asset encoding / size (public) | $(header content-encoding -H 'Accept-Encoding: gzip, br' "$public_url/$asset") / $(curl -s -o /dev/null -H 'Accept-Encoding: gzip, br' -w '%{size_download}' "$public_url/$asset") bytes |"
fi
echo "| Front page encoding / size (public) | $(header content-encoding -H 'Accept-Encoding: gzip, br' "$public_url/") / $(curl -s -o /dev/null -H 'Accept-Encoding: gzip, br' -w '%{size_download}' "$public_url/") bytes |"
} | tee "$report"

echo
echo "Saved to $report"

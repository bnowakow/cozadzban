#!/usr/bin/env python3
"""Print a compact Jenkins image-build estimate for blue-green-upgrade.sh.

Output: state|percent|sample_count|median_seconds|elapsed_seconds|build_number|error
Only successful, non-timer main builds contribute to the last-100 sample.
"""

import base64
import json
import os
from pathlib import Path
import socket
import statistics
import sys
import time
import urllib.parse
import urllib.error
import urllib.request


API_URL = os.environ.get(
    "JENKINS_MAIN_API_URL",
    "https://jenkins.rancher.tailscale.bnowakowski.pl/job/cozadzban.pl/job/main/api/json",
)
HISTORY_FILE = Path(os.environ.get("JENKINS_BUILD_HISTORY_FILE", "logs/jenkins-build-durations.json"))
TREE = "builds[number,result,duration,timestamp,building,displayName,actions[causes[_class]]]{0,150}"


def cached_history():
    try:
        data = json.loads(HISTORY_FILE.read_text())
        return data.get("builds", []) if isinstance(data, dict) else []
    except (OSError, ValueError, TypeError):
        return []


def fetch_builds():
    query = urllib.parse.urlencode({"tree": TREE})
    request = urllib.request.Request(f"{API_URL}?{query}", headers={"Accept": "application/json"})
    token_file = os.environ.get("JENKINS_API_TOKEN_FILE")
    token = os.environ.get("JENKINS_API_TOKEN", "")
    if not token and token_file:
        token = Path(token_file).read_text().strip()
    if token:
        user = os.environ.get("JENKINS_API_USER", "sup")
        credential = base64.b64encode(f"{user}:{token}".encode()).decode()
        request.add_header("Authorization", f"Basic {credential}")
    with urllib.request.urlopen(request, timeout=6) as response:
        return json.load(response).get("builds") or []


def successful_image_build(build):
    if build.get("result") != "SUCCESS" or not isinstance(build.get("duration"), int):
        return False
    causes = [
        cause.get("_class", "")
        for action in build.get("actions", []) if isinstance(action, dict)
        for cause in (action.get("causes") or []) if isinstance(cause, dict)
    ]
    return not any("TimerTriggerCause" in cause or "Restart" in cause for cause in causes)


def merge_history(cached, builds):
    by_number = {
        entry["number"]: entry for entry in cached
        if isinstance(entry, dict) and isinstance(entry.get("number"), int)
    }
    for build in builds:
        if successful_image_build(build):
            number = build.get("number")
            duration = build.get("duration")
            if isinstance(number, int) and duration > 0:
                by_number[number] = {"number": number, "duration_ms": duration}
    return sorted(by_number.values(), key=lambda item: item["number"], reverse=True)[:100]


def save_history(history):
    try:
        HISTORY_FILE.parent.mkdir(parents=True, exist_ok=True)
        temporary = HISTORY_FILE.with_suffix(HISTORY_FILE.suffix + ".tmp")
        temporary.write_text(json.dumps({"builds": history}, separators=(",", ":")) + "\n")
        temporary.replace(HISTORY_FILE)
    except OSError:
        pass  # A read-only checkout still gets a live estimate.


def main(tag):
    history = cached_history()
    error = ""
    try:
        builds = fetch_builds()
        reachable = True
    except urllib.error.HTTPError as exc:
        builds = []
        reachable = False
        error = f"http_{exc.code}"
    except (OSError, ValueError, TypeError) as exc:
        builds = []
        reachable = False
        reason = exc.reason if isinstance(exc, urllib.error.URLError) else exc
        if isinstance(reason, socket.gaierror):
            error = "dns"
        elif isinstance(reason, (TimeoutError, socket.timeout)):
            error = "timeout"
        else:
            error = "connection"
    if reachable:
        updated = merge_history(history, builds)
        if updated != history:
            save_history(updated)
        history = updated
    durations = [
        entry["duration_ms"] / 1000 for entry in history
        if isinstance(entry, dict) and isinstance(entry.get("duration_ms"), int)
    ]
    median = round(statistics.median(durations)) if durations else 0
    current = next(
        (build for build in builds if isinstance(build, dict) and build.get("displayName") == tag),
        None,
    )
    state = "unavailable" if not reachable else "pending"
    percent = elapsed = number = 0
    if current:
        number = current.get("number") or 0
        started_ms = current.get("timestamp") or 0
        elapsed = max(0, round(time.time() - started_ms / 1000)) if started_ms else 0
        result = current.get("result")
        if result == "SUCCESS":
            state, percent = "succeeded", 99
        elif result and not current.get("building"):
            state = "failed"
        else:
            state = "building"
            percent = min(99, round(100 * elapsed / median)) if median else 0
    print(f"{state}|{percent}|{len(history)}|{median}|{elapsed}|{number}|{error}")


if __name__ == "__main__":
    main(sys.argv[1])

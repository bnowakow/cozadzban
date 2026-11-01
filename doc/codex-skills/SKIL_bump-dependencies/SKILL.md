---
name: bump-dependencies
description: Bump Gradle plugins, libraries and the Gradle wrapper to their latest non-breaking (same-major) versions, or fix CVEs Docker Scout reports for a published bnowakow/cozadzban image, then prove it with the full test suite and a Scout rescan of a locally built image.
---

# Bump Dependencies

Use this skill when the user wants dependencies updated ("bump everything without breaking changes"), or wants vulnerabilities fixed for an image such as `bnowakow/cozadzban:<version>` on Docker Hub.

## Workflow

1. Run `hostname`. On `ovh.bnowakowski.pl` do not run Gradle tests or image builds; make the version changes only and report verification as pending.
2. Check `git status --short`. Note changes that are not yours and never stage or commit them.
3. If the request is about CVEs, scan the published image first. Docker Hub hides the CVE list without sign-in, so use Scout:
   ```
   docker scout cves --format only-packages --only-fixed bnowakow/cozadzban:<version> --platform linux/amd64
   docker scout cves --only-fixed --format markdown bnowakow/cozadzban:<version> --platform linux/amd64
   ```
   The first lists vulnerable packages, the second their fixed versions. Filter rows with `grep -vE '0C +0H +0M +0L'`.
4. Find the latest versions from Maven Central metadata, skipping pre-releases:
   ```
   curl -s https://repo1.maven.org/maven2/<group/path>/<artifact>/maven-metadata.xml
   ```
   Gradle itself: `curl -s https://services.gradle.org/versions/current`.
5. Pick versions (see Version Rules), edit `build.gradle.kts`, and update the wrapper with `./gradlew wrapper --gradle-version <v>` followed by `./gradlew wrapper`.
6. Confirm what actually resolves, since Spring Boot's BOM can override direct versions:
   ```
   ./gradlew -q dependencies --configuration runtimeClasspath
   ./gradlew -q dependencyInsight --configuration runtimeClasspath --dependency <name>
   ```
7. Fix compile errors the bump causes in the narrowest way (see Known Fallout).
8. Run the full suite with Docker running, so the `*IT` and `CozadzbanApplicationTests` integration tests are included (see Verifying).
9. Build the image locally and rescan:
   ```
   docker build --platform linux/amd64 -t cozadzban:depbump .
   docker scout cves --format only-packages --only-fixed local://cozadzban:depbump
   ```
   Success is `No vulnerable package detected`.
10. Report the bumps, what was held back and why, code changes, test counts and the scan result. Commit only when the user asks, and only your files.

## Version Rules

- Stay within the current major version. Spring Boot and Vaadin minor bumps are fine; mockito-kotlin 5 → 6 is not.
- Keep the Kotlin plugin at the `kotlin.version` that Spring Boot's BOM manages (`spring-boot-dependencies-<v>.pom`), not newer.
- Spring Boot's BOM manages Selenium and OpenTelemetry at versions older than `selenium-java` needs, and the older ones (plus the Guava they bring) carry CVEs. Override them with `extra["selenium.version"]` and `extra["opentelemetry.version"]`, matching what `selenium-remote-driver-<v>.pom` declares. Drop the overrides once Boot's managed versions catch up.
- Keep `extra["vaadinVersion"]` and the `com.vaadin` plugin version equal. A Vaadin bump regenerates `src/main/bundles/prod.bundle`, `package.json`, `package-lock.json`, `tsconfig.json` and `vite.*.ts`; commit those with the bump.
- Check that springdoc's parent POM targets the same Spring Boot minor.
- For CVE work, prefer bumping the parent (Spring Boot, Selenium, Batik) over pinning a transitive library. Pin a transitive one only when no parent release fixes it.

## Known Fallout

- Kotlin 2.3+ reads Spring's JSpecify annotations strictly. `NamedParameterJdbcTemplate.queryForList(..., String::class.java)` now returns `List<String?>`. Where the SQL already filters `IS NOT NULL`, add `.filterNotNull()`.
- `e: Daemon compilation failed` with a stack trace is the Kotlin daemon falling back to in-process compilation, not a failure by itself. Look for `e: file://...` lines for real compile errors.

## Verifying

- Read results from files, never from a task's summary line. Count them from the XML:
  ```
  cat build/test-results/test/*.xml | grep -oE '<testsuite [^>]*' | awk -F'"' '{for(i=1;i<NF;i++){if($i~/ tests=$/)t+=$(i+1);if($i~/ failures=$/)f+=$(i+1);if($i~/ errors=$/)e+=$(i+1);if($i~/ skipped=$/)s+=$(i+1)}} END{print "tests",t,"failures",f,"errors",e,"skipped",s}'
  ```
- `Docker is not available; excluding Docker-backed integration tests.` in the Gradle output means the integration tests did not run. Say so.
- If another process is running Gradle in the same checkout (`ps -Ao pid,etime,command | grep gradle`), runs wipe each other's `build/` (`NoSuchFileException ... in-progress-results-generic.bin`). Test and build in an isolated copy instead, and leave the other processes alone:
  ```
  git diff HEAD --binary > <scratch>/wip.patch
  git clone -q --no-hardlinks . <scratch>/iso && cd <scratch>/iso && git apply --binary <scratch>/wip.patch
  ```
  `rsync` of the checkout fails on the sockets inside it.
- A `grep` that finds nothing exits 1 and makes a background task look failed. Check the printed output, not just the exit code.
- zsh may have `noclobber` set; write logs with `>|`.

## Project Rules

Follow repository instructions while bumping:

- run `make test` (or `./gradlew test`) on a development machine, never on `ovh.bnowakowski.pl`
- never edit applied Flyway migrations
- all DB access must remain parameterized
- persistence stays Spring Data JDBC, no `ddl-auto`

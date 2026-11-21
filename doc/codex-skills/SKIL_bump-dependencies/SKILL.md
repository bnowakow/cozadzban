---
name: bump-dependencies
description: Upgrade a project’s dependencies to the newest stable versions that preserve backwards compatibility. Use for routine dependency refreshes or compatible updates that address reported CVEs.
---

# Bump Dependencies

1. Read repository instructions and check the working tree. Inventory declared dependencies, managed BOMs, explicit overrides, build plugins and wrappers, frontend packages, lockfiles, and versioned CI or container dependencies. Review transitive versions through their owning dependency or BOM first.
2. Use official registries to discover releases and upstream release notes, migration guides, and compatibility matrices to choose versions. Start from the newest stable release within the installed major and work down until you find a compatible candidate. For `0.x`, stay within the current minor unless upstream documents compatibility. For projects with calendar versions, image tags, or other version schemes, keep the documented compatibility line and review changes between the installed and candidate versions.
3. Check breaking behavior within the allowed range. Look for removed APIs, changed defaults, new runtime or peer requirements, and changes to configuration, data formats, and published interfaces. Hold a dependency at its current version if no compatible candidate is available or compatibility cannot be established. A passing build alone does not clear a documented backward incompatibility.
4. Update related components together where their supported versions overlap. Use the package manager or wrapper to regenerate lockfiles and generated metadata. Inspect the resolved graph and lockfile diff for unexpected transitive changes, including major upgrades that affect existing consumers. Keep the current Java baseline and deployment contracts. Skip unrelated refactors, dependency substitutions, and major upgrades.
5. Run the full project test suite and verify affected deliverables or runtime paths. If an upgrade regresses supported behavior, try the next compatible release or leave the dependency pinned while continuing with independent updates. Report what changed, what was held and why, and what validation ran.

## Co za dzban

- Run `hostname`. On `ovh.bnowakowski.pl`, leave Gradle tests and local image builds for a development machine. On a development machine run `make test` with Docker available. Check the test XML under `build/test-results/test/` and report if Gradle excluded Docker-backed integration tests. If a separate Gradle run is using the checkout, verify in an isolated copy with the intended changes applied.
- Discover wrapper releases through Gradle’s release metadata. When bumping it, run `./gradlew wrapper --gradle-version <version>` and rerun `./gradlew wrapper` with the new distribution. For Spring Boot managed versions, inspect `./gradlew -q dependencies --configuration runtimeClasspath` and use `dependencyInsight` to confirm what actually resolves.
- Keep both Kotlin plugins aligned with the selected Spring Boot BOM’s Kotlin version and both Vaadin version declarations aligned with each other. Check springdoc’s supported Spring Boot minor. Review Vaadin’s regenerated frontend bundle and npm files as part of the same change.
- Check the Selenium and OpenTelemetry version overrides against the candidate Selenium POM and the selected Spring Boot BOM. Remove an override when the BOM resolves a compatible version that meets the same requirement.

## CVE requests

For a reported image vulnerability, confirm the affected component and fixed versions using the report and upstream advisory. If the source is Docker Scout, scan the specified image and platform with `docker scout cves --only-fixed`. Prefer a compatible update to the parent package, BOM, or base image before pinning a transitive package. Build and rescan the updated image locally for the same platform when available. Report remaining findings and any fix that requires a separate breaking upgrade.

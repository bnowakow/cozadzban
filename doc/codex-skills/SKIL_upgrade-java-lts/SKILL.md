---
name: upgrade-java-lts
description: Upgrade a project’s Java toolchain, CI, and runtime to the newest released LTS JDK. Use when asked to move a project to the latest Java LTS.
---

# Upgrade Java LTS

1. Read the repository instructions and inventory the Gradle or Maven toolchain, compiler target, CI runner, build and runtime images, local test scripts, and documented JDK requirements. Check the working tree before editing.
2. Verify the latest generally available LTS feature release and its current patch release using the [Oracle Java support roadmap](https://www.oracle.com/java/technologies/java-se-support-roadmap.html) and the chosen JDK vendor’s release information. Check the exact image tags and architectures used by the project. Prefer the existing vendor and base distribution when supported. Keep the existing pinning scheme.
3. Verify that the build tool supports running on the target JDK and using it as a toolchain. Check the language compiler, framework, test instrumentation, and build plugins against their compatibility documentation. Include only dependency or wrapper changes required for the Java upgrade. If a required change would break a supported contract or requires a separate migration, identify the blocker and confirm that scope before proceeding.
4. Align the build and runtime declarations in one change. Preserve any documented bytecode or consumer-runtime compatibility target unless the requested upgrade explicitly raises it. Preserve JVM options and container behavior, apart from options that the target JDK requires replacing. Update active setup and CI documentation alongside configuration.
5. Verify the actual Java versions used by the build and tests. Run the repository’s full test suite, build the production artifact, and smoke-test the runtime image on a development machine when the project ships one. Report the resulting Java and bytecode targets, prerequisite changes, verification results, and any checks still pending.

## Co za dzban

- Check `build.gradle.kts`, both stages of `Dockerfile`, `Jenkinsfile`, `utilities/docker-test.sh`, `utilities/ci-test.sh`, and the active setup and CI docs. Check the Gradle daemon’s JDK as well as the project toolchain. Keep the container’s Warsaw timezone and the JVM’s UTC default.
- Run `hostname` before testing. Run `make test` with Docker available for PostgreSQL integration tests. Never run Gradle tests on `ovh.bnowakowski.pl`. Use a development machine for test and runtime verification.

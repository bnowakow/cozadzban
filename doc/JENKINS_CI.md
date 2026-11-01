# Jenkins CI for cozadzban

Status as of 2026-11-01. Jenkins runs the test suite on every branch. For new
commits on `main` that pass the tests, it also builds the Docker image and
pushes it to Docker Hub. Deployment is **not** automated yet; that is the
planned next step. Until then, deploy a pushed image on the server with
`make docker-upgrade-dockerhub-image` (see "Deploying the image" below).

The pipeline is defined in [`Jenkinsfile`](../Jenkinsfile) at the repository
root. Jenkins itself runs in the home k3s cluster (Rancher, Fleet), configured
in the `bash_configs` repository.

## Where things are

| What | Where |
| --- | --- |
| Jenkins UI | https://jenkins.rancher.tailscale.bnowakowski.pl/ (also `jenkins.rancher.localdomain.bnowakowski.pl`; private network only) |
| This job | https://jenkins.rancher.tailscale.bnowakowski.pl/job/cozadzban.pl/ (Multibranch Pipeline) |
| Blue Ocean view | https://jenkins.rancher.tailscale.bnowakowski.pl/blue/organizations/jenkins/cozadzban.pl/branches/ |
| Docker image | https://hub.docker.com/repository/docker/bnowakow/cozadzban/general (public) |
| Pipeline definition | `Jenkinsfile` in this repository |
| Jenkins deployment (Fleet) | `bash_configs/rancher/fleet/apps/jenkins/` (TrueCharts chart `jenkins` 12.0.4) and `bash_configs/rancher/fleet/infrastructure/jenkins-storage/` (10Gi Longhorn PVC) |
| Kubernetes namespace | `apps-jenkins` (Jenkins controller and all build pods) |
| Jenkins user | `sup` (the only user; Jenkins' own user database, signup disabled) |

## What the pipeline does

The Multibranch job scans `https://github.com/bnowakow/cozadzban.git` (public,
cloned anonymously over HTTPS) and creates one sub-job per branch that
contains a `Jenkinsfile`. Branches without a `Jenkinsfile` are skipped until
they get one, e.g. by merging `main`.

| Stage | Runs on | What it does |
| --- | --- | --- |
| `Docker` | every build | Waits for the Docker-in-Docker daemon in the build pod |
| `Test` | every build | `./gradlew --no-daemon test -PappBuildCommit=<commit>` and publishes JUnit results |
| `Docker image` | `main` only, not timer-triggered builds | Builds the repository `Dockerfile` and pushes three tags to `bnowakow/cozadzban` |

If `Test` fails, the image stage does not run.

### Triggers

- **Push:** there are no GitHub webhooks. Jenkins is only reachable on private
  addresses (10.0.0.x and Tailscale 100.x), so GitHub cannot call it. Instead,
  the Multibranch job runs a **branch scan every 5 minutes** (`H/5`) and
  builds branches whose head commit changed. A push is usually picked up
  within 1.5 to 5 minutes.
- **Daily:** `triggers { cron(env.BRANCH_NAME == 'main' ? 'H 3 * * *' : '') }`
  in the `Jenkinsfile` rebuilds `main` once a day around 03:xx. These timer
  builds only run the tests; they do not push images.
- **Deleted branches:** their sub-jobs are removed on the next scan, and any
  running build of them is aborted.

Options: no concurrent builds per branch, 90-minute timeout, last 30 builds
kept per branch.

### Docker image tags

The version is read from `version = "…"` in `build.gradle.kts`, with
`-SNAPSHOT` stripped. That matches the version the app displays about itself.
Each push from `main` produces:

| Tag | Example | Behaviour |
| --- | --- | --- |
| `<version>-<commit8>` | `0.64.15-6ffd550d` | Fixed, one per commit. Use this to deploy and to roll back. |
| `<version>` | `0.64.15` | Moves to the newest `main` build of that version. |
| `latest` | `latest` | Moves to the newest `main` build. |

The image is built for `linux/amd64` only and is about 559 MB.
`APP_BUILD_COMMIT` is passed as a build argument. The OCI labels
`org.opencontainers.image.version`, `.revision` (full SHA) and `.source` are
set.

## Deploying the image

On the server, `make docker-upgrade-dockerhub-image` runs `git pull --ff-only`,
computes the tag for the pulled commit the same way the pipeline does
(`<version without -SNAPSHOT>-<commit8>`), pulls it, tags it as
`cozadzban:local` and runs the usual blue/green switch
(`docker-data/blue-green-upgrade.sh` with `IMAGE_SOURCE=dockerhub`). The image
is public, so the server needs no Docker Hub login.

- If the tag isn't on Docker Hub yet (Jenkins needs about 10 minutes after a
  push), it asks: wait (poll every 30 s, up to 20 minutes) or build locally.
  Anything else, or no terminal, aborts without changing the running app.
- `IMAGE_TAG=<version>-<commit8>` deploys a specific build, e.g. to roll back.
  A missing explicit tag fails instead of asking.
- If the active service already runs that image, it stops without changing
  anything. `FORCE_UPGRADE=true` redeploys anyway, e.g. to apply `.env` or
  `compose.yaml` changes.
- Only branch heads are built. If several commits are pushed together, only
  the last one has an image, which is the one `git pull` lands on.
- `make docker-upgrade-local` (or `make docker-upgrade`) still builds on the
  server.

## The build pod

Every build runs in a throwaway pod in `apps-jenkins`, started by the
Jenkins Kubernetes plugin from the pod YAML in the `Jenkinsfile`. Nothing is
installed on the Jenkins controller itself.

| Container | Image | Purpose | Limits |
| --- | --- | --- | --- |
| `jnlp` | added by the plugin | Jenkins agent connection | plugin default |
| `jdk` | `eclipse-temurin:21-jdk-jammy` | Runs Gradle (same JDK as the Dockerfile build stage). Default container. | 4 CPU / 6Gi |
| `dind` | `docker:27.5.1-dind`, **privileged** | Docker daemon for Testcontainers (Postgres, Zipkin) and for the image build | 4 CPU / 6Gi |
| `docker` | `docker:27.5.1-cli` | Docker CLI with buildx (BuildKit) for `docker build` and `push` | 1 CPU / 512Mi |
| init `docker-cli` | `docker:27.5.1-cli` | Copies the `docker` binary to `/opt/docker-cli` for the `jdk` container | — |

Notes:

- The Docker daemon listens on **`127.0.0.1:2375` only**. Containers in a pod
  share the network namespace, so `jdk` and `docker` reach it via
  `DOCKER_HOST=tcp://127.0.0.1:2375`, and other pods cannot.
- `build.gradle.kts` runs `docker info` and **excludes `*IT` tests and
  `CozadzbanApplicationTests` when it fails**. That is why the `jdk`
  container gets the copied `docker` CLI (on `PATH` via
  `export PATH="/opt/docker-cli:$PATH"`). Check that the console shows
  `Docker 27.5.1 ready` and that the test counts include IT tests.
- Testcontainers settings: `TESTCONTAINERS_HOST_OVERRIDE=127.0.0.1` and
  `TESTCONTAINERS_RYUK_DISABLED=true`. Ryuk isn't needed because the whole
  pod is deleted after the build.
- The Kubernetes cloud allows at most **2 build pods at once**. The cluster
  has a history of I/O pressure.
- **No caching between builds.** Every build downloads Gradle, its
  dependencies, npm packages, and the `postgres`/`zipkin`/base images again.
  Typical durations: tests about 5 minutes; tests plus image about 10 minutes.

## Test results so far

| Build | Commit | Result |
| --- | --- | --- |
| `main` #1 | `8107f6c` | FAILURE: 439 passed, 156 failed. All failures had one cause: an empty Google OAuth2 `client-id` broke Spring context startup (`IllegalStateException at OAuth2ClientProperties.java:71`). |
| `main` #2 | `18a22a4` | SUCCESS: 595 passed, 0 failed, 6 skipped, 5m10s. Fixed by dummy test credentials in `build.gradle.kts`. |
| `main` #3 | `6ffd550` | SUCCESS: tests passed; image built and pushed as `0.64.15-6ffd550d`, `0.64.15` and `latest` (digest `sha256:4b7d0b5d…`). |

The OAuth fix: `tasks.withType<Test>` sets the system properties
`spring.security.oauth2.client.registration.google.client-id=test-client-id`
and `…client-secret=test-client-secret`. Tests never contact Google.
Locally, the suite used to pass only because the developer's environment set
`SPRING_SECURITY_OAUTH2_CLIENT_REGISTRATION_GOOGLE_CLIENT_ID`.

## Credentials

Never print, log or commit secret values.

| Purpose | Where | Notes |
| --- | --- | --- |
| Docker Hub push | Jenkins credential ID **`dockerhub-bnowakow`** (Username with password, global) | Username `bnowakow`; the password is a Docker Hub personal access token (Read & Write). Used only in the `Docker image` stage via `withCredentials`; `docker login --password-stdin`, then `docker logout`. |
| Jenkins API (for agents and scripts) | `bash_configs/rancher/tmp-jenkins-token-sup` (git-ignored; one line, 34 characters) | API token of Jenkins user `sup`. Use it as `curl -u "sup:$(cat …)"`. |
| GitHub | none | The repository is public; the scan uses anonymous HTTPS (plain Git source, not the GitHub API, so there are no rate limits). |

**Open item: rotate the Jenkins API token.** The current token, named
`claude-cli`, was exposed in tool output on 2026-11-01 (see Gotchas). Generate
a new one under the user `sup` → Security → API Token, update the file, then
revoke `claude-cli`.

## How Jenkins was set up

All of this is live. The cluster parts are in Fleet (`bash_configs`); the
Jenkins parts were done through the Jenkins API.

1. **Fleet bundles** (`bash_configs`, applied with `fleet apply` before
   commit):
   - `infrastructure/jenkins-storage`: PVC `jenkins-data-longhorn-rwo`, 10Gi,
     `longhorn-rwo-2replicas`, NAS-backup labels, Helm `keep` policy.
   - `apps/jenkins`: chart values with one replica and the Recreate
     strategy, ingress on both hosts (cert-manager `letsencrypt`, Traefik
     `websecure`), a ServiceAccount `jenkins` with a **namespaced** Role
     (pods and pods/exec: create, delete, get, list, patch, update, watch;
     pods/log and events: read), and the service-account token mounted into
     the pod. Jenkins can create pods only in `apps-jenkins`.
2. **Plugins:** the suggested plugins from the setup wizard (Git, Pipeline,
   Multibranch, GitHub branch source, JUnit, Pipeline Graph View, …), plus
   **`kubernetes`** (4560.vc25b_83890c34) and **`blueocean`** (1.27.26, 29
   plugins with dependencies). Blue Ocean is in maintenance mode upstream;
   Pipeline Graph View ("Stages" on a build page) is its maintained successor.
3. **Kubernetes cloud** `kubernetes` (Manage Jenkins → Clouds):
   in-cluster API, namespace `apps-jenkins`, Jenkins URL
   `http://jenkins.apps-jenkins.svc:10280`, agent tunnel
   `jenkins-agent.apps-jenkins.svc:50000`, container cap 2, retention 5 min.
   Configured through the script console (`/scriptText`).
4. **Job** `cozadzban.pl`: Multibranch Pipeline, Git source
   `https://github.com/bnowakow/cozadzban.git` (branch discovery only),
   script path `Jenkinsfile`, periodic scan `H/5 * * * *`, prune dead
   branches and abort their builds. It replaced an empty classic Pipeline job
   of the same name, so the URL stayed the same.

## Working with it from the command line

```sh
T="$(tr -d '\n\r ' < ~/code/bash_configs/rancher/tmp-jenkins-token-sup)"
J=https://jenkins.rancher.tailscale.bnowakowski.pl
B="$J/job/cozadzban.pl/job/main"

curl -sS -u "sup:$T" "$B/lastBuild/api/json" | jq -c '{number, building, result}'
curl -sS -u "sup:$T" "$B/lastBuild/consoleText" | tail -40
curl -sS -u "sup:$T" "$B/lastBuild/testReport/api/json" | jq -c '{passCount, failCount, skipCount}'
curl -sS -u "sup:$T" "$J/job/cozadzban.pl/indexing/consoleText" | tail -20      # last branch scan
curl -sS -u "sup:$T" -X POST "$J/job/cozadzban.pl/build"                        # scan now
curl -sS -u "sup:$T" -X POST "$B/build"                                         # build main now
curl -sS -u "sup:$T" -X POST -F "jenkinsfile=<Jenkinsfile" "$J/pipeline-model-converter/validate"  # lint
kubectl -n apps-jenkins get pods                                                # build pods: cozadzban-pl-<branch>-<n>-…
unset T
```

Check what is public on Docker Hub (anonymous):

```sh
curl -sS https://hub.docker.com/v2/repositories/bnowakow/cozadzban/ | jq -c '{is_private, last_updated}'
curl -sS "https://hub.docker.com/v2/repositories/bnowakow/cozadzban/tags/?page_size=10" | jq -r '.results[].name'
```

## Gotchas (learned the hard way)

- **Never print `%{redirect_url}` or `-v` output from curl calls with
  credentials.** curl puts `user:token@` into redirect URLs. That is how the
  `claude-cli` token leaked.
- **Use `curl -g`** for Jenkins API URLs with `tree=jobs[name]`-style
  brackets; otherwise curl reports "bad range in URL".
- The `/wfapi/` (Pipeline Stage View) API is **not installed**. Use
  `/api/json`, `/consoleText` and `/testReport/api/json`, or Blue Ocean's
  `/blue/rest/…`.
- **`docker:dind` arguments:** if the container args start with `--`, the
  image's entrypoint prepends its default `--host=tcp://0.0.0.0:2375`, which
  exposes the daemon to the pod network and crashes on a duplicate listener.
  Always pass the full command (`["dockerd", "--host=tcp://127.0.0.1:2375",
  …, "--tls=false"]`). `--tls=false` also avoids Docker 27's deliberate
  15-second startup delay for TCP without TLS.
- **A safe restart (`/safeRestart`) does not wait for Pipeline builds.**
  Pipelines can resume after a restart, so Jenkins restarts while they run.
  Build #2 resumed and passed, but wait for running builds to finish before
  restarting Jenkins (e.g. for plugin installs).
- **Credential IDs must match exactly.** The first Docker Hub credential was
  saved as `dockerhub-bnowakow.` (trailing dot) and had to be recreated.
- **New API tokens** are 34 characters starting with `11`, and must be
  **saved** on the user's Security page, or Jenkins discards them.
- **Do not run the test suite on the deployment server `ovh.bnowakowski.pl`**
  (see `AGENTS.md`); it is too weak. Jenkins runs in the home cluster.
- **Cluster rules from `bash_configs`:** change Jenkins only through Fleet
  (`rancher/fleet/apps/jenkins`), apply bundles with `fleet apply` so they can
  be tested before commit, never `helm install` directly, and follow
  `rancher/AGENTS.md` for any PVC migration.

## Next steps / ideas

1. **Deploy step** (planned): after the push, deploy
   `bnowakow/cozadzban:<version>-<commit>` to the server. The server side
   exists (`make docker-upgrade-dockerhub-image`, see "Deploying the image");
   Jenkins still needs a way to run it (e.g. SSH), a credential for it, and a
   decision on whether it is automatic or manual (`input` step).
2. **Rotate the Jenkins API token** (see Credentials).
3. **Build cache:** a Gradle/npm cache PVC, or a registry cache
   (`--cache-to`/`--cache-from` with buildx), to shorten builds.
4. **arm64 image**, only if a deployment target needs it (`docker buildx
   build --platform linux/amd64,linux/arm64`).
5. **GitHub commit status** (green checks on commits and PRs): would need a
   GitHub token credential and the GitHub branch source instead of plain Git.

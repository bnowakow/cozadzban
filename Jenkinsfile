// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl
//
// CI for every branch (Jenkins Multibranch Pipeline "cozadzban.pl").
// Builds run in a throwaway Kubernetes pod in the apps-jenkins namespace:
//   jdk    - eclipse-temurin:21-jdk-jammy, same JDK as the Dockerfile build stage
//   dind   - Docker-in-Docker, so Testcontainers can start Postgres and Zipkin
//            for the *IT tests and CozadzbanApplicationTests, and so the
//            Dockerfile can be built
//   docker - docker:cli with buildx (BuildKit), builds and pushes the image
// The Docker daemon listens on 127.0.0.1 only, so it is not reachable from
// other pods. The pod, its Docker images and the Gradle cache are discarded
// after each build.
//
// After the tests pass, new commits on main are built from the Dockerfile and
// pushed to the public Docker Hub repository bnowakow/cozadzban as
// <version>, <version>-<commit> and latest. <version> comes from
// build.gradle.kts without -SNAPSHOT. The daily timer build only runs the tests.
// The Docker Hub access token is stored in Jenkins as the credential
// "dockerhub-bnowakow" (username with password).

pipeline {
  agent {
    kubernetes {
      defaultContainer 'jdk'
      yaml '''
apiVersion: v1
kind: Pod
spec:
  initContainers:
  # build.gradle.kts runs `docker info` to decide whether to include the
  # Docker-backed tests, so the JDK container needs a docker CLI.
  - name: docker-cli
    image: docker:27.5.1-cli
    command: ["sh", "-c", "cp /usr/local/bin/docker /opt/docker-cli/docker"]
    volumeMounts:
    - name: docker-cli
      mountPath: /opt/docker-cli
  containers:
  - name: jdk
    image: eclipse-temurin:21-jdk-jammy
    command: ["sleep"]
    args: ["infinity"]
    env:
    - name: DOCKER_HOST
      value: tcp://127.0.0.1:2375
    - name: TESTCONTAINERS_HOST_OVERRIDE
      value: 127.0.0.1
    # The whole pod is deleted after the build; Ryuk cleanup is unnecessary.
    - name: TESTCONTAINERS_RYUK_DISABLED
      value: "true"
    - name: TZ
      value: Europe/Warsaw
    resources:
      requests:
        cpu: "1"
        memory: 3Gi
      limits:
        cpu: "4"
        memory: 6Gi
    volumeMounts:
    - name: docker-cli
      mountPath: /opt/docker-cli
  - name: dind
    image: docker:27.5.1-dind
    securityContext:
      privileged: true
    env:
    - name: DOCKER_TLS_CERTDIR
      value: ""
    # Pass the full dockerd command: if the args start with "--", the image's
    # entrypoint prepends its default --host=tcp://0.0.0.0:2375.
    # --tls=false states the intent to run without TLS on loopback.
    args: ["dockerd", "--host=tcp://127.0.0.1:2375", "--host=unix:///var/run/docker.sock", "--tls=false"]
    # The image build (Gradle bootJar, Vaadin frontend, npm) runs inside this
    # daemon, so it needs more memory than Testcontainers alone.
    resources:
      requests:
        cpu: 250m
        memory: 512Mi
      limits:
        cpu: "4"
        memory: 6Gi
    volumeMounts:
    - name: docker-storage
      mountPath: /var/lib/docker
  - name: docker
    image: docker:27.5.1-cli
    command: ["sleep"]
    args: ["infinity"]
    env:
    - name: DOCKER_HOST
      value: tcp://127.0.0.1:2375
    resources:
      requests:
        cpu: 50m
        memory: 64Mi
      limits:
        cpu: "1"
        memory: 512Mi
  volumes:
  - name: docker-cli
    emptyDir: {}
  - name: docker-storage
    emptyDir: {}
'''
    }
  }

  // Push builds come from the Multibranch scan (every 5 minutes).
  // Only main also gets a daily run of the full suite.
  triggers {
    cron(env.BRANCH_NAME == 'main' ? 'H 3 * * *' : '')
  }

  options {
    buildDiscarder(logRotator(numToKeepStr: '30'))
    disableConcurrentBuilds()
    timeout(time: 90, unit: 'MINUTES')
  }

  stages {
    stage('Docker') {
      steps {
        sh '''
          export PATH="/opt/docker-cli:$PATH"
          for i in $(seq 1 60); do
            docker info >/dev/null 2>&1 && break
            sleep 2
          done
          docker info --format 'Docker {{.ServerVersion}} ready'
        '''
      }
    }

    stage('Test') {
      steps {
        sh '''
          export PATH="/opt/docker-cli:$PATH"
          ./gradlew --no-daemon test -PappBuildCommit="${GIT_COMMIT:-unknown}"
        '''
      }
    }

    stage('Docker image') {
      when {
        allOf {
          branch 'main'
          not { triggeredBy 'TimerTrigger' }
        }
      }
      environment {
        IMAGE = 'bnowakow/cozadzban'
      }
      steps {
        container('docker') {
          withCredentials([usernamePassword(credentialsId: 'dockerhub-bnowakow',
              usernameVariable: 'DOCKERHUB_USER', passwordVariable: 'DOCKERHUB_TOKEN')]) {
            sh '''
              set -eu
              version="$(sed -n 's/^version = "\\(.*\\)"$/\\1/p' build.gradle.kts)"
              version="${version%-SNAPSHOT}"
              commit="$(printf '%.8s' "$GIT_COMMIT")"
              [ -n "$version" ] || { echo "No version found in build.gradle.kts" >&2; exit 1; }
              echo "Building $IMAGE: $version, $version-$commit, latest"

              printf '%s' "$DOCKERHUB_TOKEN" | docker login --username "$DOCKERHUB_USER" --password-stdin
              docker build \
                --build-arg APP_BUILD_COMMIT="$commit" \
                --label org.opencontainers.image.version="$version" \
                --label org.opencontainers.image.revision="$GIT_COMMIT" \
                --label org.opencontainers.image.source=https://github.com/bnowakow/cozadzban \
                --tag "$IMAGE:$version" \
                --tag "$IMAGE:$version-$commit" \
                --tag "$IMAGE:latest" \
                .
              docker push "$IMAGE:$version-$commit"
              docker push "$IMAGE:$version"
              docker push "$IMAGE:latest"
              docker logout
            '''
          }
        }
      }
    }
  }

  post {
    always {
      junit allowEmptyResults: true, testResults: 'build/test-results/test/*.xml'
    }
  }
}

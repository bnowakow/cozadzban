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
    // The main steps are make targets, so they can be run and debugged locally:
    // `make ci-test` here, or `make docker-test` for the whole pod setup.
    // Neither image ships make, so each stage installs it first.

    // Name the build like the Docker image tag, <version>-<commit8>
    // (e.g. 0.64.16-6ffd550d), so a build and its image are easy to match.
    // The build number moves to the description and stays in the URL.
    stage('Version') {
      steps {
        script {
          currentBuild.displayName = sh(returnStdout: true,
              script: 'utilities/docker-image.sh tag').trim()
          currentBuild.description = "#${env.BUILD_NUMBER}"
        }
      }
    }

    stage('Test') {
      steps {
        sh '''
          apt-get update -qq
          DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-install-recommends make >/dev/null
          APP_BUILD_COMMIT="${GIT_COMMIT:-unknown}" make ci-test
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
            // Tags and labels: utilities/docker-image.sh. Login stays here,
            // because it needs the Jenkins credential.
            sh '''
              set -eu
              apk add --no-cache --quiet make
              make docker-image-build
              printf '%s' "$DOCKERHUB_TOKEN" | docker login --username "$DOCKERHUB_USER" --password-stdin
              make docker-image-push
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

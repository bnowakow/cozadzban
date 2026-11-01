// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 https://bnowakowski.pl
//
// CI for every branch (Jenkins Multibranch Pipeline "cozadzban.pl").
// Builds run in a throwaway Kubernetes pod in the apps-jenkins namespace:
//   jdk    - eclipse-temurin:21-jdk-jammy, same JDK as the Dockerfile build stage
//   dind   - Docker-in-Docker, so Testcontainers can start Postgres and Zipkin
//            for the *IT tests and CozadzbanApplicationTests
// The Docker daemon listens on 127.0.0.1 only, so it is not reachable from
// other pods. The pod, its Docker images and the Gradle cache are discarded
// after each build.

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
    args: ["--host=tcp://127.0.0.1:2375", "--host=unix:///var/run/docker.sock"]
    resources:
      requests:
        cpu: 250m
        memory: 512Mi
      limits:
        cpu: "2"
        memory: 3Gi
    volumeMounts:
    - name: docker-storage
      mountPath: /var/lib/docker
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
  }

  post {
    always {
      junit allowEmptyResults: true, testResults: 'build/test-results/test/*.xml'
    }
  }
}

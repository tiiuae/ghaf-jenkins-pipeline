#!/usr/bin/env groovy

// SPDX-FileCopyrightText: 2022-2024 TII (SSRC) and the Ghaf contributors
// SPDX-License-Identifier: Apache-2.0

////////////////////////////////////////////////////////////////////////////////

def REPO_URL = 'https://github.com/tiiuae/ghaf/'
def WORKDIR  = 'ghaf'

// Utils module will be loaded in the first pipeline stage
def utils = null

properties([
  githubProjectProperty(displayName: '', projectUrlStr: REPO_URL),
])

////////////////////////////////////////////////////////////////////////////////

pipeline {
  agent { label 'built-in' }
  triggers {
     pollSCM('* * * * *')
  }
  options {
    timestamps ()
    buildDiscarder(logRotator(numToKeepStr: '100'))
  }
  stages {
    stage('Checkout') {
      steps {
        script { utils = load "utils.groovy" }
        dir(WORKDIR) {
          checkout scmGit(
            branches: [[name: 'main']],
            extensions: [cleanBeforeCheckout()],
            userRemoteConfigs: [[url: REPO_URL]]
          )
          script {
            env.TARGET_COMMIT = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
            env.ARTIFACTS_RELPATH = "${env.JOB_NAME}/build_${env.BUILD_ID}-commit_${env.TARGET_COMMIT}"
          }
        }
      }
    }
    stage('Build x86_64') {
      steps {
        dir(WORKDIR) {
          script {
            utils.nix_build('.#packages.x86_64-linux.nvidia-jetson-orin-agx-debug-from-x86_64', 'out')
            utils.nix_build('.#packages.x86_64-linux.nvidia-jetson-orin-nx-debug-from-x86_64', 'out')
            utils.nix_build('.#packages.x86_64-linux.lenovo-x1-carbon-gen11-debug', 'out')
            utils.nix_build('.#packages.riscv64-linux.microchip-icicle-kit-debug', 'out')
            utils.nix_build('.#packages.x86_64-linux.doc')
          }
        }
      }
    }
    stage('Build aarch64') {
      steps {
        dir(WORKDIR) {
          script {
            utils.nix_build('.#packages.aarch64-linux.nvidia-jetson-orin-agx-debug', 'out')
            utils.nix_build('.#packages.aarch64-linux.nvidia-jetson-orin-nx-debug', 'out')
            utils.nix_build('.#packages.aarch64-linux.doc')
          }
        }
      }
    }
  }
  post {
    always {
      // Archive nix build results from the builds that produced out-links
      sh """
        export RCLONE_WEBDAV_UNIX_SOCKET_PATH=/run/rclone-jenkins-artifacts.sock
        export RCLONE_WEBDAV_URL=http://localhost
        rclone sync -L ${WORKDIR}/build/ :webdav:/${env.ARTIFACTS_RELPATH}/
      """
      script {
        currentBuild.description = "<a href=\"/artifacts/${env.ARTIFACTS_RELPATH}/\">📦 Artifacts</a>"
      }
    }
  }
}

////////////////////////////////////////////////////////////////////////////////

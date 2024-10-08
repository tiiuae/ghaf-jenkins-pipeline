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
    disableConcurrentBuilds()
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
            env.ARTIFACTS_REMOTE_PATH = "${env.JOB_NAME}/build_${env.BUILD_ID}-commit_${env.TARGET_COMMIT}"
          }
        }
      }
    }
    stage('Build x86_64') {
      steps {
        dir(WORKDIR) {
          script {
            utils.nix_build('.#packages.x86_64-linux.nvidia-jetson-orin-agx-debug-from-x86_64', 'archive')
            utils.nix_build('.#packages.x86_64-linux.nvidia-jetson-orin-nx-debug-from-x86_64', 'archive')
            utils.nix_build('.#packages.x86_64-linux.lenovo-x1-carbon-gen11-debug', 'archive')
            utils.nix_build('.#packages.x86_64-linux.microchip-icicle-kit-debug-from-x86_64', 'archive')
            utils.nix_build('.#packages.x86_64-linux.generic-x86_64-debug', 'archive')
            utils.nix_build('.#packages.x86_64-linux.doc')
          }
        }
      }
    }
    stage('Build aarch64') {
      steps {
        dir(WORKDIR) {
          script {
            utils.nix_build('.#packages.aarch64-linux.nvidia-jetson-orin-agx-debug', 'archive')
            utils.nix_build('.#packages.aarch64-linux.nvidia-jetson-orin-nx-debug', 'archive')
            utils.nix_build('.#packages.aarch64-linux.doc')
          }
        }
      }
    }
    stage('HW test') {
      steps {
        dir(WORKDIR) {
          script {
            utils.ghaf_hw_test('.#packages.x86_64-linux.nvidia-jetson-orin-agx-debug-from-x86_64', 'orin-agx')
            utils.ghaf_hw_test('.#packages.aarch64-linux.nvidia-jetson-orin-agx-debug', 'orin-agx')
            utils.ghaf_hw_test('.#packages.x86_64-linux.nvidia-jetson-orin-nx-debug-from-x86_64', 'orin-nx')
            utils.ghaf_hw_test('.#packages.aarch64-linux.nvidia-jetson-orin-nx-debug', 'orin-nx')
            utils.ghaf_hw_test('.#packages.x86_64-linux.lenovo-x1-carbon-gen11-debug', 'lenovo-x1')
            utils.ghaf_hw_test('.#packages.x86_64-linux.generic-x86_64-debug', 'nuc')
            utils.ghaf_hw_test('.#packages.x86_64-linux.microchip-icicle-kit-debug-from-x86_64', 'riscv')
          }
        }
      }
    }
  }

  post {
    failure {
      script {
        githublink="https://github.com/tiiuae/ghaf/commit/${env.TARGET_COMMIT}"
        servername = sh(script: 'uname -n', returnStdout: true).trim()
        echo "Server name:$servername"
        if (servername=="ghaf-jenkins-controller-dev") {
          serverchannel="ghaf-jenkins-builds-failed"
          echo "Slack channel:$serverchannel"
          message= "FAIL build: ${servername} ${env.JOB_NAME} [${env.BUILD_NUMBER}] (<${githublink}|The commits>)  (<${env.BUILD_URL}|The Build>)"
          slackSend (
            channel: "$serverchannel",
            color: '#36a64f', // green
            message: message
          )
        }
        else {
          echo "Slack message not sent (failed build). Check pipeline slack configuration!"
        }
      }
    }
  }
}

////////////////////////////////////////////////////////////////////////////////

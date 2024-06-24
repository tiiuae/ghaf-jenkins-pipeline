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
            env.ARTIFACTS_REMOTE_PATH = "${env.JOB_NAME}/build_${env.BUILD_ID}-commit_${env.TARGET_COMMIT}"
            env.GITHUBLINK="https://github.com/tiiuae/ghaf/commit/${env.TARGET_COMMIT}"
            env.SERVER_NAME = sh(script: 'echo ${JENKINS_URL}', returnStdout: true).trim()
                    if (env.SERVER_NAME == "") {
                        env.SERVER_NAME = sh(script: 'uname -n', returnStdout: true).trim()
                    }
            def slacking=utils.determine_environment(env.SERVER_NAME)
            env.SERVER_ENVIRONMENT=slacking.env
            env.SERVER_SLACK_CHANNEL=slacking.channel
            if (env.SERVER_SLACK_CHANNEL == "FALSE") {
              env.SLACK_FLAG = "FALSE"
            }
            else {
              env.SLACK_FLAG = "TRUE"
            }
          }
        }
        echo "Server name:${env.SERVER_NAME}"
        echo "Server environment:${env.SERVER_ENVIRONMENT}"
        echo "Slack channel:${env.SERVER_SLACK_CHANNEL}"
        echo "Slacking status:${env.SLACK_FLAG}"
      }
    }
    stage('Build x86_64') {
      steps {
        dir(WORKDIR) {
          script {
            utils.nix_build('.#packages.x86_64-linux.nvidia-jetson-orin-agx-debug-from-x86_64', 'archive')
            utils.nix_build('.#packages.x86_64-linux.nvidia-jetson-orin-nx-debug-from-x86_64', 'archive')
            utils.nix_build('.#packages.x86_64-linux.lenovo-x1-carbon-gen11-debug', 'archive')
            utils.nix_build('.#packages.riscv64-linux.microchip-icicle-kit-debug', 'archive')
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
  }

post {
    failure {
      script {
        if (env.SLACK_FLAG== 'TRUE') {
          message= "FAIL build: ${env.SERVER_NAME} ${env.JOB_NAME} [${env.BUILD_NUMBER}] (<${env.GITHUBLINK}|The commits>)  (<${env.BUILD_URL}|The Build>)"
          slackSend (
            channel: "${env.SERVER_SLACK_CHANNEL}",
            color: '#36a64f', // green
            message: message
          )
        }
        else {
          echo "Slack message not sent. Check pipeline slack configuration!"
        }
      }
    }
  }
}

////////////////////////////////////////////////////////////////////////////////

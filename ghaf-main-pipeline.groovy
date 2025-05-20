#!/usr/bin/env groovy

// SPDX-FileCopyrightText: 2022-2024 TII (SSRC) and the Ghaf contributors
// SPDX-License-Identifier: Apache-2.0

////////////////////////////////////////////////////////////////////////////////

def REPO_URL = 'https://github.com/tiiuae/ghaf/'
def WORKDIR  = 'ghaf'

// Defines if there is need to run purge_artifacts
def purge_stashed_artifacts = true

// Utils module will be loaded in the first pipeline stage
def utils = null

properties([
  githubProjectProperty(displayName: '', projectUrlStr: REPO_URL),
])

// Record failed target(s)
def failedTargets = []
def failedHWTests = []
def target_jobs = [:]

////////////////////////////////////////////////////////////////////////////////

def targets = [
  [ target: "doc",
    system: "x86_64-linux",
    archive: false,
    scs: false,
    hwtest_device: null,
  ],
  [ target: "lenovo-x1-carbon-gen11-debug",
    system: "x86_64-linux",
    archive: true,
    scs: false,
    hwtest_device: "lenovo-x1",
  ],
  [ target: "dell-latitude-7230-debug",
    system: "x86_64-linux",
    archive: true,
    scs: false,
    hwtest_device: null,
  ],
  [ target: "dell-latitude-7330-debug",
    system: "x86_64-linux",
    archive: true,
    scs: false,
    hwtest_device: "dell-7330",
  ],
  [ target: "nvidia-jetson-orin-agx-debug",
    system: "aarch64-linux",
    archive: true,
    scs: false,
    hwtest_device: "orin-agx",
  ],
  [ target: "nvidia-jetson-orin-agx-debug-from-x86_64",
    system: "x86_64-linux",
    archive: true,
    scs: false,
    hwtest_device: "orin-agx",
  ],
  [ target: "nvidia-jetson-orin-agx64-debug",
    system: "aarch64-linux",
    archive: true,
    scs: false,
    hwtest_device: "orin-agx-64",
  ],
  [ target: "nvidia-jetson-orin-agx64-debug-from-x86_64",
    system: "x86_64-linux",
    archive: true,
    scs: false,
    hwtest_device: "orin-agx-64",
  ],
  [ target: "nvidia-jetson-orin-nx-debug",
    system: "aarch64-linux",
    archive: true,
    scs: false,
    hwtest_device: "orin-nx",
  ],
  [ target: "nvidia-jetson-orin-nx-debug-from-x86_64",
    system: "x86_64-linux",
    archive: true,
    scs: false,
    hwtest_device: "orin-nx",
  ],
]

////////////////////////////////////////////////////////////////////////////////

pipeline {
  agent { label 'built-in' }
  triggers {
    githubPush()
  }
  options {
    buildDiscarder(logRotator(numToKeepStr: '100'))
  }
  stages {
    stage('Checkout') {
      steps {
        script {
	  sh(script: 'rm -Rf sig')
	  utils = load "utils.groovy"
	}
        dir(WORKDIR) {
          checkout scmGit(
            branches: [[name: 'main']],
            extensions: [[$class: 'WipeWorkspace']],
            userRemoteConfigs: [[url: REPO_URL]]
          )
          script {
            env.TARGET_COMMIT = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
            env.ARTIFACTS_REMOTE_PATH = "${env.JOB_NAME}/build_${env.BUILD_ID}-commit_${env.TARGET_COMMIT}"
          }
        }
      }
    }

    stage('Evaluate') {
      steps {
        dir(WORKDIR) {
          lock('evaluator') {
            script {
              utils.nix_eval_jobs(targets)
              target_jobs = utils.create_parallel_stages(targets, testset='_relayboot_bat_', failedTargets=failedTargets, failedHWTests=failedHWTests)
            }
          }
        }
      }
    }

    stage('Build targets') {
      steps {
        script {
          parallel target_jobs
        }
      }
    }
  }

  post {
    always {
      script {
        if(purge_stashed_artifacts) {
          // Remove build results if those are older than 60days time
          utils.purge_artifacts_by_age('ghaf-main-pipeline', '60d')
        }
      }
    }
    failure {
      script {
        githublink="https://github.com/tiiuae/ghaf/commit/${env.TARGET_COMMIT}"
        servername = sh(script: 'uname -n', returnStdout: true).trim()
        echo "Server name:$servername"
        def formattedFailedMessage = ""
        def formattedHWFailedMessage = ""
        def line5=""
        def line6=""
        if (failedTargets) {
          formattedFailedMessage = failedTargets.collect { "- ${it.trim()}" }.join("\n")
        } else {
          formattedFailedMessage = "No failed build targets"
          formattedHWFailedMessage = failedHWTests.collect  { "- ${it.trim()}" }.join("\n")
          line5="\n*Failed HW test targets:*".stripIndent()
          line6="\n${formattedHWFailedMessage}".stripIndent()
        }
        if (servername=="ghaf-jenkins-controller-prod") {
          serverchannel="ghaf-build" // prod main build failures channel
          echo "Slack channel:$serverchannel"
          line1="*FAILURE:* ${env.BUILD_URL}".stripIndent()
          line2="\nCommit: <${githublink}|${env.TARGET_COMMIT}>".stripIndent()
          line3="\n*Failed build targets:*".stripIndent()
          line4="\n${formattedFailedMessage}".stripIndent()
          message = """
          ${line1}
          ${line2}
          ${line3}
          ${line4}
          ${line5}
          ${line6}""".stripIndent()
          slackSend (
            channel: "$serverchannel",
            color: "danger",
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

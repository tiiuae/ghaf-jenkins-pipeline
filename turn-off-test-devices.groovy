#!/usr/bin/env groovy

// SPDX-FileCopyrightText: 2022-2025 TII (SSRC) and the Ghaf contributors
// SPDX-License-Identifier: Apache-2.0

////////////////////////////////////////////////////////////////////////////////

def REPO_URL = 'https://github.com/tiiuae/ci-test-automation/'
def WORKDIR = 'ghaf'

////////////////////////////////////////////////////////////////////////////////

def hwtest_devices = [
  "lenovo-x1",
//   "dell-7330",  // we can't reliably find out if laptop is on or off, so if it was off already we will turn in on
  "orin-agx",
  "orin-agx-64",
  "orin-nx"
]

////////////////////////////////////////////////////////////////////////////////


def ghaf_robot_test(String hwtest_device) {
  def testagent_nodes = nodesByLabel(label: "$hwtest_device", offline: false)
  if (!testagent_nodes) {
    println "Warning: Skipping HW test '$flakeref', no test agents online"
    unstable("No test agents online")
    return
  }

  def job = build(
      job: "tests/x-ghaf-hw-test",
      propagate: false,
      parameters: [
        string(name: "REPO_URL", value: 'https://github.com/tiiuae/ci-test-automation.git'),
        string(name: "IMG_URL", value: ""),
        string(name: "DEVICE_TAG", value: "$hwtest_device"),
        string(name: "BRANCH", value: "main"),
        string(name: "TEST_TAGS", value: ""),
        booleanParam(name: "REFRESH", value: false),
        booleanParam(name: "FLASH_AND_BOOT", value: false),
        booleanParam(name: "BOOT", value: false),
        booleanParam(name: "TURN_OFF", value: true),
        booleanParam(name: "USE_RELAY", value: true),
      ],
      wait: true,
  )

  // If the test job failed, mark the current step unstable and set
  // the final build result failed, but continue the pipeline execution.
  if (job.result != "SUCCESS") {
    unstable("FAILED: $hwtest_device")
    currentBuild.result = "FAILURE"
  }
  return null
}

pipeline {
  agent { label 'built-in' }
  triggers {
    cron('0 19 * * *')
  }
  options {
    disableConcurrentBuilds()
    timestamps()
    buildDiscarder(logRotator(numToKeepStr: '100'))
  }
  environment {
    // https://stackoverflow.com/questions/46680573
    GITREF = params.getOrDefault('GITREF', 'main')
  }
  stages {
    stage('Checkout') {
      steps {
        dir(WORKDIR) {
          checkout scmGit(
            branches: [[name: env.GITREF]],
            extensions: [[$class: 'WipeWorkspace']],
            userRemoteConfigs: [[url: REPO_URL]]
          )
          script {
            env.TARGET_REPO = sh(script: 'git remote get-url origin', returnStdout: true).trim()
            env.TARGET_COMMIT = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
            env.ARTIFACTS_REMOTE_PATH = "${env.JOB_NAME}/build_${env.BUILD_ID}"
          }
        }
      }
    }

    stage('Turn off devices') {
      steps {
        script {
          hwtest_devices.each { device ->
            stage("Turn off ${device}") {
              script {
                ghaf_robot_test(device)
              }
            }
          }
        }
      }
    }
  }
}

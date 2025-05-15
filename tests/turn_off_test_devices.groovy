#!/usr/bin/env groovy

// SPDX-FileCopyrightText: 2022-2024 TII (SSRC) and the Ghaf contributors
// SPDX-License-Identifier: Apache-2.0

////////////////////////////////////////////////////////////////////////////////

def REPO_URL = 'https://github.com/tiiuae/ci-test-automation/'
def DEF_LABEL = 'testagent'
def WORKDIR = 'ghaf'

// Utils module will be loaded in the first pipeline stage
def utils = null

////////////////////////////////////////////////////////////////////////////////

def targets = [
  [ system: "x86_64-linux",
    target: "lenovo-x1-carbon-gen11-debug",
    archive: true,
    scs: false,
    hwtest_device: "lenovo-x1",
  ],
  [ target: "dell-latitude-7330-debug",
    system: "x86_64-linux",
    archive: true,
    scs: false,
    hwtest_device: "dell-7330",
  ],
//   [ system: "aarch64-linux",
//     target: "nvidia-jetson-orin-agx-debug",
//     archive: true,
//     scs: false,
//     hwtest_device: "orin-agx",
//   ],
//   [ system: "aarch64-linux",
//     target: "nvidia-jetson-orin-nx-debug",
//     archive: true,
//     scs: false,
//     hwtest_device: "orin-nx",
//   ],

]

def ghaf_robot_test() {
  if (!env.DEVICE_TAG || !env.DEVICE_NAME) {
    error("DEVICE_TAG or DEVICE_NAME not set")
  }

  dir("Robot-Framework/test-suites") {
    sh 'rm -f *.txt *.png output.xml report.html log.html'
    try {
      // Run Robot Framework tests with specified device variables
      sh '''
        nix run .#ghaf-robot -- \
          -v DEVICE:$DEVICE_NAME \
          -v DEVICE_TYPE:$DEVICE_TAG \
          -i relay-turnoff .
      '''
    } catch (Exception e) {
      currentBuild.result = "FAILURE"
      unstable("FAILED: ${e.toString()}")
    } finally {
      // Move test output files into a separate folder for each device
      def outputDir = "${env.DEVICE_NAME}".replaceAll("[^a-zA-Z0-9_-]", "_")
      sh """
        rm -fr $outputDir; mkdir -p $outputDir
        mv -f *.txt *.png output.xml report.html log.html $outputDir/ || true
      """
    }
  }
}

pipeline {
  agent { label 'built-in' }
  triggers {
    cron('5 8 * * *')  // runs every day at 08:05 UTC
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
        script { utils = load "utils.groovy" }
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

    stage('Hardware tests') {
      steps {
        script {
          targets.each {
            if (it.hwtest_device != null) {
              stage("Test ${it.target} (${it.system})") {
                script {
                  def targetAttr = "${it.system}.${it.target}"
                  utils.ghaf_hw_test(targetAttr, it.hwtest_device, '_turnoff_')
                }
              }
            }
          }
        }
      }
    }
  }
  post {
    always {
      script {
        // Remove temporary, stashed build results before exiting the pipeline
        utils.purge_artifacts(env.ARTIFACTS_REMOTE_PATH)
        // Remove build description because of broken artifacts link
        currentBuild.description = ""
      }
    }
  }
}

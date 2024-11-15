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

// Record failed target(s)
def failedTargets = []

def targets = [
  [ system: "aarch64-linux", target: "doc",
    archive: false, hwtest_device: null
  ],
  [ system: "x86_64-linux", target: "doc",
    archive: false, hwtest_device: null
  ],
  [ system: "x86_64-linux", target: "generic-x86_64-debug",
    archive: true, hwtest_device: "nuc"
  ],
  [ system: "x86_64-linux", target: "lenovo-x1-carbon-gen11-debug",
    archive: true, hwtest_device: "lenovo-x1"
  ],
  [ system: "x86_64-linux", target: "microchip-icicle-kit-debug-from-x86_64",
    archive: true, hwtest_device: "riscv"
  ],
  [ system: "aarch64-linux", target: "nvidia-jetson-orin-agx-debug",
    archive: true, hwtest_device: "orin-agx"
  ],
  [ system: "x86_64-linux", target: "nvidia-jetson-orin-agx-debug-from-x86_64",
    archive: true, hwtest_device: "orin-agx"
  ],
  [ system: "aarch64-linux", target: "nvidia-jetson-orin-nx-debug",
    archive: true, hwtest_device: "orin-nx"
  ],
  [ system: "x86_64-linux", target: "nvidia-jetson-orin-nx-debug-from-x86_64",
    archive: true, hwtest_device: "orin-nx"
  ],
]

target_jobs = [:]

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

    stage('Evaluate') {
      steps {
        dir(WORKDIR) {
          script {
            // Creates jobs.txt in working directory to use later
            utils.nix_eval_jobs(targets)

            targets.each {
              def target = it.system + "." + it.target

              // row that matches this target is grepped from jobs.txt, extracting the pre-evaluated derivation path
              def drvPath = sh (script: "cat jobs.txt | grep ${target} | cut -d ' ' -f 2", returnStdout: true).trim()

              target_jobs["${it.target} (${it.system})"] = {
                stage("Build ${target}") {
                  def opts = ""
                  if (it.archive) {
                    opts = "--out-link archive/${target}"
                  } else {
                    opts = "--no-link"
                  }
                  try {
                    if (drvPath) {
                      sh "nix build -L ${drvPath}\\^* ${opts}"
                      // only attempt signing if there is something to sign
                      if (it.archive) {
                        def img_relpath = utils.find_img_relpath(target, "archive")
                        utils.sign_file("archive/${img_relpath}", "sig/${img_relpath}.sig", "INT-Ghaf-Devenv-Image")
                      }
                    } else {
                      error("Target \"${target}\" was not found in packages")
                    }
                  } catch (InterruptedException e) {
                    throw e
                  } catch (Exception e) {
                    unstable("FAILED: ${target}")
                    currentBuild.result = "FAILURE"
                    echo "Adding target: ${target} for failed builds table"
                    failedTargets.add(target)
                    println "Error: ${e.toString()}"
                  }
                }

                if (it.archive) {
                  stage("Archive ${target}") {
                    script {
                      utils.archive_artifacts("archive", target)
                      utils.archive_artifacts("sig", target)
                    }
                  }
                }

                if (it.hwtest_device != null) {
                  stage("Test ${target}") {
                    utils.ghaf_parallel_hw_test(target, it.hwtest_device, '_boot_bat_')
                  }
                }
              }
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
    failure {
      script {
        githublink="https://github.com/tiiuae/ghaf/commit/${env.TARGET_COMMIT}"
        servername = sh(script: 'uname -n', returnStdout: true).trim()
        echo "Server name:$servername"
        def formattedFailedMessage = ""
        if (failedTargets) {
          formattedFailedMessage = failedTargets.collect { "- ${it.trim()}" }.join("\n")
        } else {
          formattedFailedMessage = "None"
        }
        if (servername=="ghaf-jenkins-controller-prod") {
          serverchannel="ghaf-build" // prod main build failures channel
          echo "Slack channel:$serverchannel"
          line1="*FAILURE:* ${env.BUILD_URL}".stripIndent()
          line2="\n*Failed Targets:*".stripIndent()
          line3="\n${formattedFailedMessage}".stripIndent()
          line4="\n*Commit*: <${githublink}|${env.TARGET_COMMIT}>".stripIndent()
          message = """
          ${line1}
          ${line2}
          ${line3}
          ${line4}""".stripIndent()
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

////////////////////////////////////////////////////////////////////////////////

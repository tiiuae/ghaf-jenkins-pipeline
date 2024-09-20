#!/usr/bin/env groovy

// SPDX-FileCopyrightText: 2024 Technology Innovation Institute (TII)
//
// SPDX-License-Identifier: Apache-2.0

def REPO_URL = 'https://github.com/tiiuae/ghaf/'
def WORKDIR  = 'ghaf'

properties([
  githubProjectProperty(displayName: '', projectUrlStr: REPO_URL),
])

// Ghaf targets to build
// Must match target names defined in #hydraJobs
def targets = [
  [ target: "generic-x86_64-debug.x86_64-linux",
    hwtest_device: "nuc"
  ],
  [ target: "lenovo-x1-carbon-gen11-debug.x86_64-linux",
    hwtest_device: "lenovo-x1"
  ],
  [ target: "microchip-icicle-kit-debug-from-x86_64",
    hwtest_device: "riscv"
  ],
  [ target: "nvidia-jetson-orin-agx-debug.aarch64-linux",
    hwtest_device: "orin-agx"
  ],
  [ target: "nvidia-jetson-orin-agx-debug-from-x86_64.x86_64-linux",
    hwtest_device: "orin-agx"
  ],
  [ target: "nvidia-jetson-orin-nx-debug.aarch64-linux",
    hwtest_device: "orin-nx"
  ],
  [ target: "nvidia-jetson-orin-nx-debug-from-x86_64.x86_64-linux",
    hwtest_device: "orin-nx"
  ],
]

// Utils module will be loaded in the first pipeline stage
def utils = null

// Container for the parallel build stages
def target_jobs = [:]

pipeline {
  agent { label 'built-in' }
  triggers {
    pollSCM '0 23 * * *'
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
            env.TARGET_REPO = sh(script: 'git remote get-url origin', returnStdout: true).trim()
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
            // Which attribute of the flake to evaluate for building
            // Target names must be direct children of this attribute
            def flakeAttr = ".#hydraJobs"

            // nix-eval-jobs is used to evaluate the given flake attribute, and output target information into jobs.json
            sh "nix run github:nix-community/nix-eval-jobs -- --gc-roots-dir gcroots --flake ${flakeAttr} --force-recurse > jobs.json"
            // jobs.json is parsed using jq. target's name and derivation path are appended as space separated row into jobs.txt
            sh "nix run nixpkgs#jq -- -r '.attr + \" \" + .drvPath' < jobs.json > jobs.txt"

            targets.each {
              def target = it['target']

              // row that matches this target is grepped from jobs.txt, extracting the pre-evaluated derivation path
              def drvPath = sh (script: "cat jobs.txt | grep ${target} | cut -d ' ' -f 2", returnStdout: true).trim()

              target_jobs[target] = {
                stage("Build ${target}") {
                  def opts = ""
                  if (it['hwtest_device'] != null) {
                    opts = "--out-link archive/${target}"
                  } else {
                    opts = "--no-link"
                  }
                  try {
                    if (drvPath) {
                      sh "nix build -L ${drvPath}\\^* ${opts}"
                    } else {
                      error("Target \"${target}\" was not found in ${flakeAttr}")
                    }
                  } catch (InterruptedException e) {
                    throw e
                  } catch (Exception e) {
                    unstable("FAILED: ${target}")
                    currentBuild.result = "FAILURE"
                    println "Error: ${e.toString()}"
                  }
                }

                if (it['hwtest_device'] != null) {
                  stage("Archive ${target}") {
                    script {
                      utils.archive_artifacts("archive", target)
                    }
                  }
                }

                if (it['hwtest_device'] != null) {
                  stage("Test ${target}") {
                    utils.ghaf_parallel_hw_test(target, it['hwtest_device'])
                  }
                }

              }
            }
          }
        }
      }
    }

    stage('Parallel build targets') {
      steps {
        script {
          parallel target_jobs
        }
      }
    }
  }
}

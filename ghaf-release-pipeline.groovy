#!/usr/bin/env groovy

// SPDX-FileCopyrightText: 2022-2024 TII (SSRC) and the Ghaf contributors
// SPDX-License-Identifier: Apache-2.0

////////////////////////////////////////////////////////////////////////////////

def REPO_URL = 'https://github.com/tiiuae/ghaf/'
def WORKDIR  = 'ghaf'
def DEF_GITREF = 'main'

// Utils module will be loaded in the first pipeline stage
def utils = null

properties([
  githubProjectProperty(displayName: '', projectUrlStr: REPO_URL),
  parameters([
    string(name: 'GITREF', defaultValue: DEF_GITREF, description: 'Ghaf git reference (Commit/Branch/Tag)')
  ])
])

def targets = [
  // docs
  [ system: "x86_64-linux", target: "doc",
    archive: false
  ],

  // lenovo x1
  [ system: "x86_64-linux", target: "lenovo-x1-carbon-gen11-debug",
    archive: true, scs: true, hwtest_device: "lenovo-x1"
  ],
  [ system: "x86_64-linux", target: "lenovo-x1-carbon-gen11-debug-installer",
    archive: true, scs: true
  ],

  // nvidia orin
  [ system: "aarch64-linux", target: "nvidia-jetson-orin-agx-debug",
    archive: true, scs: true, hwtest_device: "orin-agx"
  ],
  [ system: "aarch64-linux", target: "nvidia-jetson-orin-nx-debug",
    archive: true, scs: true, hwtest_device: "orin-nx"
  ],
  [ system: "x86_64-linux", target: "nvidia-jetson-orin-agx-debug-from-x86_64",
    archive: true, scs: true, hwtest_device: "orin-agx"
  ],
  [ system: "x86_64-linux", target: "nvidia-jetson-orin-nx-debug-from-x86_64",
    archive: true, scs: true, hwtest_device: "orin-nx"
  ],

  // others
  [ system: "x86_64-linux", target: "generic-x86_64-debug",
    archive: true, scs: true, hwtest_device: "nuc"
  ],
  [ system: "x86_64-linux", target: "microchip-icicle-kit-debug-from-x86_64",
    archive: true, scs: true, hwtest_device: "riscv"
  ],
]

target_jobs = [:]

////////////////////////////////////////////////////////////////////////////////

pipeline {
  agent { label 'built-in' }
  options {
    disableConcurrentBuilds()
    timestamps ()
    buildDiscarder(logRotator(numToKeepStr: '100'))
  }
  environment {
    // https://stackoverflow.com/questions/46680573
    GITREF = params.getOrDefault('GITREF', DEF_GITREF)
  }
  stages {
    stage('Checkout') {
      steps {
        script { utils = load "utils.groovy" }
        dir(WORKDIR) {
          checkout scmGit(
            branches: [[name: env.GITREF]],
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
            utils.nix_eval_jobs(targets)
            target_jobs = utils.create_parallel_stages(targets, skip_hw_test=true)
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

    stage('Hardware tests') {
      steps {
        script {
          targets.each {
            if (it.hwtest_device != null) {
              stage("Test ${it.target} (${it.system})") {
                script {
                  def targetAttr = "${it.system}.${it.target}"
                  utils.ghaf_hw_test(targetAttr, it.hwtest_device, '_boot_bat_perf_')
                }
              }
            }
          }
        }
      }
    }
  }
}

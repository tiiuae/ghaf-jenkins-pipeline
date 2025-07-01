#!/usr/bin/env groovy

// SPDX-FileCopyrightText: 2022-2024 TII (SSRC) and the Ghaf contributors
// SPDX-License-Identifier: Apache-2.0

////////////////////////////////////////////////////////////////////////////////

import groovy.json.JsonOutput

def REPO_URL = 'https://github.com/tiiuae/ghaf/'
def WORKDIR  = 'ghaf'

// Defines if there is need to run purge_artifacts
def purge_stashed_artifacts = true

// Utils module will be loaded in the first pipeline stage
def utils = null

properties([
  githubProjectProperty(displayName: '', projectUrlStr: REPO_URL),
])

def target_jobs = [:]

////////////////////////////////////////////////////////////////////////////////

def targets = [
  // docs
  [ target: "doc",
    system: "x86_64-linux",
    archive: false,
    scs: false,
    hwtest_device: null,
  ],

  // lenovo x1
  [ target: "lenovo-x1-carbon-gen11-debug",
    system: "x86_64-linux",
    archive: true,
    scs: true,
    hwtest_device: "lenovo-x1",
  ],
  [ target: "lenovo-x1-carbon-gen11-debug-installer",
    system: "x86_64-linux",
    archive: true,
    scs: true,
    hwtest_device: "lenovo-x1",
  ],
  [ target: "lenovo-x1-carbon-gen11-release",
    system: "x86_64-linux",
    archive: true,
    scs: true,
    hwtest_device: null,
  ],
  [ target: "lenovo-x1-carbon-gen11-release-installer",
    system: "x86_64-linux",
    archive: true,
    scs: true,
    hwtest_device: null,
  ],
  [ target: "lenovo-x1-gen11-hardening-debug",
    system: "x86_64-linux",
    archive: true,
    scs: false,
    hwtest_device: null,
  ],
  [ target: "lenovo-x1-gen11-hardening-debug-installer",
    system: "x86_64-linux",
    archive: true,
    scs: false,
    hwtest_device: null,
  ],

  // Dell Latitude rugged laptops
  [ target: "dell-latitude-7230-debug",
    system: "x86_64-linux",
    archive: true,
    scs: true,
    hwtest_device: null,
  ],
  [ target: "dell-latitude-7330-debug",
    system: "x86_64-linux",
    archive: true,
    scs: true,
    hwtest_device: "dell-7330",
  ],

  // nvidia orin
  [ target: "nvidia-jetson-orin-agx-debug",
    system: "aarch64-linux",
    archive: true,
    scs: true,
    hwtest_device: "orin-agx",
  ],
  [ target: "nvidia-jetson-orin-agx64-debug",
    system: "aarch64-linux",
    archive: true,
    scs: false,
    hwtest_device: "orin-agx-64",
  ],
  [ target: "nvidia-jetson-orin-nx-debug",
    system: "aarch64-linux",
    archive: true,
    scs: true,
    hwtest_device: "orin-nx",
  ],
  [ target: "nvidia-jetson-orin-agx-debug-from-x86_64",
    system: "x86_64-linux",
    archive: true,
    scs: true,
    hwtest_device: "orin-agx",
  ],
  [ target: "nvidia-jetson-orin-agx64-debug-from-x86_64",
    system: "x86_64-linux",
    archive: true,
    scs: true,
    hwtest_device: "orin-agx-64",
  ],
  [ target: "nvidia-jetson-orin-nx-debug-from-x86_64",
    system: "x86_64-linux",
    archive: true,
    scs: true,
    hwtest_device: "orin-nx",
  ],

  // others
  [ target: "generic-x86_64-debug",
    system: "x86_64-linux",
    archive: true,
    scs: false,
    hwtest_device: null,
  ],
  [ target: "nxp-imx8mp-evk-debug",
    system: "aarch64-linux",
    archive: true,
    scs: true,
    hwtest_device: null,
  ],
]

// bpmp builds are disabled for now!
// def hydrajobs_targets = [
//   // nvidia orin with bpmp enabled
//   [ target: "nvidia-jetson-orin-agx-debug-bpmp",
//     system: "aarch64-linux",
//     archive: true,
//     scs: false,
//     hwtest_device: null,
//   ],
//   [ target: "nvidia-jetson-orin-nx-debug-bpmp",
//     system: "aarch64-linux",
//     archive: true,
//     scs: false,
//     hwtest_device: null,
//   ],
//   [ target: "nvidia-jetson-orin-agx-debug-bpmp-from-x86_64",
//     system: "x86_64-linux",
//     archive: true,
//     scs: false,
//     hwtest_device: null,
//   ],
//   [ target: "nvidia-jetson-orin-nx-debug-bpmp-from-x86_64",
//     system: "x86_64-linux",
//     archive: true,
//     scs: false,
//     hwtest_device: null,
//   ],
// ]

////////////////////////////////////////////////////////////////////////////////

pipeline {
  agent { label 'built-in' }

  triggers {
     // We could use something like cron('@midnight') here, but since we
     // archive the images, this pipeline would then generate many
     // tens of gigabytes of artifacts every night, even if there were no new
     // commits to main since the last nigthly run. Therefore, for now,
     // we trigger based one-time daily poll at 20:00 UTC instead:
     pollSCM('0 20 * * *')
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
          lock('evaluator') {
            script {
              utils.nix_eval_jobs(targets)
              // remove when hydrajobs is retired from ghaf
              // bpmp builds are disabled for now!
              // utils.nix_eval_hydrajobs(hydrajobs_targets)
              //targets = targets + hydrajobs_targets

              target_jobs = utils.create_parallel_stages(targets, testset='_relayboot_gui_regression_')
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
          // Remove build results if those are older than 60 days
          utils.purge_artifacts_by_age('ghaf-nightly-pipeline', '60d')
        }
      }
    }
  }
}

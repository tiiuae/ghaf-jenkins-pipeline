#!/usr/bin/env groovy

// SPDX-FileCopyrightText: 2022-2024 TII (SSRC) and the Ghaf contributors
// SPDX-License-Identifier: Apache-2.0

////////////////////////////////////////////////////////////////////////////////

def REPO_URL = 'https://github.com/tiiuae/ghaf/'

def WORKDIR  = 'ghaf'

def DEF_GITHUB_PR_NUMBER = ''

// Defines if there is need to run purge_artifacts
def purge_stashed_artifacts = true

// Utils module will be loaded in the first pipeline stage
def utils = null

properties([
  githubProjectProperty(displayName: '', projectUrlStr: REPO_URL),
  parameters([
    string(name: 'GITHUB_PR_NUMBER', defaultValue: DEF_GITHUB_PR_NUMBER, description: 'Ghaf PR number')
  ])
])

def target_jobs = [:]

////////////////////////////////////////////////////////////////////////////////

def setBuildStatus(String message, String state, String commit) {
  withCredentials([string(credentialsId: 'ssrcdevops-classic', variable: 'TOKEN')]) {
    env.TOKEN = "$TOKEN"
    String status_url = "https://api.github.com/repos/tiiuae/ghaf/statuses/$commit"
    sh """
      # set -x
      curl -H \"Authorization: token \$TOKEN\" \
        -X POST \
        -d '{\"description\": \"$message\", \
             \"state\": \"$state\", \
             \"context\": "ghaf-pre-merge-pipeline", \
             \"target_url\" : \"$BUILD_URL\" }' \
        ${status_url}
    """
  }
}

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
  options {
    buildDiscarder(logRotator(numToKeepStr: '100'))
  }
  environment {
    // https://stackoverflow.com/questions/46680573
    GITHUB_PR_NUMBER = params.getOrDefault('GITHUB_PR_NUMBER', DEF_GITHUB_PR_NUMBER)
  }
  stages {
    stage('Checkenv') {
      steps {
        sh 'if [ -z "$GITHUB_PR_NUMBER" ]; then exit 1; fi'
        // Fail if environment is otherwise unexpected
        sh 'if [ -z "$JOB_BASE_NAME" ]; then exit 1; fi'
        sh 'if [ -z "$BUILD_NUMBER" ]; then exit 1; fi'
        sh 'if [ -z "$BUILD_URL" ]; then exit 1; fi'
        script {
          def href = "${REPO_URL}/pull/${GITHUB_PR_NUMBER}"
          currentBuild.description = "<br>(<a href=\"${href}\">#${GITHUB_PR_NUMBER}</a>)"
        }
      }
    }
    stage('Checkout') {
      steps {
        script { utils = load "utils.groovy" }
        dir(WORKDIR) {
          checkout scmGit(
            userRemoteConfigs: [[
              url: REPO_URL,
              name: 'pr_origin',
              // Below, we set the git remote: 'pr_origin'.
              // We use '/merge' in pr_origin to build the PR as if it was
              // merged to the PR target branch. To build the PR head (without
              // merge) you would replace '/merge' with '/head'.
              refspec: '+refs/pull/${GITHUB_PR_NUMBER}/merge:refs/remotes/pr_origin/pull/${GITHUB_PR_NUMBER}/merge',
            ]],
            branches: [[name: 'pr_origin/pull/${GITHUB_PR_NUMBER}/merge']],
            extensions: [
              [$class: 'WipeWorkspace'],
            ],
          )
          script {
            sh 'git fetch pr_origin pull/${GITHUB_PR_NUMBER}/head:PR_head'
            env.TARGET_COMMIT = sh(script: 'git rev-parse PR_head', returnStdout: true).trim()
            println "TARGET_COMMIT: ${env.TARGET_COMMIT}"
            env.ARTIFACTS_REMOTE_PATH = "stash/${env.BUILD_TAG}-commit_${env.TARGET_COMMIT}"
          }
        }
      }
    }

    stage('Set PR status pending') {
      steps {
        script {
          setBuildStatus(message="Manual trigger: pending", state="pending", commit=env.TARGET_COMMIT)
        }
      }
    }

    stage('Evaluate') {
      steps {
        dir(WORKDIR) {
          lock('evaluator') {
            script {
              utils.nix_eval_jobs(targets)
              target_jobs = utils.create_parallel_stages(targets, testset='_relayboot_pre-merge_')
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
          // Remove temporary, stashed build results if those are older than 14days
          utils.purge_artifacts_by_age('stash', '14d')
        }
      }
    }
    success {
      script {
        setBuildStatus(message="Manual trigger: success", state="success", commit=env.TARGET_COMMIT)
      }
    }
    unsuccessful {
      script {
        setBuildStatus(message="Manual trigger: failure", state="failure", commit=env.TARGET_COMMIT)
      }
    }
  }
}

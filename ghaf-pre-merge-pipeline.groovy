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
  // The following options are documented in:
  // https://www.jenkins.io/doc/pipeline/steps/params/pipelinetriggers/
  // Following config requires having github token configured in:
  // 'Manage Jenkins' > 'System' > 'Github' > 'GitHub Server' > 'Credentials'.
  // Token needs to be 'classic' with 'repo' scope to be able to both set the
  // commit statuses and read the commit author organization.
  // 'HEAVY_HOOKS' requires webhooks configured properly in the target
  // github repository. If webhooks cannot be used, consider using polling by
  // replacing 'HEAVY_HOOKS' with 'CRON' and declaring the poll interval in
  // 'spec'.
  pipelineTriggers([
    githubPullRequests(
      spec: '',
      triggerMode: 'HEAVY_HOOKS',
      // Trigger on PR open, change, or close. Skip if PR is not mergeable.
      events: [Open(), commitChanged(), close(), nonMergeable(skip: true)],
      abortRunning: true,
      cancelQueued: true,
      preStatus: true,
      skipFirstRun: false,
      userRestriction: [users: '', orgs: 'tiiuae'],
      repoProviders: [
        githubPlugin(
          repoPermission: 'PULL'
        )
      ]
    )
  ])
])

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
    hwtest_device: null,
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
  stages {
    stage('Checkenv') {
      steps {
        sh 'set | grep -P "(GITHUB_PR_)"'
        // Fail if this build was not triggered by a PR
        sh 'if [ -z "$GITHUB_PR_NUMBER" ]; then exit 1; fi'
        sh 'if [ -z "$GITHUB_PR_TARGET_BRANCH" ]; then exit 1; fi'
        sh 'if [ -z "$GITHUB_PR_STATE" ]; then exit 1; fi'
        // Fail if environment is otherwise unexpected
        sh 'if [ -z "$JOB_BASE_NAME" ]; then exit 1; fi'
        sh 'if [ -z "$BUILD_NUMBER" ]; then exit 1; fi'
        // Fail if PR was closed (but not merged)
        sh 'if [ "$GITHUB_PR_STATE" = "CLOSED" ]; then exit 1; fi'
      }
    }
    stage('Checkout') {
      steps {
        script { utils = load "utils.groovy" }
        dir(WORKDIR) {
          // References:
          // https://www.jenkins.io/doc/pipeline/steps/params/scmgit/#scmgit
          // https://github.com/KostyaSha/github-integration-plugin/blob/master/docs/Configuration.adoc
          checkout scmGit(
            userRemoteConfigs: [[
              url: REPO_URL,
              name: 'pr_origin',
              // Below, we set two git remotes: 'pr_origin' and 'origin'
              // We use '/merge' in pr_origin to build the PR as if it was
              // merged to the PR target branch GITHUB_PR_TARGET_BRANCH.
              // To build the PR head (without merge) you would replace
              // '/merge' with '/head' in the pr_origin remote. We also
              // need to set the 'origin' remote to be able to compare
              // the PR changes against the correct target.
              refspec: '+refs/pull/${GITHUB_PR_NUMBER}/merge:refs/remotes/pr_origin/pull/${GITHUB_PR_NUMBER}/merge +refs/heads/*:refs/remotes/origin/*',
            ]],
            branches: [[name: 'pr_origin/pull/${GITHUB_PR_NUMBER}/merge']],
            extensions: [
              [$class: 'WipeWorkspace'],
              // We use the 'changelogToBranch' extension to correctly
              // show the PR changed commits in Jenkins changes.
              // References:
              // https://issues.jenkins.io/browse/JENKINS-26354
              // https://javadoc.jenkins.io/plugin/git/hudson/plugins/git/extensions/impl/ChangelogToBranch.html
              changelogToBranch (
                options: [
                  compareRemote: 'origin',
                  compareTarget: "${GITHUB_PR_TARGET_BRANCH}"
                ]
              )
            ],
          )
          script {
            env.TARGET_COMMIT = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
            env.ARTIFACTS_REMOTE_PATH = "stash/${env.BUILD_TAG}-commit_${env.TARGET_COMMIT}"
          }
        }
      }
    }
    stage('Set PR status pending') {
      steps {
        script {
          // https://www.jenkins.io/doc/pipeline/steps/github-pullrequest/
          setGitHubPullRequestStatus(
            state: 'PENDING',
            context: "${JOB_BASE_NAME}",
            message: "Build #${BUILD_NUMBER} started",
          )
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
        setGitHubPullRequestStatus(
          state: 'SUCCESS',
          context: "${JOB_BASE_NAME}",
          message: "Build #${BUILD_NUMBER} passed in ${currentBuild.durationString}",
        )
      }
    }
    unsuccessful {
      script {
        setGitHubPullRequestStatus(
          state: 'FAILURE',
          context: "${JOB_BASE_NAME}",
          message: "Build #${BUILD_NUMBER} failed in ${currentBuild.durationString}",
        )
      }
    }
  }
}

////////////////////////////////////////////////////////////////////////////////

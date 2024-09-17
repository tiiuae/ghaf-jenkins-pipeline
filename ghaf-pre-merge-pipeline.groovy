#!/usr/bin/env groovy

// SPDX-FileCopyrightText: 2022-2024 TII (SSRC) and the Ghaf contributors
// SPDX-License-Identifier: Apache-2.0

////////////////////////////////////////////////////////////////////////////////

def REPO_URL = 'https://github.com/tiiuae/ghaf/'
def WORKDIR  = 'ghaf'

// Which attribute of the flake to evaluate for building
def flakeAttr = ".#hydraJobs"

// Target names must be direct children of the above
def targets = [
  [ target: "docs.aarch64-linux" ],
  [ target: "docs.x86_64-linux" ],
  [ target: "generic-x86_64-debug.x86_64-linux" ],
  [ target: "lenovo-x1-carbon-gen11-debug.x86_64-linux" ],
  [ target: "microchip-icicle-kit-debug-from-x86_64.x86_64-linux" ],
  [ target: "nvidia-jetson-orin-agx-debug.aarch64-linux" ],
  [ target: "nvidia-jetson-orin-agx-debug-from-x86_64.x86_64-linux" ],
  [ target: "nvidia-jetson-orin-nx-debug.aarch64-linux" ],
  [ target: "nvidia-jetson-orin-nx-debug-from-x86_64.x86_64-linux" ],
]

target_jobs = [:]

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

////////////////////////////////////////////////////////////////////////////////

pipeline {
  agent { label 'built-in' }
  options {
    timestamps ()
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
              cleanBeforeCheckout(),
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
          script {
            // nix-eval-jobs is used to evaluate the given flake attribute, and output target information into jobs.json
            sh "nix-eval-jobs --gc-roots-dir gcroots --flake ${flakeAttr} --force-recurse > jobs.json"

            // jobs.json is parsed using jq. target's name and derivation path are appended as space separated row into jobs.txt 
            sh "jq -r '.attr + \" \" + .drvPath' < jobs.json > jobs.txt"

            targets.each {
              def target = it['target']

              // row that matches this target is grepped from jobs.txt, extracting the pre-evaluated derivation path
              def drvPath = sh (script: "cat jobs.txt | grep ${target} | cut -d ' ' -f 2", returnStdout: true).trim()

              target_jobs[target] = {
                stage("${target}") {
                  catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
                    if (drvPath) {
                      sh "nix build -L ${drvPath}\\^*"
                    } else {
                      error("Target \"${target}\" was not found in ${flakeAttr}")
                    }
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

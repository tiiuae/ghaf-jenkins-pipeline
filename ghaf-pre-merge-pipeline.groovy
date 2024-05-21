#!/usr/bin/env groovy

// SPDX-FileCopyrightText: 2024 Technology Innovation Institute (TII)
//
// SPDX-License-Identifier: Apache-2.0

////////////////////////////////////////////////////////////////////////////////

def nix_build(flakeref) {
  try {
    sh "nix build ${flakeref}"
  } catch (InterruptedException e) {
    // Do not continue pipeline execution on abort.
    throw e
  } catch (Exception e) {
    // Otherwise, if the command fails, mark the current step unstable and set
    // the final build result to failed, but continue the pipeline execution.
    unstable("FAILED: ${flakeref}")
    currentBuild.result = "FAILURE"
  }
}

////////////////////////////////////////////////////////////////////////////////

properties([
  githubProjectProperty(displayName: '', projectUrlStr: 'https://github.com/tiiuae/ghaf/'),
  // The following options are documented in:
  // https://www.jenkins.io/doc/pipeline/steps/params/pipelinetriggers/
  // Following config requires having github token configured in:
  // 'Manage Jenkins' > 'System' > 'Github' > 'GitHub Server' > 'Credentials'.
  // Token needs to be 'classic' with 'repo' scope to be able to both set the
  // commit statuses and read the commit author organization.
  // 'HEAVY_HOOKS' requires webhooks configured properly in the target
  // github repository. If webhooks cannot be used, consider using polling by
  // replacing 'HEAVY_HOOKS' with 'CRON' and declaring the poll intervall in
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
    buildDiscarder(logRotator(artifactNumToKeepStr: '5', numToKeepStr: '100'))
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
        dir('pr') {
          // References:
          // https://www.jenkins.io/doc/pipeline/steps/params/scmgit/#scmgit
          // https://github.com/KostyaSha/github-integration-plugin/blob/master/docs/Configuration.adoc
          checkout scmGit(
            userRemoteConfigs: [[
              url: 'https://github.com/tiiuae/ghaf.git',
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
    stage('Build x86_64') {
      steps {
        dir('pr') {
          nix_build('.#packages.x86_64-linux.nvidia-jetson-orin-agx-debug-from-x86_64')
          nix_build('.#packages.x86_64-linux.nvidia-jetson-orin-nx-debug-from-x86_64')
          nix_build('.#packages.x86_64-linux.lenovo-x1-carbon-gen11-debug')
          nix_build('.#packages.riscv64-linux.microchip-icicle-kit-debug')
          nix_build('.#packages.x86_64-linux.doc')
        }
      }
    }
    stage('Build aarch64') {
      steps {
        dir('pr') {
          nix_build('.#packages.aarch64-linux.nvidia-jetson-orin-agx-debug')
          nix_build('.#packages.aarch64-linux.nvidia-jetson-orin-nx-debug')
          nix_build('.#packages.aarch64-linux.doc')
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

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

////////////////////////////////////////////////////////////////////////////////

pipeline {
  agent { label 'built-in' }
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
            env.TARGET_COMMIT = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
            env.STASH_REMOTE_PATH = "stash/${env.BUILD_TAG}-commit_${env.TARGET_COMMIT}"
            env.ARTIFACTS_REMOTE_PATH = "${env.JOB_NAME}/build_${env.BUILD_ID}-commit_${env.TARGET_COMMIT}"
          }
        }
      }
    }
    stage('Build x86_64') {
      steps {
        dir(WORKDIR) {
          script {
            // Example build, temporary stashing build output to make it
            // available for HW-testing
            utils.nix_build('.#packages.x86_64-linux.nvidia-jetson-orin-agx-debug-from-x86_64', 'stash')
            // Example build, archiving the build output to 'permanent' artifacts
            utils.nix_build('.#packages.x86_64-linux.nvidia-jetson-orin-nx-debug-from-x86_64', 'archive')
          }
        }
      }
    }
    stage('HW Tests (stashed image)') {
      // Example HW test trigger using stashed image. For instance, pre-merge
      // builds do not archive the image as build artifact. Therefore, we
      // would need to use the 'stash' as a temporary artifact storage to be
      // able to provide the image to the HW testing job.
      steps {
        dir(WORKDIR) {
          script {
            imgdir = utils.find_img_relpath('.#packages.x86_64-linux.nvidia-jetson-orin-agx-debug-from-x86_64', 'stash')
            remote_path = "artifacts/${env.STASH_REMOTE_PATH}"
            jenkins_url = "https://ghaf-jenkins-controller-henrirosten.northeurope.cloudapp.azure.com"
            img_url = "${jenkins_url}/${remote_path}/${imgdir}"
            // Trigger a build in test-ghaf-dummy-hw-test pipeline
            build = build(
              job: "test-ghaf-dummy-hw-test",
              propagate: true,
              parameters: [
                string(name: "LABEL", value: "testagent"),
                string(name: "DEVICE", value: "orin-agx"),
                string(name: "IMG_URL", value: "$img_url"),
              ],
              wait: true,
            )
          }
        }
      }
    }
    stage('HW Tests (archived image)') {
      // Example HW test trigger using archived image.
      // Builds that archive the image as an artifact can point to the archived
      // image in HW test job trigger.
      steps {
        dir(WORKDIR) {
          script {
            imgdir = utils.find_img_relpath('.#packages.x86_64-linux.nvidia-jetson-orin-nx-debug-from-x86_64', 'archive')
            remote_path = "artifacts/${env.ARTIFACTS_REMOTE_PATH}"
            jenkins_url = "https://ghaf-jenkins-controller-henrirosten.northeurope.cloudapp.azure.com"
            img_url = "${jenkins_url}/${remote_path}/${imgdir}"
            // Trigger a build in test-ghaf-dummy-hw-test pipeline
            build = build(
              job: "test-ghaf-dummy-hw-test",
              propagate: true,
              parameters: [
                string(name: "LABEL", value: "testagent"),
                string(name: "DEVICE", value: "orin-nx"),
                string(name: "IMG_URL", value: "$img_url"),
              ],
              wait: true,
            )
          }
        }
      }
    }
  }
  post {
    always {
      script {
        // Remove temporary, stashed build results before exiting the pipeline
        utils.purge_stash("${env.STASH_REMOTE_PATH}")
      }
    }
  }
}

////////////////////////////////////////////////////////////////////////////////

#!/usr/bin/env groovy

// SPDX-FileCopyrightText: 2024 Technology Innovation Institute (TII)
//
// SPDX-License-Identifier: Apache-2.0

///////////////////////////////////////////////////////////////////////

// Default values for parameters
DEFAULT_URL = 'https://github.com/tiiuae/FMO-OS.git'
DEFAULT_REF = 'main'

// Utils module
def utils = null

// Parallel stages for targets under test
def tests = [:]

// FMO-OS targets
def targets = [
  '.#packages.x86_64-linux.fmo-os-installer-public-debug',
  '.#packages.x86_64-linux.fmo-os-installer-public-release',
  '.#packages.x86_64-linux.fmo-os-rugged-laptop-7330-public-debug',
  '.#packages.x86_64-linux.fmo-os-rugged-laptop-7330-public-release',
  '.#packages.x86_64-linux.fmo-os-rugged-tablet-7230-public-debug',
  '.#packages.x86_64-linux.fmo-os-rugged-tablet-7230-public-release'
]

// RUN_TYPE parameter description
def run_type_description = '''
normal - executing all configured build and test stages normally<br>
setup  - only reloading configuration, not running futher stages
'''

///////////////////////////////////////////////////////////////////////

// define code blocks per target to execute as brances for parallel step
targets.each {
  def target = "${it}"
  tests[target] = {

    stage("${target}") {
      node('built-in') {
        stage("Build ${target}") {
          dir(FMO_PATH) {
            utils.nix_build("${target}", 'archive')
          }
        }
      }
    }

  }
}

///////////////////////////////////////////////////////////////////////

pipeline {
  agent none
  parameters {
    string description: 'Repository URL',
      name: 'URL',
      defaultValue: DEFAULT_URL
    string description: 'Branch (or revision reference) Specifier',
      name: 'BRANCH',
      defaultValue: DEFAULT_REF
    choice name: 'RUN_TYPE',
      choices: ['normal', 'setup' ],
      description: run_type_description
  }
  triggers {
    pollSCM '* * * * *'
  }
  options {
    disableConcurrentBuilds()
    buildDiscarder logRotator(
      artifactDaysToKeepStr: '7',
      artifactNumToKeepStr: '10',
      daysToKeepStr: '70',
      numToKeepStr: '100'
     )
  }
  environment {
    FMO_PATH = "$JENKINS_HOME/workspace/FMO-OS-main-ws"

    // Use default values if parameter values are not yet defined
    // like the case on the very first run
    FMO_URL = params.getOrDefault('URL', DEFAULT_URL)
    FMO_REF = params.getOrDefault('BRANCH', DEFAULT_REF)
  }
  stages {
    stage('Setup') {
      when {
        anyOf {
          triggeredBy 'JobDslCause';
          environment name: 'RUN_TYPE', value: 'setup'
        }
      }
      steps {
        script {
          String note = 'Project configuration parsed.'
          echo note
          currentBuild.description = note
          currentBuild.result = 'NOT_BUILT'
        }
      }
    }
    stage('Checkout') {
      agent any
      when {
        not {
          anyOf {
            triggeredBy 'JobDslCause';
            environment name: 'RUN_TYPE', value: 'setup'
          }
        }
      }
      steps {
        script {
          utils = load "utils.groovy"
          dir(FMO_PATH) {
            def scm = checkout scmGit(
              branches: [[name: FMO_REF]],
              extensions: [cleanBeforeCheckout()],
              userRemoteConfigs: [[url: FMO_URL]]
            )
            env.TARGET_COMMIT = scm.GIT_COMMIT
            env.ARTIFACTS_REMOTE_PATH = "${JOB_NAME}/build_${BUILD_ID}-commit_${TARGET_COMMIT}"
          }
        }
      }
    }
    stage('Test targets') {
      when {
        not {
          anyOf {
            triggeredBy 'JobDslCause';
            environment name: 'RUN_TYPE', value: 'setup'
          }
        }
      }
      steps {
        script {
          parallel tests
        }
      }
    }
  }
}

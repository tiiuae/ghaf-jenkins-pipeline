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
    stage('Build x86_64') {
      steps {
        dir(WORKDIR) {
          script {
            utils.nix_build('.#packages.x86_64-linux.nvidia-jetson-orin-agx-debug-from-x86_64', 'archive')
            utils.nix_build('.#packages.x86_64-linux.nvidia-jetson-orin-nx-debug-from-x86_64', 'archive')
            utils.nix_build('.#packages.x86_64-linux.lenovo-x1-carbon-gen11-debug', 'archive')
            utils.nix_build('.#packages.x86_64-linux.generic-x86_64-debug', 'archive')
            utils.nix_build('.#packages.x86_64-linux.microchip-icicle-kit-debug-from-x86_64', 'archive')
            utils.nix_build('.#packages.x86_64-linux.doc', 'archive')
          }
        }
      }
    }
    stage('Build aarch64') {
      steps {
        dir(WORKDIR) {
          script {
            utils.nix_build('.#packages.aarch64-linux.nvidia-jetson-orin-agx-debug', 'archive')
            utils.nix_build('.#packages.aarch64-linux.nvidia-jetson-orin-nx-debug', 'archive')
          }
        }
      }
    }
    stage('Provenance') {
      steps {
        dir(WORKDIR) {
          script {
            utils.sbomnix('provenance', '.#packages.x86_64-linux.nvidia-jetson-orin-agx-debug-from-x86_64')
            utils.sbomnix('provenance', '.#packages.x86_64-linux.nvidia-jetson-orin-nx-debug-from-x86_64')
            utils.sbomnix('provenance', '.#packages.x86_64-linux.lenovo-x1-carbon-gen11-debug')
            utils.sbomnix('provenance', '.#packages.x86_64-linux.generic-x86_64-debug')
            utils.sbomnix('provenance', '.#packages.x86_64-linux.microchip-icicle-kit-debug-from-x86_64')
            utils.sbomnix('provenance', '.#packages.aarch64-linux.nvidia-jetson-orin-agx-debug')
            utils.sbomnix('provenance', '.#packages.aarch64-linux.nvidia-jetson-orin-nx-debug')
          }
        }
      }
    }
    stage('SBOM') {
      steps {
        dir(WORKDIR) {
          script {
            utils.sbomnix('sbomnix', '.#packages.x86_64-linux.nvidia-jetson-orin-agx-debug-from-x86_64')
            utils.sbomnix('sbomnix', '.#packages.x86_64-linux.nvidia-jetson-orin-nx-debug-from-x86_64')
            utils.sbomnix('sbomnix', '.#packages.x86_64-linux.lenovo-x1-carbon-gen11-debug')
            utils.sbomnix('sbomnix', '.#packages.x86_64-linux.generic-x86_64-debug')
            utils.sbomnix('sbomnix', '.#packages.x86_64-linux.microchip-icicle-kit-debug-from-x86_64')
            utils.sbomnix('sbomnix', '.#packages.aarch64-linux.nvidia-jetson-orin-agx-debug')
            utils.sbomnix('sbomnix', '.#packages.aarch64-linux.nvidia-jetson-orin-nx-debug')
          }
        }
      }
    }
    stage('Vulnxscan') {
      steps {
        dir(WORKDIR) {
          script {
            utils.sbomnix('vulnxscan', '.#packages.x86_64-linux.nvidia-jetson-orin-agx-debug-from-x86_64')
            utils.sbomnix('vulnxscan', '.#packages.x86_64-linux.nvidia-jetson-orin-nx-debug-from-x86_64')
            utils.sbomnix('vulnxscan', '.#packages.x86_64-linux.lenovo-x1-carbon-gen11-debug')
            utils.sbomnix('vulnxscan', '.#packages.x86_64-linux.generic-x86_64-debug')
            utils.sbomnix('vulnxscan', '.#packages.x86_64-linux.microchip-icicle-kit-debug-from-x86_64')
            utils.sbomnix('vulnxscan', '.#packages.aarch64-linux.nvidia-jetson-orin-agx-debug')
            utils.sbomnix('vulnxscan', '.#packages.aarch64-linux.nvidia-jetson-orin-nx-debug')
          }
        }
      }
    }
    stage('HW test') {
      steps {
        dir(WORKDIR) {
          script {
            testset = "_boot_bat_perf_"
            utils.ghaf_hw_test('.#packages.x86_64-linux.nvidia-jetson-orin-agx-debug-from-x86_64', 'orin-agx', testset)
            utils.ghaf_hw_test('.#packages.aarch64-linux.nvidia-jetson-orin-agx-debug', 'orin-agx', testset)
            utils.ghaf_hw_test('.#packages.x86_64-linux.nvidia-jetson-orin-nx-debug-from-x86_64', 'orin-nx', testset)
            utils.ghaf_hw_test('.#packages.aarch64-linux.nvidia-jetson-orin-nx-debug', 'orin-nx', testset)
            utils.ghaf_hw_test('.#packages.x86_64-linux.lenovo-x1-carbon-gen11-debug', 'lenovo-x1', testset)
            utils.ghaf_hw_test('.#packages.x86_64-linux.generic-x86_64-debug', 'nuc', testset)
            utils.ghaf_hw_test('.#packages.x86_64-linux.microchip-icicle-kit-debug-from-x86_64', 'riscv', testset)
          }
        }
      }
    }
  }
}

////////////////////////////////////////////////////////////////////////////////

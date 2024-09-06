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
  triggers {
     // We could use something like cron('@midnight') here, but since we
     // archive the images, this pipeline would then generate many
     // tens of gigabytes of artifacts every night, even if there were no new
     // commits to main since the last nigthly run. Therefore, for now,
     // we trigger based one-time daily poll at 23:00 instead:
     pollSCM('0 23 * * *')
  }
  options {
    disableConcurrentBuilds()
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
    stage('Build x86_64') {
      steps {
        dir(WORKDIR) {
          script {
            utils.nix_build('.#packages.x86_64-linux.nvidia-jetson-orin-agx-debug-from-x86_64', 'archive')
            utils.nix_build('.#packages.x86_64-linux.nvidia-jetson-orin-nx-debug-from-x86_64', 'archive')
            utils.nix_build('.#packages.x86_64-linux.lenovo-x1-carbon-gen11-debug', 'archive')
            utils.nix_build('.#packages.x86_64-linux.lenovo-x1-carbon-gen11-debug-installer', 'archive')
            utils.nix_build('.#packages.x86_64-linux.lenovo-x1-carbon-gen11-release', 'archive')
            utils.nix_build('.#packages.x86_64-linux.lenovo-x1-carbon-gen11-release-installer', 'archive')
            utils.nix_build('.#packages.x86_64-linux.generic-x86_64-debug', 'archive')
            utils.nix_build('.#packages.x86_64-linux.microchip-icicle-kit-debug-from-x86_64', 'archive')
            utils.nix_build('.#hydraJobs.nvidia-jetson-orin-agx-debug-bpmp-from-x86_64.x86_64-linux', 'archive')
            utils.nix_build('.#hydraJobs.nvidia-jetson-orin-nx-debug-bpmp-from-x86_64.x86_64-linux', 'archive')
            utils.nix_build('.#packages.x86_64-linux.doc')
          }
        }
      }
    }
    stage('Build aarch64') {
      steps {
        dir(WORKDIR) {
          script {
            utils.nix_build('.#packages.aarch64-linux.nxp-imx8mp-evk-debug', 'archive')
            utils.nix_build('.#packages.aarch64-linux.nvidia-jetson-orin-agx-debug', 'archive')
            utils.nix_build('.#packages.aarch64-linux.nvidia-jetson-orin-nx-debug', 'archive')
            utils.nix_build('.#hydraJobs.nvidia-jetson-orin-agx-debug-bpmp.aarch64-linux', 'archive')
            utils.nix_build('.#hydraJobs.nvidia-jetson-orin-nx-debug-bpmp.aarch64-linux', 'archive')
            utils.nix_build('.#packages.aarch64-linux.doc')
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
            utils.sbomnix('provenance', '.#packages.x86_64-linux.lenovo-x1-carbon-gen11-debug-installer')
            utils.sbomnix('provenance', '.#packages.x86_64-linux.lenovo-x1-carbon-gen11-release')
            utils.sbomnix('provenance', '.#packages.x86_64-linux.lenovo-x1-carbon-gen11-release-installer')
            utils.sbomnix('provenance', '.#packages.x86_64-linux.microchip-icicle-kit-debug-from-x86_64')
            utils.sbomnix('provenance', '.#packages.aarch64-linux.nxp-imx8mp-evk-debug')
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
            utils.sbomnix('sbomnix', '.#packages.x86_64-linux.lenovo-x1-carbon-gen11-debug-installer')
            utils.sbomnix('sbomnix', '.#packages.x86_64-linux.lenovo-x1-carbon-gen11-release')
            utils.sbomnix('sbomnix', '.#packages.x86_64-linux.lenovo-x1-carbon-gen11-release-installer')
            utils.sbomnix('sbomnix', '.#packages.x86_64-linux.microchip-icicle-kit-debug-from-x86_64')
            utils.sbomnix('sbomnix', '.#packages.aarch64-linux.nxp-imx8mp-evk-debug')
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
            utils.sbomnix('vulnxscan', '.#packages.x86_64-linux.lenovo-x1-carbon-gen11-debug-installer')
            utils.sbomnix('vulnxscan', '.#packages.x86_64-linux.lenovo-x1-carbon-gen11-release')
            utils.sbomnix('vulnxscan', '.#packages.x86_64-linux.lenovo-x1-carbon-gen11-release-installer')
            utils.sbomnix('vulnxscan', '.#packages.x86_64-linux.microchip-icicle-kit-debug-from-x86_64')
            utils.sbomnix('vulnxscan', '.#packages.aarch64-linux.nxp-imx8mp-evk-debug')
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
            jenkins_url = "https://ghaf-jenkins-controller-dev.northeurope.cloudapp.azure.com"
            testset = "_boot_bat_perf_"
            utils.ghaf_hw_test('.#packages.x86_64-linux.nvidia-jetson-orin-agx-debug-from-x86_64', 'orin-agx', jenkins_url, testset)
            utils.ghaf_hw_test('.#packages.aarch64-linux.nvidia-jetson-orin-agx-debug', 'orin-agx', jenkins_url, testset)
            utils.ghaf_hw_test('.#packages.x86_64-linux.nvidia-jetson-orin-nx-debug-from-x86_64', 'orin-nx', jenkins_url, testset)
            utils.ghaf_hw_test('.#packages.aarch64-linux.nvidia-jetson-orin-nx-debug', 'orin-nx', jenkins_url, testset)
            utils.ghaf_hw_test('.#packages.x86_64-linux.lenovo-x1-carbon-gen11-debug', 'lenovo-x1', jenkins_url, testset)
            utils.ghaf_hw_test('.#packages.x86_64-linux.generic-x86_64-debug', 'nuc', jenkins_url, testset)
            utils.ghaf_hw_test('.#packages.x86_64-linux.microchip-icicle-kit-debug-from-x86_64', 'riscv', jenkins_url, testset)
          }
        }
      }
    }
  }
}

////////////////////////////////////////////////////////////////////////////////

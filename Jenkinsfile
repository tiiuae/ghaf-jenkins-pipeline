#!/usr/bin/env groovy

// SPDX-FileCopyrightText: 2024 Technology Innovation Institute (TII)
//
// SPDX-License-Identifier: Apache-2.0

pipeline {
  agent any
  parameters {
    string name: 'URL', defaultValue: 'https://github.com/tiiuae/ghaf.git'
    string name: 'BRANCH', defaultValue: 'main'
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
  stages {
    stage('Checkout') {
      agent any
      steps {
        ws('ghaf') {
          checkout scmGit(
            branches: [[name: params.BRANCH]],
            extensions: [cleanBeforeCheckout()],
            userRemoteConfigs: [[url: params.URL]])
        }
      }
    }
    stage('Test targets') {
      matrix {
        axes {
          axis {
            name 'TARGET'
            values 'aarch64-linux.nvidia-jetson-orin-agx-debug',
            'aarch64-linux.nvidia-jetson-orin-nx-debug',
            'aarch64-linux.imx8qm-mek-debug',
            'aarch64-linux.doc',
            'riscv64-linux.microchip-icicle-kit-debug',
            'x86_64-linux.nvidia-jetson-orin-agx-debug-from-x86_64',
            'x86_64-linux.nvidia-jetson-orin-nx-debug-from-x86_64',
            'x86_64-linux.generic-x86_64-debug',
            'x86_64-linux.lenovo-x1-carbon-gen11-debug',
            'x86_64-linux.doc'
          }
        }
        stages {
          stage('Build') {
            agent any
            steps {
              ws('ghaf') {
                sh 'nix build -L .#packages.${TARGET} -o result-${TARGET}'
                archiveArtifacts allowEmptyArchive: true,
                  artifacts: "result-${TARGET}/**"
              }
            }
          }
          stage('Generate SBOM') {
            agent any
            steps {
              ws('ghaf') {
                sh 'mkdir -p result-sbom-${TARGET}'
                sh '''
                  nix run github:tiiuae/sbomnix#sbomnix -- \
                  --csv result-sbom-${TARGET}/sbom.csv \
                  --cdx result-sbom-${TARGET}/sbom.cdx.json \
                  --spdx result-sbom-${TARGET}/sbom.spdx.json \
                  .#packages.${TARGET}
                '''
                archiveArtifacts allowEmptyArchive: true,
                  artifacts: "result-sbom-${TARGET}/**"
              }
            }
          }
          stage('Another test') {
            agent any
            steps {
              sh 'echo Under Construction'
            }
          }
        }
      }
    }
  }
}

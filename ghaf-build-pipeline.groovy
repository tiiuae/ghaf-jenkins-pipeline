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
  triggers {
    pollSCM '* * * * *'
  }
  options {
    timestamps()
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
      steps {
        dir('ghaf') {
          checkout scmGit(
            branches: [[name: params.BRANCH]],
            extensions: [cleanBeforeCheckout()],
            userRemoteConfigs: [[url: params.URL]]
          )
        }
      }
    }
    stage('Build on x86_64') {
      steps {
        dir('ghaf') {
          sh 'nix build -L .#packages.x86_64-linux.nvidia-jetson-orin-agx-debug-from-x86_64 -o result-jetson-orin-agx-debug'
          sh 'nix build -L .#packages.x86_64-linux.nvidia-jetson-orin-nx-debug-from-x86_64  -o result-jetson-orin-nx-debug'
          sh 'nix build -L .#packages.x86_64-linux.generic-x86_64-debug                     -o result-generic-x86_64-debug'
          sh 'nix build -L .#packages.x86_64-linux.lenovo-x1-carbon-gen11-debug             -o result-lenovo-x1-carbon-gen11-debug'
          sh 'nix build -L .#packages.riscv64-linux.microchip-icicle-kit-debug              -o result-microchip-icicle-kit-debug'
          sh 'nix build -L .#packages.x86_64-linux.doc                                      -o result-doc'
        }
      }
    }
    stage('Build on aarch64') {
      steps {
        dir('ghaf') {
          sh 'nix build -L .#packages.aarch64-linux.nvidia-jetson-orin-agx-debug -o result-aarch64-jetson-orin-agx-debug'
          sh 'nix build -L .#packages.aarch64-linux.nvidia-jetson-orin-nx-debug  -o result-aarch64-jetson-orin-nx-debug'
          sh 'nix build -L .#packages.aarch64-linux.imx8qm-mek-debug             -o result-aarch64-imx8qm-mek-debug'
          sh 'nix build -L .#packages.aarch64-linux.doc                          -o result-aarch64-doc'
        }
      }
    }
  }
  post {
    always {
      archiveArtifacts allowEmptyArchive: true, artifacts: 'ghaf/result-*/**'
    }
  }
}

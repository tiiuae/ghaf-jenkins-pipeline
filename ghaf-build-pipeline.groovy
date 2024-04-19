#!/usr/bin/env groovy

// SPDX-FileCopyrightText: 2024 Technology Innovation Institute (TII)
//
// SPDX-License-Identifier: Apache-2.0

////////////////////////////////////////////////////////////////////////////////

def DEF_GHAF_REPO = 'https://github.com/tiiuae/ghaf'
def DEF_GITREF = 'main'
def DEF_REBASE = false

////////////////////////////////////////////////////////////////////////////////

properties([
  // Poll every minute
  pipelineTriggers([pollSCM('* * * * *')]),
  parameters([
    string(name: 'REPO', defaultValue: DEF_GHAF_REPO, description: 'Target Ghaf repository'),
    string(name: 'GITREF', defaultValue: DEF_GITREF, description: 'Target gitref (commit/branch/tag) to build'),
    booleanParam(name: 'REBASE', defaultValue: DEF_REBASE, description: 'Rebase on top of tiiuae/ghaf main'),
  ])
])

////////////////////////////////////////////////////////////////////////////////

pipeline {
  agent { label 'built-in' }
  options {
    timestamps ()
    buildDiscarder logRotator(
      artifactDaysToKeepStr: '7',
      artifactNumToKeepStr: '10',
      daysToKeepStr: '70',
      numToKeepStr: '100'
    )
  }
  environment {
    // https://stackoverflow.com/questions/46680573
    REPO = params.getOrDefault('REPO', DEF_GHAF_REPO)
    GITREF = params.getOrDefault('GITREF', DEF_GITREF)
    REBASE = params.getOrDefault('REBASE', DEF_REBASE)
  }
  stages {
    // Changes to the repo/branch configured here will trigger the pipeline
    stage('Configure target repo') {
      steps {
        script {
          SCM = git(url: DEF_GHAF_REPO, branch: DEF_GITREF)
        }
      }
    }
    stage('Build') {
      stages {
        stage('Checkout') {
          steps {
            sh 'rm -rf ghaf'
            sh 'git clone $REPO ghaf'
            dir('ghaf') {
              sh 'git checkout $GITREF'
            }
          }
        }
        stage('Rebase') {
          when { expression { env.REBASE == true || params.REBASE == true } }
          steps {
            dir('ghaf') {
              sh 'git config user.email "jenkins@demo.fi"'
              sh 'git config user.name "Jenkins"'
              sh 'git remote add tiiuae https://github.com/tiiuae/ghaf.git'
              sh 'git fetch tiiuae'
              sh 'git rebase tiiuae/main'
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
        stage('Post-Build Analysis') {
          parallel {
            stage('x64 Analysis') {
              steps {
                dir('ghaf') {
                  sh 'nix run github:tiiuae/sbomnix#sbomnix -- .#packages.x86_64-linux.nvidia-jetson-orin-agx-debug-from-x86_64 --csv result-jetson-orin-agx-debug.csv --cdx result-jetson-orin-agx-debug.cdx.json --spdx result-jetson-orin-agx-debug.spdx.json'
                  sh 'nix run github:tiiuae/sbomnix#sbomnix -- .#packages.x86_64-linux.nvidia-jetson-orin-nx-debug-from-x86_64 --csv result-jetson-orin-nx-debug.csv --cdx result-jetson-orin-nx-debug.cdx.json --spdx result-jetson-orin-nx-debug.json'
                  sh 'nix run github:tiiuae/sbomnix#sbomnix -- .#packages.x86_64-linux.generic-x86_64-debug  --csv result-generic-x86_64-debug.csv --cdx result-generic-x86_64-debugcdx.cdx.json --spdx result-generic-x86_64-debug.spdx.json'
                  sh 'nix run github:tiiuae/sbomnix#sbomnix -- .#packages.x86_64-linux.lenovo-x1-carbon-gen11-debug --csv result-lenovo-x1-carbon-gen11-debug.csv --cdx result-lenovo-x1-carbon-gen11-debug.cdx.json --spdx result-lenovo-x1-carbon-gen11-debug.spdx.json'
                  sh 'nix run github:tiiuae/sbomnix#sbomnix -- .#packages.riscv64-linux.microchip-icicle-kit-debug --csv result-microchip-icicle-kit-debug.csv --cdx result-microchip-icicle-kit-debug.cdx.json --spdx result-microchip-icicle-kit-debug.spdx.json'
                  sh 'nix run github:tiiuae/sbomnix#sbomnix -- .#packages.x86_64-linux.doc --csv result-doc.csv --cdx result-doc.cdx.json --spdx result-doc.spdx.json'
                }
              }
            }
            stage('aarch64 Analysis') {
              steps {
                dir('ghaf') {
                  sh 'nix run github:tiiuae/sbomnix#sbomnix -- .#packages.aarch64-linux.nvidia-jetson-orin-agx-debug --csv result-aarch64-jetson-orin-agx-debug.csv --cdx result-aarch64-jetson-orin-agx-debug.cdx.json --spdx result-aarch64-jetson-orin-agx-debug.spdx.json'
                  sh 'nix run github:tiiuae/sbomnix#sbomnix -- .#packages.aarch64-linux.nvidia-jetson-orin-nx-debug  --csv result-aarch64-jetson-orin-nx-debug.csv --cdx result-aarch64-jetson-orin-nx-debug.cdx.json --spdx result-aarch64-jetson-orin-nx-debug.json'
                  sh 'nix run github:tiiuae/sbomnix#sbomnix -- .#packages.aarch64-linux.imx8qm-mek-debug   --csv result-aarch64-imx8qm-mek-debug.csv --cdx result-aarch64-imx8qm-mek-debug.cdx.json --spdx result-aarch64-imx8qm-mek-debug.spdx.json'
                  sh 'nix run github:tiiuae/sbomnix#sbomnix -- .#packages.aarch64-linux.doc --csv result-aarch64-doc.csv --cdx result-aarch64-doc.cdx.json --spdx result-aarch64-doc.spdx.json'
                }
              }
            }            }
          }
        }
    }
  }
  post {
    always {
      script {
        def directoryPath = 'ghaf'
        def artifactsToArchive = sh(script: "find ${directoryPath} -type f -name 'result-*'", returnStdout: true).trim().split('\n')
          artifactsToArchive.each { artifact ->
            echo "Archiving artifact: ${artifact}"
              archiveArtifacts allowEmptyArchive: true, artifacts: "${artifact}"
            }
            echo 'Artifact archiving completed.'
      }
    }
  }
}

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
  options { timestamps () }
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
      }
    }
  }
}
#!/usr/bin/env groovy

// SPDX-FileCopyrightText: 2024 Technology Innovation Institute (TII)
//
// SPDX-License-Identifier: Apache-2.0

// Default values for parameters
DEFAULT_URL = 'https://github.com/tiiuae/ghaf.git'
DEFAULT_REF = 'main'
DEFAULT_SBOMNIX = 'a1f0f88d719687acedd989899ecd7fafab42394c'

// Parallel stages for targets under test
def tests = [:]

// Ghaf targets
def targets = [
  'aarch64-linux.nvidia-jetson-orin-agx-debug',
  'aarch64-linux.nvidia-jetson-orin-nx-debug'
]

targets.each {

  def target = "${it}"

  tests[target] = {

    stage("${target}") {

      node('built-in') {
        stage("Build ${target}") {
          dir(GHAF_PATH) {
            sh "date +%s > TS_BEGIN_${target}"
            sh "nix build -L .#packages.${target} -o result-${target}"
            sh "date +%s > TS_FINISHED_${target}"
            archiveArtifacts allowEmptyArchive: true,
              artifacts: "result-${target}/**"
          }
        }

        stage("Generate provenance for ${target}") {
          dir(GHAF_PATH) {
            sh """
              PROVENANCE_TIMESTAMP_BEGIN=\$(<TS_BEGIN_${target})
              PROVENANCE_TIMESTAMP_FINISHED=\$(<TS_FINISHED_${target})
              PROVENANCE_EXTERNAL_PARAMS=\$(jq -n \
                --arg repository $GHAF_URL \
                --arg ref $GHAF_REF \
                --arg target $target \
                '\$ARGS.named')
              PROVENANCE_INTERNAL_PARAMS=\$(jq -n \
                --arg agent $NODE_NAME \
                --arg ws $WORKSPACE \
                '\$ARGS.named')
              export PROVENANCE_TIMESTAMP_BEGIN
              export PROVENANCE_TIMESTAMP_FINISHED
              export PROVENANCE_EXTERNAL_PARAMS
              export PROVENANCE_INTERNAL_PARAMS
              mkdir -p result-provenance-${target}
              nix run github:tiiuae/sbomnix/${SBOMNIX_REF}#provenance -- \
                .#packages.${target} --recursive \
                --out result-provenance-${target}/provenance.json
            """
            archiveArtifacts allowEmptyArchive: true,
            artifacts: "result-provenance-${target}/**"
          }
        }

        stage("Generate SBOM for ${target}") {
          dir(GHAF_PATH) {
            sh "mkdir -p result-sbom-${target}"
            sh """
              nix run github:tiiuae/sbomnix/${SBOMNIX_REF}#sbomnix -- \
                --csv result-sbom-${target}/sbom.csv \
                --cdx result-sbom-${target}/sbom.cdx.json \
                --spdx result-sbom-${target}/sbom.spdx.json \
                .#packages.${target}
            """
            archiveArtifacts allowEmptyArchive: true,
            artifacts: "result-sbom-${target}/**"
          }
        }

        stage("Run vulnerability scan for ${target}") {
          dir(GHAF_PATH) {
            sh "mkdir -p result-vulnxscan-${target}"
            sh """
              nix run github:tiiuae/sbomnix/${SBOMNIX_REF}#vulnxscan -- \
                --out result-vulnxscan-${target}/vulns.csv \
                .#packages.${target}
            """
            archiveArtifacts allowEmptyArchive: true,
              artifacts: "result-vulnxscan-${target}/**"
          }
        }

      }
    }
  }
}

pipeline {
  agent none
  parameters {
    string description: 'Repository URL',
      name: 'URL',
      defaultValue: DEFAULT_URL
    string description: 'Branch (or revision reference) Specifier',
      name: 'BRANCH',
      defaultValue: DEFAULT_REF
    string description: 'sbomnix project revision',
      name: 'SBOMNIX',
      defaultValue: DEFAULT_SBOMNIX
  }
  triggers {
    pollSCM 'H 21 * * *'
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
    PROVENANCE_BUILD_TYPE = 'https://docs.cimon.build/provenance/buildtypes/jenkins/v1'
    PROVENANCE_BUILDER_ID = "$JENKINS_URL"
    PROVENANCE_INVOCATION_ID = "$BUILD_URL"
    GHAF_PATH = "$JENKINS_HOME/workspace/nightly-ghaf"

    // Use default values if parameter values are not yet defined
    // like the case on the very first run
    GHAF_URL = params.getOrDefault('URL', DEFAULT_URL)
    GHAF_REF = params.getOrDefault('BRACH', DEFAULT_REF)
    SBOMNIX_REF = params.getOrDefault('SBOMNIX', DEFAULT_SBOMNIX)
  }
  stages {
    stage('Checkout') {
      agent any
      steps {
        dir(GHAF_PATH) {
          checkout scmGit(
            branches: [[name: params.BRANCH]],
            extensions: [cleanBeforeCheckout()],
            userRemoteConfigs: [[url: params.URL]]
          )
        }
      }
    }
    stage('Test targets') {
      steps {
        script {
          parallel tests
        }
      }
    }
  }
}

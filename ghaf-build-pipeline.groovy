#!/usr/bin/env groovy

// SPDX-FileCopyrightText: 2024 Technology Innovation Institute (TII)
//
// SPDX-License-Identifier: Apache-2.0


def builds_x86_64=
//  NAME OF THE BUILD TARGET ,                             NAME OF THE BUILD OUTPUT,                    SBOM TARGET BASE NAME,                              NAME OF THE PROVENANCE OUTPUT
["x86_64-linux.nvidia-jetson-orin-agx-debug-from-x86_64", "result-crosscompile-jetson-orin-agx-debug",  "result-sbom-crosscompile-jetson-orin-agx-debug",   "result-provenance-jetson-orin-agx-debug",
"x86_64-linux.nvidia-jetson-orin-nx-debug-from-x86_64",   "result-crosscompile-jetson-orin-nx-debug",   "result-sbom-crosscompile-jetson-orin-nx-debug",    "result-provenance-jetson-orin-nx-debug",
"x86_64-linux.generic-x86_64-debug",                      "result-generic-x86_64-debug",                "result-generic-x86_64-debug",                      "result-provenance-generic-x86_64-debug",
"x86_64-linux.lenovo-x1-carbon-gen11-debug",              "result-lenovo-x1-carbon-gen11-debug",        "result-lenovo-x1-carbon-gen11-debug",              "result-provenance-lenovo-x1-carbon-gen11-debug",
"riscv64-linux.microchip-icicle-kit-debug",               "result-microchip-icicle-kit-debug",          "result-microchip-icicle-kit-debug",                "result-provenance-microchip-icicle-kit-debug",
"x86_64-linux.doc",                                       "result-doc",                                 "NA",                                               "NA"]

def builds_aarch_64=
//  NAME OF THE BUILD TARGET ,                             NAME OF THE BUILD OUTPUT                     SBOM TARGET BASE NAME,                              NAME OF THE PROVENANCE OUTPUT
["aarch64-linux.nvidia-jetson-orin-agx-debug",             "result-aarch64-jetson-orin-agx-debug",      "result-aarch64-jetson-orin-agx-debug",             "result-provenance-aarch64-jetson-orin-agx-debug",
"aarch64-linux.nvidia-jetson-orin-nx-debug",               "result-aarch64-jetson-orin-nx-debug",       "result-aarch64-jetson-orin-nx-debug",              "result-provenance-aarch64-jetson-orin-nx-debug",
"aarch64-linux.imx8qm-mek-debug",                          "result-aarch64-imx8qm-mek-debug",           "result-aarch64-imx8qm-mek-debug",                  "result-provenance-aarch64-imx8qm-mek-debug",
"aarch64-linux.doc",                                       "result-aarch64-doc",                        "NA",                                               "NA"]

def processBuilds(builds) {
    for (int i = 0; i < builds.size(); i += 4) {
        def buildConfig = builds[i]
        def buildTarget = builds[i + 1]
        sh "nix build -L .#packages.${buildConfig} -o result-${buildTarget}"
    }
}
def processSBOMs(builds) {
    for (int i = 0; i < builds.size(); i += 4) {
        def buildConfig = builds[i]
        def buildTarget = builds[i + 1]
        def buildSBOMName = builds[i + 2]
        if (buildSBOMName != "NA") {
          sh "nix run github:tiiuae/sbomnix/a1f0f88d719687acedd989899ecd7fafab42394c#sbomnix -- .#packages.${buildConfig} --csv ${buildSBOMName}.csv --cdx ${buildSBOMName}cdx.json --spdx ${buildSBOMName}.spdx.json"
        }
    }
}
def processProvenances(builds) {
    for (int i = 0; i < builds.size(); i += 4) {
        def buildConfig = builds[i]
        def buildTarget = builds[i + 1]
        def buildSBOMName = builds[i + 2]
        def buildProvenanceName = builds[i + 3]
        if (buildProvenanceName != "NA") {
          sh "nix run github:tiiuae/sbomnix/a1f0f88d719687acedd989899ecd7fafab42394c#provenance -- .#packages.${buildConfig} --recursive --out ${buildProvenanceName}.json"
        }
    }
}

///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////

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
        script {
          env.ts_build_begin = sh(script: 'date +%s', returnStdout: true).trim()
          dir('ghaf') {
            processBuilds(builds_x86_64)
            }
          }
        }
      }
    stage('Build on aarch64') {
      steps {
        dir('ghaf') {
          processBuilds(builds_aarch_64)
        }
        script {
          env.ts_build_finished = sh(script: 'date +%s', returnStdout: true).trim()
        }
      }
    }
    stage('Provenance') {
          environment {
            // TODO: Write our own buildtype and builder id documents
            PROVENANCE_BUILD_TYPE = "https://docs.cimon.build/provenance/buildtypes/jenkins/v1"
            PROVENANCE_BUILDER_ID = "https://github.com/tiiuae/ghaf-infra/tree/main/terraform"
            PROVENANCE_INVOCATION_ID = "${env.JOB_NAME}/${env.BUILD_ID}"
            PROVENANCE_TIMESTAMP_BEGIN = "${env.ts_build_begin}"
            PROVENANCE_TIMESTAMP_FINISHED = "${env.ts_build_finished}"
            PROVENANCE_EXTERNAL_PARAMS = sh(
              returnStdout: true,
              script: 'jq -n --arg flakeURI $URL --arg flakeBranch $BRANCH \'$ARGS.named\''
            )
            PROVENANCE_INTERNAL_PARAMS = sh(
              returnStdout: true,
              // returns the specified environment varibles in json format
              script: """
                jq -n env | jq "{ \
                  JOB_NAME, \
                  GIT_URL, \
                  GIT_BRANCH, \
                  GIT_COMMIT, \
                }"
              """
            )
          }
          steps {
            dir('ghaf') {
              processProvenances(builds_x86_64)
              processProvenances(builds_aarch_64)
            }
          }
        }
    stage('SBOM') {
      steps {
        dir('ghaf') {
          processSBOMs(builds_x86_64)
          processSBOMs(builds_aarch_64)
        }
      }
    }
    stage('Vulnxscan runtime') {
      steps {
        dir('ghaf') {
          sh 'nix run github:tiiuae/sbomnix/a1f0f88d719687acedd989899ecd7fafab42394c#vulnxscan -- .#packages.x86_64-linux.lenovo-x1-carbon-gen11-debug --out result-vulns-lenovo-x1-carbon-gen11-debug.csv'
          sh 'csvcut result-vulns-lenovo-x1-carbon-gen11-debug.csv --not-columns sortcol | csvlook -I > result-vulns-lenovo-x1-carbon-gen11-debug.txt'
        }
      }
    }
  }
  post {
    always {
      archiveArtifacts allowEmptyArchive: true, artifacts: "ghaf/result-*"
      archiveArtifacts allowEmptyArchive: true, artifacts: "ghaf/result-*/**"
      archiveArtifacts allowEmptyArchive: true, artifacts: "ghaf/result-aarch64*/**"
    }
  }
}

#!/usr/bin/env groovy

// SPDX-FileCopyrightText: 2024 Technology Innovation Institute (TII)
//
// SPDX-License-Identifier: Apache-2.0

// Default values for parameters
DEFAULT_URL = 'https://github.com/tiiuae/ghaf.git'
DEFAULT_REF = 'main'
DEFAULT_SBOMNIX = 'a1f0f88d719687acedd989899ecd7fafab42394c'

pipeline {
  agent any
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
        ws('workspace/ghaf-pipeline/ghaf') {
          checkout scmGit(
            branches: [[name: params.BRANCH]],
            extensions: [cleanBeforeCheckout()],
            userRemoteConfigs: [[url: params.URL]]
          )
        }
      }
    }
    stage('Test targets') {
      parallel {

        stage("AARCH64_AGX_DEBUG") {
          environment {
            GHAF_TARGET='aarch64-linux.nvidia-jetson-orin-agx-debug'
          }
          stages {
            stage("Build AARCH64_AGX_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'date +%s > TS_BEGIN_${GHAF_TARGET}'
                  sh 'nix build -L .#packages.${GHAF_TARGET} -o result-${GHAF_TARGET}'
                  sh 'date +%s > TS_FINISHED_${GHAF_TARGET}'
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-${GHAF_TARGET}/**"
                }
              }
            }
            stage("Generate provenance for AARCH64_AGX_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh '''
                    PROVENANCE_TIMESTAMP_BEGIN=$(<TS_BEGIN_${GHAF_TARGET})
                    PROVENANCE_TIMESTAMP_FINISHED=$(<TS_FINISHED_${GHAF_TARGET})
                    PROVENANCE_EXTERNAL_PARAMS=$(jq -n \
                      --arg repository $GHAF_URL \
                      --arg ref $GHAF_REF \
                      --arg target $GHAF_TARGET \
                      '$ARGS.named')
                    PROVENANCE_INTERNAL_PARAMS=$(jq -n \
                      --arg agent $NODE_NAME \
                      --arg ws $WORKSPACE \
                      '$ARGS.named')
                    export PROVENANCE_TIMESTAMP_BEGIN
                    export PROVENANCE_TIMESTAMP_FINISHED
                    export PROVENANCE_EXTERNAL_PARAMS
                    export PROVENANCE_INTERNAL_PARAMS
                    mkdir -p result-provenance-${GHAF_TARGET}
                    nix run github:tiiuae/sbomnix/${SBOMNIX_REF}#provenance -- \
                      .#packages.${GHAF_TARGET} --recursive \
                      --out result-provenance-${GHAF_TARGET}/provenance.json
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-provenance-${GHAF_TARGET}/**"
                }
              }
            }
            stage("Generate SBOM for AARCH64_AGX_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-sbom-${GHAF_TARGET}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX_REF}#sbomnix -- \
                    --csv result-sbom-${GHAF_TARGET}/sbom.csv \
                    --cdx result-sbom-${GHAF_TARGET}/sbom.cdx.json \
                    --spdx result-sbom-${GHAF_TARGET}/sbom.spdx.json \
                    .#packages.${GHAF_TARGET}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-sbom-${GHAF_TARGET}/**"
                }
              }
            }
            stage("Run vulnerability scan for AARCH64_AGX_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-vulnxscan-${GHAF_TARGET}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX_REF}#vulnxscan -- \
                    --out result-vulnxscan-${GHAF_TARGET}/vulns.csv \
                    .#packages.${GHAF_TARGET}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-vulnxscan-${GHAF_TARGET}/**"
                }
              }
            }
          }
        }

        stage("AARCH64_NX_DEBUG") {
          environment {
            GHAF_TARGET='aarch64-linux.nvidia-jetson-orin-nx-debug'
          }
          stages {
            stage("Build AARCH64_NX_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'date +%s > TS_BEGIN_${GHAF_TARGET}'
                  sh 'nix build -L .#packages.${GHAF_TARGET} -o result-${GHAF_TARGET}'
                  sh 'date +%s > TS_FINISHED_${GHAF_TARGET}'
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-${GHAF_TARGET}/**"
                }
              }
            }
            stage("Generate provenance for AARCH64_NX_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh '''
                    PROVENANCE_TIMESTAMP_BEGIN=$(<TS_BEGIN_${GHAF_TARGET})
                    PROVENANCE_TIMESTAMP_FINISHED=$(<TS_FINISHED_${GHAF_TARGET})
                    PROVENANCE_EXTERNAL_PARAMS=$(jq -n \
                      --arg repository $GHAF_URL \
                      --arg ref $GHAF_REF \
                      --arg target $GHAF_TARGET \
                      '$ARGS.named')
                    PROVENANCE_INTERNAL_PARAMS=$(jq -n \
                      --arg agent $NODE_NAME \
                      --arg ws $WORKSPACE \
                      '$ARGS.named')
                    export PROVENANCE_TIMESTAMP_BEGIN
                    export PROVENANCE_TIMESTAMP_FINISHED
                    export PROVENANCE_EXTERNAL_PARAMS
                    export PROVENANCE_INTERNAL_PARAMS
                    mkdir -p result-provenance-${GHAF_TARGET}
                    nix run github:tiiuae/sbomnix/${SBOMNIX_REF}#provenance -- \
                      .#packages.${GHAF_TARGET} --recursive \
                      --out result-provenance-${GHAF_TARGET}/provenance.json
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-provenance-${GHAF_TARGET}/**"
                }
              }
            }
            stage("Generate SBOM for AARCH64_NX_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-sbom-${GHAF_TARGET}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX_REF}#sbomnix -- \
                    --csv result-sbom-${GHAF_TARGET}/sbom.csv \
                    --cdx result-sbom-${GHAF_TARGET}/sbom.cdx.json \
                    --spdx result-sbom-${GHAF_TARGET}/sbom.spdx.json \
                    .#packages.${GHAF_TARGET}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-sbom-${GHAF_TARGET}/**"
                }
              }
            }
            stage("Run vulnerability scan for AARCH64_NX_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-vulnxscan-${GHAF_TARGET}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX_REF}#vulnxscan -- \
                    --out result-vulnxscan-${GHAF_TARGET}/vulns.csv \
                    .#packages.${GHAF_TARGET}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-vulnxscan-${GHAF_TARGET}/**"
                }
              }
            }
          }
        }

        stage("AARCH64_NX_RELEASE") {
          environment {
            GHAF_TARGET='aarch64-linux.nvidia-jetson-orin-nx-release'
          }
          stages {
            stage("Build AARCH64_NX_RELEASE") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'date +%s > TS_BEGIN_${GHAF_TARGET}'
                  sh 'nix build -L .#packages.${GHAF_TARGET} -o result-${GHAF_TARGET}'
                  sh 'date +%s > TS_FINISHED_${GHAF_TARGET}'
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-${GHAF_TARGET}/**"
                }
              }
            }
            stage("Generate provenance for AARCH64_NX_RELEASE") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh '''
                    PROVENANCE_TIMESTAMP_BEGIN=$(<TS_BEGIN_${GHAF_TARGET})
                    PROVENANCE_TIMESTAMP_FINISHED=$(<TS_FINISHED_${GHAF_TARGET})
                    PROVENANCE_EXTERNAL_PARAMS=$(jq -n \
                      --arg repository $GHAF_URL \
                      --arg ref $GHAF_REF \
                      --arg target $GHAF_TARGET \
                      '$ARGS.named')
                    PROVENANCE_INTERNAL_PARAMS=$(jq -n \
                      --arg agent $NODE_NAME \
                      --arg ws $WORKSPACE \
                      '$ARGS.named')
                    export PROVENANCE_TIMESTAMP_BEGIN
                    export PROVENANCE_TIMESTAMP_FINISHED
                    export PROVENANCE_EXTERNAL_PARAMS
                    export PROVENANCE_INTERNAL_PARAMS
                    mkdir -p result-provenance-${GHAF_TARGET}
                    nix run github:tiiuae/sbomnix/${SBOMNIX_REF}#provenance -- \
                      .#packages.${GHAF_TARGET} --recursive \
                      --out result-provenance-${GHAF_TARGET}/provenance.json
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-provenance-${GHAF_TARGET}/**"
                }
              }
            }
            stage("Generate SBOM for AARCH64_NX_RELEASE") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-sbom-${GHAF_TARGET}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX_REF}#sbomnix -- \
                    --csv result-sbom-${GHAF_TARGET}/sbom.csv \
                    --cdx result-sbom-${GHAF_TARGET}/sbom.cdx.json \
                    --spdx result-sbom-${GHAF_TARGET}/sbom.spdx.json \
                    .#packages.${GHAF_TARGET}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-sbom-${GHAF_TARGET}/**"
                }
              }
            }
            stage("Run vulnerability scan for AARCH64_NX_RELEASE") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-vulnxscan-${GHAF_TARGET}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX_REF}#vulnxscan -- \
                    --out result-vulnxscan-${GHAF_TARGET}/vulns.csv \
                    .#packages.${GHAF_TARGET}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-vulnxscan-${GHAF_TARGET}/**"
                }
              }
            }
          }
        }

        stage("RISCV64_ICICLE_KIT") {
          environment {
            GHAF_TARGET='riscv64-linux.microchip-icicle-kit-debug'
          }
          stages {
            stage("Build RISCV64_ICICLE_KIT") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'date +%s > TS_BEGIN_${GHAF_TARGET}'
                  sh 'nix build -L .#packages.${GHAF_TARGET} -o result-${GHAF_TARGET}'
                  sh 'date +%s > TS_FINISHED_${GHAF_TARGET}'
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-${GHAF_TARGET}/**"
                }
              }
            }
            stage("Generate provenance for RISCV64_ICICLE_KIT") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh '''
                    PROVENANCE_TIMESTAMP_BEGIN=$(<TS_BEGIN_${GHAF_TARGET})
                    PROVENANCE_TIMESTAMP_FINISHED=$(<TS_FINISHED_${GHAF_TARGET})
                    PROVENANCE_EXTERNAL_PARAMS=$(jq -n \
                      --arg repository $GHAF_URL \
                      --arg ref $GHAF_REF \
                      --arg target $GHAF_TARGET \
                      '$ARGS.named')
                    PROVENANCE_INTERNAL_PARAMS=$(jq -n \
                      --arg agent $NODE_NAME \
                      --arg ws $WORKSPACE \
                      '$ARGS.named')
                    export PROVENANCE_TIMESTAMP_BEGIN
                    export PROVENANCE_TIMESTAMP_FINISHED
                    export PROVENANCE_EXTERNAL_PARAMS
                    export PROVENANCE_INTERNAL_PARAMS
                    mkdir -p result-provenance-${GHAF_TARGET}
                    nix run github:tiiuae/sbomnix/${SBOMNIX_REF}#provenance -- \
                      .#packages.${GHAF_TARGET} --recursive \
                      --out result-provenance-${GHAF_TARGET}/provenance.json
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-provenance-${GHAF_TARGET}/**"
                }
              }
            }
            stage("Generate SBOM for RISCV64_ICICLE_KIT") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-sbom-${GHAF_TARGET}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX_REF}#sbomnix -- \
                    --csv result-sbom-${GHAF_TARGET}/sbom.csv \
                    --cdx result-sbom-${GHAF_TARGET}/sbom.cdx.json \
                    --spdx result-sbom-${GHAF_TARGET}/sbom.spdx.json \
                    .#packages.${GHAF_TARGET}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-sbom-${GHAF_TARGET}/**"
                }
              }
            }
            stage("Run vulnerability scan for RISCV64_ICICLE_KIT") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-vulnxscan-${GHAF_TARGET}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX_REF}#vulnxscan -- \
                    --out result-vulnxscan-${GHAF_TARGET}/vulns.csv \
                    .#packages.${GHAF_TARGET}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-vulnxscan-${GHAF_TARGET}/**"
                }
              }
            }
          }
        }

        stage("X86_64_AGX_DEBUG") {
          environment {
            GHAF_TARGET='x86_64-linux.nvidia-jetson-orin-agx-debug-from-x86_64'
          }
          stages {
            stage("Build X86_64_AGX_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'date +%s > TS_BEGIN_${GHAF_TARGET}'
                  sh 'nix build -L .#packages.${GHAF_TARGET} -o result-${GHAF_TARGET}'
                  sh 'date +%s > TS_FINISHED_${GHAF_TARGET}'
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-${GHAF_TARGET}/**"
                }
              }
            }
            stage("Generate provenance for X86_64_AGX_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh '''
                    PROVENANCE_TIMESTAMP_BEGIN=$(<TS_BEGIN_${GHAF_TARGET})
                    PROVENANCE_TIMESTAMP_FINISHED=$(<TS_FINISHED_${GHAF_TARGET})
                    PROVENANCE_EXTERNAL_PARAMS=$(jq -n \
                      --arg repository $GHAF_URL \
                      --arg ref $GHAF_REF \
                      --arg target $GHAF_TARGET \
                      '$ARGS.named')
                    PROVENANCE_INTERNAL_PARAMS=$(jq -n \
                      --arg agent $NODE_NAME \
                      --arg ws $WORKSPACE \
                      '$ARGS.named')
                    export PROVENANCE_TIMESTAMP_BEGIN
                    export PROVENANCE_TIMESTAMP_FINISHED
                    export PROVENANCE_EXTERNAL_PARAMS
                    export PROVENANCE_INTERNAL_PARAMS
                    mkdir -p result-provenance-${GHAF_TARGET}
                    nix run github:tiiuae/sbomnix/${SBOMNIX_REF}#provenance -- \
                      .#packages.${GHAF_TARGET} --recursive \
                      --out result-provenance-${GHAF_TARGET}/provenance.json
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-provenance-${GHAF_TARGET}/**"
                }
              }
            }
            stage("Generate SBOM for X86_64_AGX_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-sbom-${GHAF_TARGET}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX_REF}#sbomnix -- \
                    --csv result-sbom-${GHAF_TARGET}/sbom.csv \
                    --cdx result-sbom-${GHAF_TARGET}/sbom.cdx.json \
                    --spdx result-sbom-${GHAF_TARGET}/sbom.spdx.json \
                    .#packages.${GHAF_TARGET}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-sbom-${GHAF_TARGET}/**"
                }
              }
            }
            stage("Run vulnerability scan for X86_64_AGX_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-vulnxscan-${GHAF_TARGET}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX_REF}#vulnxscan -- \
                    --out result-vulnxscan-${GHAF_TARGET}/vulns.csv \
                    .#packages.${GHAF_TARGET}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-vulnxscan-${GHAF_TARGET}/**"
                }
              }
            }
          }
        }

        stage("X86_64_NX_DEBUG") {
          environment {
            GHAF_TARGET='x86_64-linux.nvidia-jetson-orin-nx-debug-from-x86_64'
          }
          stages {
            stage("Build X86_64_NX_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'date +%s > TS_BEGIN_${GHAF_TARGET}'
                  sh 'nix build -L .#packages.${GHAF_TARGET} -o result-${GHAF_TARGET}'
                  sh 'date +%s > TS_FINISHED_${GHAF_TARGET}'
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-${GHAF_TARGET}/**"
                }
              }
            }
            stage("Generate provenance for X86_64_NX_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh '''
                    PROVENANCE_TIMESTAMP_BEGIN=$(<TS_BEGIN_${GHAF_TARGET})
                    PROVENANCE_TIMESTAMP_FINISHED=$(<TS_FINISHED_${GHAF_TARGET})
                    PROVENANCE_EXTERNAL_PARAMS=$(jq -n \
                      --arg repository $GHAF_URL \
                      --arg ref $GHAF_REF \
                      --arg target $GHAF_TARGET \
                      '$ARGS.named')
                    PROVENANCE_INTERNAL_PARAMS=$(jq -n \
                      --arg agent $NODE_NAME \
                      --arg ws $WORKSPACE \
                      '$ARGS.named')
                    export PROVENANCE_TIMESTAMP_BEGIN
                    export PROVENANCE_TIMESTAMP_FINISHED
                    export PROVENANCE_EXTERNAL_PARAMS
                    export PROVENANCE_INTERNAL_PARAMS
                    mkdir -p result-provenance-${GHAF_TARGET}
                    nix run github:tiiuae/sbomnix/${SBOMNIX_REF}#provenance -- \
                      .#packages.${GHAF_TARGET} --recursive \
                      --out result-provenance-${GHAF_TARGET}/provenance.json
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-provenance-${GHAF_TARGET}/**"
                }
              }
            }
            stage("Generate SBOM for X86_64_NX_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-sbom-${GHAF_TARGET}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX_REF}#sbomnix -- \
                    --csv result-sbom-${GHAF_TARGET}/sbom.csv \
                    --cdx result-sbom-${GHAF_TARGET}/sbom.cdx.json \
                    --spdx result-sbom-${GHAF_TARGET}/sbom.spdx.json \
                    .#packages.${GHAF_TARGET}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-sbom-${GHAF_TARGET}/**"
                }
              }
            }
            stage("Run vulnerability scan for X86_64_NX_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-vulnxscan-${GHAF_TARGET}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX_REF}#vulnxscan -- \
                    --out result-vulnxscan-${GHAF_TARGET}/vulns.csv \
                    .#packages.${GHAF_TARGET}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-vulnxscan-${GHAF_TARGET}/**"
                }
              }
            }
          }
        }

        stage("X86_64_DEBUG") {
          environment {
            GHAF_TARGET='x86_64-linux.generic-x86_64-debug'
          }
          stages {
            stage("Build X86_64_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'date +%s > TS_BEGIN_${GHAF_TARGET}'
                  sh 'nix build -L .#packages.${GHAF_TARGET} -o result-${GHAF_TARGET}'
                  sh 'date +%s > TS_FINISHED_${GHAF_TARGET}'
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-${GHAF_TARGET}/**"
                }
              }
            }
            stage("Generate provenance for X86_64_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh '''
                    PROVENANCE_TIMESTAMP_BEGIN=$(<TS_BEGIN_${GHAF_TARGET})
                    PROVENANCE_TIMESTAMP_FINISHED=$(<TS_FINISHED_${GHAF_TARGET})
                    PROVENANCE_EXTERNAL_PARAMS=$(jq -n \
                      --arg repository $GHAF_URL \
                      --arg ref $GHAF_REF \
                      --arg target $GHAF_TARGET \
                      '$ARGS.named')
                    PROVENANCE_INTERNAL_PARAMS=$(jq -n \
                      --arg agent $NODE_NAME \
                      --arg ws $WORKSPACE \
                      '$ARGS.named')
                    export PROVENANCE_TIMESTAMP_BEGIN
                    export PROVENANCE_TIMESTAMP_FINISHED
                    export PROVENANCE_EXTERNAL_PARAMS
                    export PROVENANCE_INTERNAL_PARAMS
                    mkdir -p result-provenance-${GHAF_TARGET}
                    nix run github:tiiuae/sbomnix/${SBOMNIX_REF}#provenance -- \
                      .#packages.${GHAF_TARGET} --recursive \
                      --out result-provenance-${GHAF_TARGET}/provenance.json
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-provenance-${GHAF_TARGET}/**"
                }
              }
            }
            stage("Generate SBOM for X86_64_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-sbom-${GHAF_TARGET}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX_REF}#sbomnix -- \
                    --csv result-sbom-${GHAF_TARGET}/sbom.csv \
                    --cdx result-sbom-${GHAF_TARGET}/sbom.cdx.json \
                    --spdx result-sbom-${GHAF_TARGET}/sbom.spdx.json \
                    .#packages.${GHAF_TARGET}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-sbom-${GHAF_TARGET}/**"
                }
              }
            }
            stage("Run vulnerability scan for X86_64_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-vulnxscan-${GHAF_TARGET}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX_REF}#vulnxscan -- \
                    --out result-vulnxscan-${GHAF_TARGET}/vulns.csv \
                    .#packages.${GHAF_TARGET}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-vulnxscan-${GHAF_TARGET}/**"
                }
              }
            }
          }
        }

        stage("X86_64_GEN11_DEBUG") {
          environment {
            GHAF_TARGET='x86_64-linux.lenovo-x1-carbon-gen11-debug'
          }
          stages {
            stage("Build X86_64_GEN11_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'date +%s > TS_BEGIN_${GHAF_TARGET}'
                  sh 'nix build -L .#packages.${GHAF_TARGET} -o result-${GHAF_TARGET}'
                  sh 'date +%s > TS_FINISHED_${GHAF_TARGET}'
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-${GHAF_TARGET}/**"
                }
              }
            }
            stage("Generate provenance for X86_64_GEN11_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh '''
                    PROVENANCE_TIMESTAMP_BEGIN=$(<TS_BEGIN_${GHAF_TARGET})
                    PROVENANCE_TIMESTAMP_FINISHED=$(<TS_FINISHED_${GHAF_TARGET})
                    PROVENANCE_EXTERNAL_PARAMS=$(jq -n \
                      --arg repository $GHAF_URL \
                      --arg ref $GHAF_REF \
                      --arg target $GHAF_TARGET \
                      '$ARGS.named')
                    PROVENANCE_INTERNAL_PARAMS=$(jq -n \
                      --arg agent $NODE_NAME \
                      --arg ws $WORKSPACE \
                      '$ARGS.named')
                    export PROVENANCE_TIMESTAMP_BEGIN
                    export PROVENANCE_TIMESTAMP_FINISHED
                    export PROVENANCE_EXTERNAL_PARAMS
                    export PROVENANCE_INTERNAL_PARAMS
                    mkdir -p result-provenance-${GHAF_TARGET}
                    nix run github:tiiuae/sbomnix/${SBOMNIX_REF}#provenance -- \
                      .#packages.${GHAF_TARGET} --recursive \
                      --out result-provenance-${GHAF_TARGET}/provenance.json
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-provenance-${GHAF_TARGET}/**"
                }
              }
            }
            stage("Generate SBOM for X86_64_GEN11_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-sbom-${GHAF_TARGET}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX_REF}#sbomnix -- \
                    --csv result-sbom-${GHAF_TARGET}/sbom.csv \
                    --cdx result-sbom-${GHAF_TARGET}/sbom.cdx.json \
                    --spdx result-sbom-${GHAF_TARGET}/sbom.spdx.json \
                    .#packages.${GHAF_TARGET}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-sbom-${GHAF_TARGET}/**"
                }
              }
            }
            stage("Run vulnerability scan for X86_64_GEN11_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-vulnxscan-${GHAF_TARGET}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX_REF}#vulnxscan -- \
                    --out result-vulnxscan-${GHAF_TARGET}/vulns.csv \
                    .#packages.${GHAF_TARGET}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-vulnxscan-${GHAF_TARGET}/**"
                }
              }
            }
          }
        }

        stage("X86_64_GEN11_RELEASE") {
          environment {
            GHAF_TARGET='x86_64-linux.lenovo-x1-carbon-gen11-release'
          }
          stages {
            stage("Build X86_64_GEN11_RELEASE") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'date +%s > TS_BEGIN_${GHAF_TARGET}'
                  sh 'nix build -L .#packages.${GHAF_TARGET} -o result-${GHAF_TARGET}'
                  sh 'date +%s > TS_FINISHED_${GHAF_TARGET}'
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-${GHAF_TARGET}/**"
                }
              }
            }
            stage("Generate provenance for X86_64_GEN11_RELEASE") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh '''
                    PROVENANCE_TIMESTAMP_BEGIN=$(<TS_BEGIN_${GHAF_TARGET})
                    PROVENANCE_TIMESTAMP_FINISHED=$(<TS_FINISHED_${GHAF_TARGET})
                    PROVENANCE_EXTERNAL_PARAMS=$(jq -n \
                      --arg repository $GHAF_URL \
                      --arg ref $GHAF_REF \
                      --arg target $GHAF_TARGET \
                      '$ARGS.named')
                    PROVENANCE_INTERNAL_PARAMS=$(jq -n \
                      --arg agent $NODE_NAME \
                      --arg ws $WORKSPACE \
                      '$ARGS.named')
                    export PROVENANCE_TIMESTAMP_BEGIN
                    export PROVENANCE_TIMESTAMP_FINISHED
                    export PROVENANCE_EXTERNAL_PARAMS
                    export PROVENANCE_INTERNAL_PARAMS
                    mkdir -p result-provenance-${GHAF_TARGET}
                    nix run github:tiiuae/sbomnix/${SBOMNIX_REF}#provenance -- \
                      .#packages.${GHAF_TARGET} --recursive \
                      --out result-provenance-${GHAF_TARGET}/provenance.json
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-provenance-${GHAF_TARGET}/**"
                }
              }
            }
            stage("Generate SBOM for X86_64_GEN11_RELEASE") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-sbom-${GHAF_TARGET}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX_REF}#sbomnix -- \
                    --csv result-sbom-${GHAF_TARGET}/sbom.csv \
                    --cdx result-sbom-${GHAF_TARGET}/sbom.cdx.json \
                    --spdx result-sbom-${GHAF_TARGET}/sbom.spdx.json \
                    .#packages.${GHAF_TARGET}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-sbom-${GHAF_TARGET}/**"
                }
              }
            }
            stage("Run vulnerability scan for X86_64_GEN11_RELEASE") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-vulnxscan-${GHAF_TARGET}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX_REF}#vulnxscan -- \
                    --out result-vulnxscan-${GHAF_TARGET}/vulns.csv \
                    .#packages.${GHAF_TARGET}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-vulnxscan-${GHAF_TARGET}/**"
                }
              }
            }
          }
        }

      }
    }
  }
}

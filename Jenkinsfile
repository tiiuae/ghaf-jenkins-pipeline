#!/usr/bin/env groovy

// SPDX-FileCopyrightText: 2024 Technology Innovation Institute (TII)
//
// SPDX-License-Identifier: Apache-2.0

pipeline {
  agent any
  parameters {
    string name: 'URL',
      defaultValue: 'https://github.com/tiiuae/ghaf.git'
    string name: 'BRANCH',
      defaultValue: 'main'
    string name: 'SBOMNIX',
      defaultValue: 'a1f0f88d719687acedd989899ecd7fafab42394c',
      description: 'sbomnix revision'
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
    AARCH64_AGX_DEBUG  = 'aarch64-linux.nvidia-jetson-orin-agx-debug'
    AARCH64_NX_DEBUG   = 'aarch64-linux.nvidia-jetson-orin-nx-debug'
    AARCH64_MEK_DEBUG  = 'aarch64-linux.imx8qm-mek-debug'
    AARCH64_DOC        = 'aarch64-linux.doc'
    RISCV64_ICICLE_KIT = 'riscv64-linux.microchip-icicle-kit-debug'
    X86_64_AGX_DEBUG   = 'x86_64-linux.nvidia-jetson-orin-agx-debug-from-x86_64'
    X86_64_NX_DEBUG    = 'x86_64-linux.nvidia-jetson-orin-nx-debug-from-x86_64'
    X86_64_DEBUG       = 'x86_64-linux.generic-x86_64-debug'
    X86_64_GEN11_DEBUG = 'x86_64-linux.lenovo-x1-carbon-gen11-debug'
    X86_64_DOC         = 'x86_64-linux.doc'
  }
  stages {
    stage('Checkout') {
      agent any
      steps {
        ws('workspace/ghaf-pipeline/ghaf') {
          checkout scmGit(
            branches: [[name: params.BRANCH]],
            extensions: [cleanBeforeCheckout()],
            userRemoteConfigs: [[url: params.URL]])
        }
      }
    }
    stage('Test targets') {
      parallel {

        stage("AARCH64_AGX_DEBUG") {
          stages {
            stage("Build AARCH64_AGX_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'nix build -L .#packages.${AARCH64_AGX_DEBUG} -o result-${AARCH64_AGX_DEBUG}'
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-${AARCH64_AGX_DEBUG}/**"
                }
              }
            }
            stage("Generate SBOM for AARCH64_AGX_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-sbom-${AARCH64_AGX_DEBUG}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX}#sbomnix -- \
                    --csv result-sbom-${AARCH64_AGX_DEBUG}/sbom.csv \
                    --cdx result-sbom-${AARCH64_AGX_DEBUG}/sbom.cdx.json \
                    --spdx result-sbom-${AARCH64_AGX_DEBUG}/sbom.spdx.json \
                    .#packages.${AARCH64_AGX_DEBUG}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-sbom-${AARCH64_AGX_DEBUG}/**"
                }
              }
            }
            stage("Run vulnerability scan for AARCH64_AGX_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-vulnxscan-${AARCH64_AGX_DEBUG}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX}#vulnxscan -- \
                    --out result-vulnxscan-${AARCH64_AGX_DEBUG}/vulns.csv \
                    .#packages.${AARCH64_AGX_DEBUG}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-vulnxscan-${AARCH64_AGX_DEBUG}/**"
                }
              }
            }
          }
        }

        stage("AARCH64_NX_DEBUG") {
          stages {
            stage("Build AARCH64_NX_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'nix build -L .#packages.${AARCH64_NX_DEBUG} -o result-${AARCH64_NX_DEBUG}'
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-${AARCH64_NX_DEBUG}/**"
                }
              }
            }
            stage("Generate SBOM for AARCH64_NX_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-sbom-${AARCH64_NX_DEBUG}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX}#sbomnix -- \
                    --csv result-sbom-${AARCH64_NX_DEBUG}/sbom.csv \
                    --cdx result-sbom-${AARCH64_NX_DEBUG}/sbom.cdx.json \
                    --spdx result-sbom-${AARCH64_NX_DEBUG}/sbom.spdx.json \
                    .#packages.${AARCH64_NX_DEBUG}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-sbom-${AARCH64_NX_DEBUG}/**"
                }
              }
            }
            stage("Run vulnerability scan for AARCH64_NX_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-vulnxscan-${AARCH64_NX_DEBUG}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX}#vulnxscan -- \
                    --out result-vulnxscan-${AARCH64_NX_DEBUG}/vulns.csv \
                    .#packages.${AARCH64_NX_DEBUG}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-vulnxscan-${AARCH64_NX_DEBUG}/**"
                }
              }
            }
          }
        }

        stage("AARCH64_MEK_DEBUG") {
          stages {
            stage("Build AARCH64_MEK_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'nix build -L .#packages.${AARCH64_MEK_DEBUG} -o result-${AARCH64_MEK_DEBUG}'
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-${AARCH64_MEK_DEBUG}/**"
                }
              }
            }
            stage("Generate SBOM for AARCH64_MEK_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-sbom-${AARCH64_MEK_DEBUG}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX}#sbomnix -- \
                    --csv result-sbom-${AARCH64_MEK_DEBUG}/sbom.csv \
                    --cdx result-sbom-${AARCH64_MEK_DEBUG}/sbom.cdx.json \
                    --spdx result-sbom-${AARCH64_MEK_DEBUG}/sbom.spdx.json \
                    .#packages.${AARCH64_MEK_DEBUG}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-sbom-${AARCH64_MEK_DEBUG}/**"
                }
              }
            }
            stage("Run vulnerability scan for AARCH64_MEK_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-vulnxscan-${AARCH64_MEK_DEBUG}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX}#vulnxscan -- \
                    --out result-vulnxscan-${AARCH64_MEK_DEBUG}/vulns.csv \
                    .#packages.${AARCH64_MEK_DEBUG}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-vulnxscan-${AARCH64_MEK_DEBUG}/**"
                }
              }
            }
          }
        }

        stage("AARCH64_DOC") {
          stages {
            stage("Build AARCH64_DOC") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'nix build -L .#packages.${AARCH64_DOC} -o result-${AARCH64_DOC}'
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-${AARCH64_DOC}/**"
                }
              }
            }
            stage("Generate SBOM for AARCH64_DOC") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-sbom-${AARCH64_DOC}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX}#sbomnix -- \
                    --csv result-sbom-${AARCH64_DOC}/sbom.csv \
                    --cdx result-sbom-${AARCH64_DOC}/sbom.cdx.json \
                    --spdx result-sbom-${AARCH64_DOC}/sbom.spdx.json \
                    .#packages.${AARCH64_DOC}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-sbom-${AARCH64_DOC}/**"
                }
              }
            }
            stage("Run vulnerability scan for AARCH64_DOC") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-vulnxscan-${AARCH64_DOC}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX}#vulnxscan -- \
                    --out result-vulnxscan-${AARCH64_DOC}/vulns.csv \
                    .#packages.${AARCH64_DOC}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-vulnxscan-${AARCH64_DOC}/**"
                }
              }
            }
          }
        }

        stage("RISCV64_ICICLE_KIT") {
          stages {
            stage("Build RISCV64_ICICLE_KIT") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'nix build -L .#packages.${RISCV64_ICICLE_KIT} -o result-${RISCV64_ICICLE_KIT}'
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-${RISCV64_ICICLE_KIT}/**"
                }
              }
            }
            stage("Generate SBOM for RISCV64_ICICLE_KIT") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-sbom-${RISCV64_ICICLE_KIT}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX}#sbomnix -- \
                    --csv result-sbom-${RISCV64_ICICLE_KIT}/sbom.csv \
                    --cdx result-sbom-${RISCV64_ICICLE_KIT}/sbom.cdx.json \
                    --spdx result-sbom-${RISCV64_ICICLE_KIT}/sbom.spdx.json \
                    .#packages.${RISCV64_ICICLE_KIT}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-sbom-${RISCV64_ICICLE_KIT}/**"
                }
              }
            }
            stage("Run vulnerability scan for RISCV64_ICICLE_KIT") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-vulnxscan-${RISCV64_ICICLE_KIT}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX}#vulnxscan -- \
                    --out result-vulnxscan-${RISCV64_ICICLE_KIT}/vulns.csv \
                    .#packages.${RISCV64_ICICLE_KIT}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-vulnxscan-${RISCV64_ICICLE_KIT}/**"
                }
              }
            }
          }
        }

        stage("X86_64_AGX_DEBUG") {
          stages {
            stage("Build X86_64_AGX_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'nix build -L .#packages.${X86_64_AGX_DEBUG} -o result-${X86_64_AGX_DEBUG}'
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-${X86_64_AGX_DEBUG}/**"
                }
              }
            }
            stage("Generate SBOM for X86_64_AGX_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-sbom-${X86_64_AGX_DEBUG}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX}#sbomnix -- \
                    --csv result-sbom-${X86_64_AGX_DEBUG}/sbom.csv \
                    --cdx result-sbom-${X86_64_AGX_DEBUG}/sbom.cdx.json \
                    --spdx result-sbom-${X86_64_AGX_DEBUG}/sbom.spdx.json \
                    .#packages.${X86_64_AGX_DEBUG}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-sbom-${X86_64_AGX_DEBUG}/**"
                }
              }
            }
            stage("Run vulnerability scan for X86_64_AGX_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-vulnxscan-${X86_64_AGX_DEBUG}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX}#vulnxscan -- \
                    --out result-vulnxscan-${X86_64_AGX_DEBUG}/vulns.csv \
                    .#packages.${X86_64_AGX_DEBUG}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-vulnxscan-${X86_64_AGX_DEBUG}/**"
                }
              }
            }
          }
        }

        stage("X86_64_NX_DEBUG") {
          stages {
            stage("Build X86_64_NX_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'nix build -L .#packages.${X86_64_NX_DEBUG} -o result-${X86_64_NX_DEBUG}'
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-${X86_64_NX_DEBUG}/**"
                }
              }
            }
            stage("Generate SBOM for X86_64_NX_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-sbom-${X86_64_NX_DEBUG}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX}#sbomnix -- \
                    --csv result-sbom-${X86_64_NX_DEBUG}/sbom.csv \
                    --cdx result-sbom-${X86_64_NX_DEBUG}/sbom.cdx.json \
                    --spdx result-sbom-${X86_64_NX_DEBUG}/sbom.spdx.json \
                    .#packages.${X86_64_NX_DEBUG}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-sbom-${X86_64_NX_DEBUG}/**"
                }
              }
            }
            stage("Run vulnerability scan for X86_64_NX_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-vulnxscan-${X86_64_NX_DEBUG}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX}#vulnxscan -- \
                    --out result-vulnxscan-${X86_64_NX_DEBUG}/vulns.csv \
                    .#packages.${X86_64_NX_DEBUG}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-vulnxscan-${X86_64_NX_DEBUG}/**"
                }
              }
            }
          }
        }

        stage("X86_64_DEBUG") {
          stages {
            stage("Build X86_64_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'nix build -L .#packages.${X86_64_DEBUG} -o result-${X86_64_DEBUG}'
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-${X86_64_DEBUG}/**"
                }
              }
            }
            stage("Generate SBOM for X86_64_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-sbom-${X86_64_DEBUG}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX}#sbomnix -- \
                    --csv result-sbom-${X86_64_DEBUG}/sbom.csv \
                    --cdx result-sbom-${X86_64_DEBUG}/sbom.cdx.json \
                    --spdx result-sbom-${X86_64_DEBUG}/sbom.spdx.json \
                    .#packages.${X86_64_DEBUG}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-sbom-${X86_64_DEBUG}/**"
                }
              }
            }
            stage("Run vulnerability scan for X86_64_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-vulnxscan-${X86_64_DEBUG}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX}#vulnxscan -- \
                    --out result-vulnxscan-${X86_64_DEBUG}/vulns.csv \
                    .#packages.${X86_64_DEBUG}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-vulnxscan-${X86_64_DEBUG}/**"
                }
              }
            }
          }
        }

        stage("X86_64_GEN11_DEBUG") {
          stages {
            stage("Build X86_64_GEN11_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'nix build -L .#packages.${X86_64_GEN11_DEBUG} -o result-${X86_64_GEN11_DEBUG}'
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-${X86_64_GEN11_DEBUG}/**"
                }
              }
            }
            stage("Generate SBOM for X86_64_GEN11_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-sbom-${X86_64_GEN11_DEBUG}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX}#sbomnix -- \
                    --csv result-sbom-${X86_64_GEN11_DEBUG}/sbom.csv \
                    --cdx result-sbom-${X86_64_GEN11_DEBUG}/sbom.cdx.json \
                    --spdx result-sbom-${X86_64_GEN11_DEBUG}/sbom.spdx.json \
                    .#packages.${X86_64_GEN11_DEBUG}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-sbom-${X86_64_GEN11_DEBUG}/**"
                }
              }
            }
            stage("Run vulnerability scan for X86_64_GEN11_DEBUG") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-vulnxscan-${X86_64_GEN11_DEBUG}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX}#vulnxscan -- \
                    --out result-vulnxscan-${X86_64_GEN11_DEBUG}/vulns.csv \
                    .#packages.${X86_64_GEN11_DEBUG}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-vulnxscan-${X86_64_GEN11_DEBUG}/**"
                }
              }
            }
          }
        }

        stage("X86_64_DOC") {
          stages {
            stage("Build X86_64_DOC") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'nix build -L .#packages.${X86_64_DOC} -o result-${X86_64_DOC}'
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-${X86_64_DOC}/**"
                }
              }
            }
            stage("Generate SBOM for X86_64_DOC") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-sbom-${X86_64_DOC}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX}#sbomnix -- \
                    --csv result-sbom-${X86_64_DOC}/sbom.csv \
                    --cdx result-sbom-${X86_64_DOC}/sbom.cdx.json \
                    --spdx result-sbom-${X86_64_DOC}/sbom.spdx.json \
                    .#packages.${X86_64_DOC}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-sbom-${X86_64_DOC}/**"
                }
              }
            }
            stage("Run vulnerability scan for X86_64_DOC") {
              agent any
              steps {
                ws('workspace/ghaf-pipeline/ghaf') {
                  sh 'mkdir -p result-vulnxscan-${X86_64_DOC}'
                  sh '''
                    nix run github:tiiuae/sbomnix/${SBOMNIX}#vulnxscan -- \
                    --out result-vulnxscan-${X86_64_DOC}/vulns.csv \
                    .#packages.${X86_64_DOC}
                  '''
                  archiveArtifacts allowEmptyArchive: true,
                    artifacts: "result-vulnxscan-${X86_64_DOC}/**"
                }
              }
            }
          }
        }

      }
    }
  }
}

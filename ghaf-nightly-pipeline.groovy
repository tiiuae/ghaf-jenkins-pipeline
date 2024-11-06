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

def targets = [
  // docs
  [ system: "x86_64-linux", target: "doc",
    archive: false
  ],
  [ system: "aarch64-linux", target: "doc",
    archive: false
  ],

  // lenovo x1
  [ system: "x86_64-linux", target: "lenovo-x1-carbon-gen11-debug",
    archive: true, scs: true, hwtest_device: "lenovo-x1"
  ],
  [ system: "x86_64-linux", target: "lenovo-x1-carbon-gen11-debug-installer",
    archive: true, scs: true
  ],
  [ system: "x86_64-linux", target: "lenovo-x1-carbon-gen11-release",
    archive: true, scs: true
  ],
  [ system: "x86_64-linux", target: "lenovo-x1-carbon-gen11-release-installer",
    archive: true, scs: true
  ],

  // nvidia orin
  [ system: "aarch64-linux", target: "nvidia-jetson-orin-agx-debug",
    archive: true, scs: true, hwtest_device: "orin-agx"
  ],
  [ system: "aarch64-linux", target: "nvidia-jetson-orin-nx-debug",
    archive: true, scs: true, hwtest_device: "orin-nx"
  ],
  [ system: "x86_64-linux", target: "nvidia-jetson-orin-agx-debug-from-x86_64",
    archive: true, scs: true, hwtest_device: "orin-agx"
  ],
  [ system: "x86_64-linux", target: "nvidia-jetson-orin-nx-debug-from-x86_64",
    archive: true, scs: true, hwtest_device: "orin-nx"
  ],

  // others
  [ system: "x86_64-linux", target: "generic-x86_64-debug",
    archive: true, hwtest_device: "nuc"
  ],
  [ system: "x86_64-linux", target: "microchip-icicle-kit-debug-from-x86_64",
    archive: true, scs: true, hwtest_device: "riscv"
  ],
  [ system: "x86_64-linux", target: "nxp-imx8mp-evk-debug",
    archive: true, scs: true
  ], 
]

hydrajobs_targets = [
  // nvidia orin with bpmp enabled
  [ system: "aarch64-linux",target: "nvidia-jetson-orin-agx-debug-bpmp", 
    archive: true
  ], 
  [ system: "aarch64-linux",target: "nvidia-jetson-orin-nx-debug-bpmp", 
    archive: true
  ], 
  [ system: "x86_64-linux", target: "nvidia-jetson-orin-agx-debug-bpmp-from-x86_64", 
    archive: true
  ], 
  [ system: "x86_64-linux", target: "nvidia-jetson-orin-nx-debug-bpmp-from-x86_64", 
    archive: true
  ], 
]

target_jobs = [:]

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

    stage('Evaluate') {
      steps {
        dir(WORKDIR) {
          script {
            utils.nix_eval_hydrajobs(hydrajobs_targets)
            return
            // targets = utils.nix_eval_jobs(targets, "packages")
            // targets = targets + utils.nix_eval_jobs(hydrajobs_targets, "hydraJobs")

            targets.each {
              def timestampBegin = ""
              def timestampEnd = ""
              def scsdir = "scs/${target}/scs"
              def target = it.system + "." + it.target

              target_jobs["${it.target} (${it.system})"] = {
                stage("Build ${target}") {
                  def opts = ""
                  if (it.archive) { 
                    opts = "--out-link archive/${target}"
                  } else {
                    opts = "--no-link"
                  }
                  try {
                    if (drvPath) {
                      timestampBegin = sh(script: "date +%s", returnStdout: true).trim()
                      sh "nix build -L ${drvPath}\\^* ${opts}"
                      timestampEnd = sh(script: "date +%s", returnStdout: true).trim()

                      // only attempt signing if there is something to sign
                      if (it.archive) {
                        def img_relpath = find_img_relpath(target, "archive")
                        sign_file("archive/${img_relpath}", "sig/${img_relpath}.sig", "INT-Ghaf-Devenv-Image")
                      };
                    } else {
                      error("Target \"${target}\" was not found in ${flakeAttr}")
                    }
                  } catch (InterruptedException e) {
                    throw e
                  } catch (Exception e) {
                    unstable("FAILED: ${target}")
                    currentBuild.result = "FAILURE"
                    println "Error: ${e.toString()}"
                  }
                }

                if (scs) {
                  stage("Provenance (${target})") {
                    def externalParams = """
                      {
                        "target": {
                          "name": "#packages.${target}",
                          "repository": "${env.TARGET_REPO}",
                          "ref": "${env.TARGET_COMMIT}"
                        },
                        "workflow": {
                          "name": "${env.JOB_NAME}",
                          "repository": "${env.GIT_URL}",
                          "ref": "${env.GIT_COMMIT}"
                        },
                        "job": "${env.JOB_NAME}",
                        "jobParams": ${JsonOutput.toJson(params)},
                        "buildRun": "${env.BUILD_ID}" 
                      }
                    """
                    // this environment block is only valid for the scope of this stage,
                    // preventing timestamp collision when provenances are built in parallel
                    withEnv([
                      'PROVENANCE_BUILD_TYPE="https://github.com/tiiuae/ghaf-infra/blob/ea938e90/slsa/v1.0/L1/buildtype.md"',
                      "PROVENANCE_BUILDER_ID=${env.JENKINS_URL}",
                      "PROVENANCE_INVOCATION_ID=${env.BUILD_URL}",
                      "PROVENANCE_TIMESTAMP_BEGIN=${timestampBegin}",
                      "PROVENANCE_TIMESTAMP_FINISHED=${timestampEnd}",
                      "PROVENANCE_EXTERNAL_PARAMS=${externalParams}"
                    ]) {
                      catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                        def outpath = "${scsdir}/provenance.json"
                        sh """
                          mkdir -p ${scsdir}
                          sh "provenance ${drvPath} --recursive --out ${outpath} 
                        """
                        sign_file(outpath, "sig/${outpath}.sig", "INT-Ghaf-Devenv-Provenance")
                      }
                    }
                  }

                  stage("SBOM (${target})") {
                    catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                      sh """
                        mkdir -p ${scsdir}
                        cd ${scsdir}
                        sbomnix ${drvPath}
                        cd ..
                      """
                    }
                  }

                  stage("Vulnxscan (${target})") {
                    catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                      sh """
                        mkdir -p ${scsdir}
                        vulnxscan ${drvPath} --out vulns.csv
                        csvcut vulns.csv --not-columns sortcol | csvlook -I >${scsdir}/vulns.txt
                      """
                    }
                  }
                }

                if (it.archive) {
                  stage("Archive ${target}") {
                    script {
                      archive_artifacts("archive", target)
                      archive_artifacts("sig", target)
                      if (it.scs) {
                        archive_artifacts("scs", target)
                      }
                    }
                  }
                }

                if (it.hwtest_device != null) {
                  stage("Test ${target}") {
                    script {
                      ghaf_parallel_hw_test(target, it.hwtest_device, '_boot_bat_perf_')
                    }
                  }
                }
              }
            }
          }
        }
      }
    }

    stage('Build targets') {
      steps {
        script {
          parallel target_jobs
        }
      }
    }
  }
}

////////////////////////////////////////////////////////////////////////////////

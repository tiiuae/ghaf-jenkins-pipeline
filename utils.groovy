#!/usr/bin/env groovy

// SPDX-FileCopyrightText: 2022-2024 TII (SSRC) and the Ghaf contributors
// SPDX-License-Identifier: Apache-2.0

import groovy.json.JsonOutput

////////////////////////////////////////////////////////////////////////////////

def flakeref_trim(String flakeref) {
  // Trim the flakeref so it can be used in artifacts storage URL:
  // Examples:
  //   .#packages.x86_64-linux.doc    ==> packages.x86_64-linux.doc
  //   .#hydraJobs.doc.x86_64-linux   ==> hydraJobs.doc.x86_64-linux
  //   .#doc                          ==> doc
  //   github:tiiuae/ghaf#doc         ==> doc
  //   doc                            ==> doc
  trimmed = "${flakeref.replaceAll(/^.*#/,"")}"
  trimmed = "${trimmed.replaceAll(/^\s*\.*/,"")}"
  // Replace any remaining non-whitelisted characters with '_':
  return "${trimmed.replaceAll(/[^a-zA-Z0-9-_.]/,"_")}"
}

def run_rclone(String opts) {
  sh """
    rclone ${opts} \
        "--webdav-unix-socket" "/run/rclone-jenkins-artifacts.sock" \
        "--webdav-url" "http://localhost"
  """
}

def archive_artifacts(String subdir, String target="") {
  if (!subdir) {
    println "Warning: skipping archive, subdir not set"
    return
  }
  // Archive artifacts to env.ARTIFACTS_REMOTE_PATH
  if (!env.ARTIFACTS_REMOTE_PATH) {
    println "Warning: skipping archive, ARTIFACTS_REMOTE_PATH not set"
    return
  }
  run_rclone("copy -L ${subdir}/${target} :webdav:/${env.ARTIFACTS_REMOTE_PATH}/${target}")
  // Add a link to Artifacts on the build description if it isn't added yet
  href = "/artifacts/${env.ARTIFACTS_REMOTE_PATH}/"
  artifacts_anchor = "<a href=\"${href}\">📦 Artifacts</a>"
  if (!currentBuild.description) {
    // Set the description if it wasn't set earlier
    currentBuild.description = "${artifacts_anchor}"
  } else if (!currentBuild.description.contains(" Artifacts</a>")) {
    // If the description is set, but does not contain the Artifacts link
    // yet, place the Artifacts link on the top of the description
    currentBuild.description = "${artifacts_anchor}${currentBuild.description}"
  }
}

def purge_artifacts(String remote_path) {
  if (!remote_path) {
    println "Warning: skipping artifacts purge, remote_path not set"
    return
  }
  run_rclone("purge :webdav:/${remote_path}")
}

def purge_artifacts_by_age(String remote_path, String age) {
  if (!remote_path) {
    println "Warning: skipping artifacts delete, remote_path not set"
    return
  }
  // remove artifact files by given age
  run_rclone("delete :webdav:/${remote_path} --min-age ${age} --include '**/*'")
  // remove empty directories
  run_rclone("rmdirs :webdav:/stash --leave-root")

}

def nix_build(String flakeref, String subdir=null) {
  try {
    flakeref_trimmed = "${flakeref_trim(flakeref)}"
    // Produce build out-links only if subdir was specified
    if (!subdir) {
      opts = "--no-link"
    } else {
      opts = "--out-link ${subdir}/${flakeref_trimmed}"
    }
    // Store the build start time to job's environment
    epoch_seconds = (int) (new Date().getTime() / 1000l)
    env."BEG_${flakeref_trimmed}_${env.BUILD_TAG}" = epoch_seconds
    sh "nix build ${flakeref} ${opts}"
    // If the build result is an image, produce a signature file
    img_relpath = subdir ? find_img_relpath(flakeref, subdir, abort_on_error='false') : ""
    if (img_relpath) {
      target_path = "${subdir}/${img_relpath}"
      sig_path = "sig/${img_relpath}.sig"
      sign_file(target_path, sig_path, "INT-Ghaf-Devenv-Image")
      // Archive signature file alongside the target image
      archive_artifacts("sig")
    } else {
      println "Build result is not an image, skipping image signing"
    }
    // Store the build end time to job's environment
    epoch_seconds = (int) (new Date().getTime() / 1000l)
    env."END_${flakeref_trimmed}_${env.BUILD_TAG}" = epoch_seconds
    // Archive possible build outputs from subdir directory
    if (subdir) {
        archive_artifacts(subdir)
    }
  } catch (InterruptedException e) {
    // Do not continue pipeline execution on abort.
    throw e
  } catch (Exception e) {
    // Otherwise, if the command fails, mark the current step unstable and set
    // the final build result to failed, but continue the pipeline execution.
    unstable("FAILED: ${flakeref}")
    currentBuild.result = "FAILURE"
    println "Error: ${e.toString()}"
  }
}

def provenance(String flakeref, String outdir, String flakeref_trimmed) {
    env.PROVENANCE_BUILD_TYPE = "https://github.com/tiiuae/ghaf-infra/blob/ea938e90/slsa/v1.0/L1/buildtype.md"
    env.PROVENANCE_BUILDER_ID = "${env.JENKINS_URL}"
    env.PROVENANCE_INVOCATION_ID = "${env.BUILD_URL}"
    env.PROVENANCE_TIMESTAMP_BEGIN = env."BEG_${flakeref_trimmed}_${env.BUILD_TAG}"
    env.PROVENANCE_TIMESTAMP_FINISHED = env."END_${flakeref_trimmed}_${env.BUILD_TAG}"
    env.PROVENANCE_EXTERNAL_PARAMS = """
      {
        "target": {
          "name": "${flakeref}",
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
    opts = "--recursive --out ${outdir}/provenance.json"
    sh "provenance ${flakeref} ${opts}"
    // Sign the provenance
    target_path = "${outdir}/provenance.json"
    sign_file(target_path, "${target_path}.sig", "INT-Ghaf-Devenv-Provenance")
}

def sbomnix(String tool, String flakeref) {
  flakeref_trimmed = "${flakeref_trim(flakeref)}"
  // Sbomnix outputs are stored in directory hierarchy under 'scs/'
  outdir = "scs/${flakeref_trimmed}/scs"
  sh "mkdir -p ${outdir}"
  if (tool == "provenance") {
    provenance(flakeref, outdir, flakeref_trimmed)
  } else if (tool == "sbomnix") {
    sh """
      cd ${outdir}
      sbomnix ${flakeref}
    """
  } else if (tool == "vulnxscan") {
    sh """
      vulnxscan ${flakeref} --out vulns.csv
      csvcut vulns.csv --not-columns sortcol | csvlook -I >${outdir}/vulns.txt
    """
  }
  archive_artifacts("scs")
}

def find_img_relpath(String flakeref, String subdir, String abort_on_error="true") {
  flakeref_trimmed = "${flakeref_trim(flakeref)}"
  img_relpath = sh(
    script: """
      cd ${subdir} && \
      find -L ${flakeref_trimmed} -regex '.*\\.\\(img\\|raw\\|zst\\|iso\\)\$' -print -quit
    """, returnStdout: true).trim()
  if (!img_relpath) {
    println "Warning: no image found from '${subdir}/${flakeref_trimmed}'"
    // Error out stopping the pipeline execution if abort_on_error was set
    sh "if [ '${abort_on_error}' = 'true' ]; then exit 1; fi"
  } else {
    println "Found flakeref '${flakeref}' image '${img_relpath}'"
  }
  return img_relpath
}

def sign_file(String path, String sigfile, String cert="INT-Ghaf-Devenv-Common") {
  println "sign_file: ${path} ### ${cert} ### ${sigfile}"
  try {
    sh(
      // See the 'sign' command at: https://github.com/tiiuae/ci-yubi
      script: """
        mkdir -p \$(dirname '${sigfile}') || true
        sign --path=${path} --cert=${cert} --sigfile=${sigfile}
    """, returnStdout: true).trim()
  } catch (Exception e) {
    println "Warning: signing failed: sigfile will not be generated for: ${path}"
  }
}

def ghaf_hw_test(String flakeref, String device_config, String testset='_boot_') {
  testagent_nodes = nodesByLabel(label: "$device_config", offline: false)
  if (!testagent_nodes) {
    println "Warning: Skipping HW test '$flakeref', no test agents online"
    unstable("No test agents online")
    return
  }
  if (!env.ARTIFACTS_REMOTE_PATH) {
    println "Warning: skipping HW test '$flakeref', ARTIFACTS_REMOTE_PATH not set"
    return
  }
  if (!env.JENKINS_URL) {
    println "Warning: skipping HW test '$flakeref', JENKINS_URL not set"
    return
  }
  // Compose the image URL; testagent will need this URL to download the image
  def imgdir = find_img_relpath(flakeref, 'archive')
  def remote_path = "artifacts/${env.ARTIFACTS_REMOTE_PATH}"
  def img_url = "${env.JENKINS_URL}/${remote_path}/${imgdir}"
  def commit_hash = "${env.TARGET_COMMIT}"
  def build_url = "${env.JENKINS_URL}/job/${env.JOB_NAME}/${env.BUILD_ID}"
  def build_href = "<a href=\"${build_url}\">${env.JOB_NAME}#${env.BUILD_ID}</a>"
  def flakeref_trimmed = "${flakeref_trim(flakeref)}"
  def description = "Triggered by ${build_href}<br>(${flakeref_trimmed})"
  // Trigger a build in 'ghaf-hw-test' pipeline.
  // 'build' step is documented in https://plugins.jenkins.io/pipeline-build-step/
  def job = build(
    job: "ghaf-hw-test",
    propagate: false,
    parameters: [
      string(name: "LABEL", value: "$device_config"),
      string(name: "DEVICE_CONFIG_NAME", value: "$device_config"),
      string(name: "IMG_URL", value: "$img_url"),
      string(name: "DESC", value: "$description"),
      string(name: "TESTSET", value: "$testset"),
      string(name: "TARGET", value: "$flakeref_trimmed"),
      string(name: "COMMIT_HASH", value: "$commit_hash"),
    ],
    wait: true,
  )
  println "ghaf-hw-test result (${device_config}:${testset}): ${job.result}"

  // Copy test results from agent to controller to 'test-results' directory
  copyArtifacts(
      projectName: "ghaf-hw-test",
      selector: specific("${job.number}"),
      target: "ghaf-hw-test/${flakeref_trimmed}/test-results",
  )
  // Archive the test results
  archive_artifacts("ghaf-hw-test", flakeref_trimmed)

  // If the test job failed, mark the current step unstable and set
  // the final build result failed, but continue the pipeline execution.
  if (job.result != "SUCCESS") {
    unstable("FAILED: ${device_config} ${testset}")
    currentBuild.result = "FAILURE"
    // Add a link to failed test job(s) on the calling pipeline
    def test_href = "<a href=\"${job.absoluteUrl}\">⛔ ${flakeref_trimmed}</a>"
    currentBuild.description = "${currentBuild.description}<br>${test_href}"
    return flakeref_trimmed
  }
  return null
}

def nix_eval_jobs(List<Map> targets) {
  // transform target names into valid nix arrays to be plugged into the expression below
  def x86_targets = targets.findAll { it.system == "x86_64-linux" }.target
  x86_targets = x86_targets ? "\"${x86_targets.join('" "')}\"" : ""

  def aarch64_targets = targets.findAll { it.system == "aarch64-linux" }.target
  aarch64_targets = aarch64_targets ? "\"${aarch64_targets.join('" "')}\"" : ""

  // nix-eval-jobs is used to evaluate the targets in parallel and compute derivation paths.
  // nix expression is used to create an attset on the fly which is a subset of #packages,
  // but optimized to only include the targets we want to build
  sh """
    nix-eval-jobs --gc-roots-dir gcroots --force-recurse --expr ' \
      let \
        flake = builtins.getFlake ("git+file://" + toString ./.); \
        lib = (import flake.inputs.nixpkgs { }).lib; \
      in { \
        x86_64-linux = lib.getAttrs [ ${x86_targets} ] flake.packages.x86_64-linux; \
        aarch64-linux = lib.getAttrs [ ${aarch64_targets} ] flake.packages.aarch64-linux; \
      }' > jobs.json

    jq -s 'map({ (.attr): { drvPath, error } }) | add' < jobs.json > results.json
  """

  targets.each {
    target = "${it.system}.${it.target}"
    drvPath = sh(script: "jq -r '.\"${target}\".drvPath' < results.json", returnStdout: true).trim()
    evalError = sh(script: "jq -r '.\"${target}\".error' < results.json", returnStdout: true).trim()
    it.drvPath = drvPath
    it.error = evalError == "null" ? null : evalError
  }
}

def nix_eval_hydrajobs(List<Map> targets) {
  def targetList = targets.findAll { it }.target
  targetList = targetList ? "\"${targetList.join('" "')}\"" : ""

  sh """
    nix-eval-jobs --gc-roots-dir gcroots --force-recurse --expr ' \
      let \
        flake = builtins.getFlake ("git+file://" + toString ./.); \
        lib = (import flake.inputs.nixpkgs { }).lib; \
      in lib.getAttrs [ ${targetList} ] flake.hydraJobs' > jobs.json

    jq -s 'map({ (.attr): { drvPath, error } }) | add' < jobs.json > results.json
  """

  targets.each {
    // note that this is in flipped order compared to #packages
    target = "${it.target}.${it.system}"
    drvPath = sh(script: "jq -r '.\"${target}\".drvPath' < results.json", returnStdout: true).trim()
    evalError = sh(script: "jq -r '.\"${target}\".error' < results.json", returnStdout: true).trim()
    it.drvPath = drvPath
    it.error = evalError == "null" ? null : evalError
  }
}

def create_parallel_stages(List<Map> targets, String testset='_boot_bat_perf_', List failedTargets=null, List failedHWTests=null) {
  def target_jobs = [:]
  targets.each {
    def timestampBegin = ""
    def timestampEnd = ""
    def displayName = "${it.target} (${it.system})"
    def targetAttr = "${it.system}.${it.target}"
    def scsdir = "scs/${targetAttr}/scs"
    target_jobs[displayName] = {
      stage("Build ${displayName}") {
        def opts = ""
        if (it.archive) {
          opts = "--out-link archive/${targetAttr}"
        } else {
          opts = "--no-link"
        }
        try {
          if (it.error) {
            error("Error in evaluation! ${it.error}")
          }
          timestampBegin = sh(script: "date +%s", returnStdout: true).trim()
          sh "nix build -L ${it.drvPath}\\^* ${opts}"
          timestampEnd = sh(script: "date +%s", returnStdout: true).trim()

          // only attempt signing if there is something to sign
          if (it.archive) {
            def img_relpath = find_img_relpath(targetAttr, "archive")
            sign_file("archive/${img_relpath}", "sig/${img_relpath}.sig", "INT-Ghaf-Devenv-Image")
          };

        } catch (InterruptedException e) {
          throw e
        } catch (Exception e) {
          if (failedTargets != null) {
            failedTargets.add(targetAttr)
          }
          println "Error: ${e.toString()}"
          // Stop running remaining steps if build failed
          throw e
        }
      }

      if (it.scs) {
        stage("Provenance ${displayName}") {
          def externalParams = """
            {
              "target": {
                "name": "${targetAttr}",
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
                provenance ${it.drvPath} --recursive --out ${outpath}
              """
              sign_file(outpath, "${outpath}.sig", "INT-Ghaf-Devenv-Provenance")
            }
          }
        }

        stage("SBOM ${displayName}") {
          lock('sbom') {
            catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
              sh """
                mkdir -p ${scsdir}
                cd ${scsdir}
                sbomnix ${it.drvPath}
              """
            }
          }
        }

        stage("Vulnxscan ${displayName}") {
          catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
            sh """
              mkdir -p ${scsdir}
              vulnxscan ${it.drvPath} --out vulns.csv
              csvcut vulns.csv --not-columns sortcol | csvlook -I >${scsdir}/vulns.txt
            """
          }
        }
      }

      if (it.archive) {
        stage("Archive ${displayName}") {
          script {
            archive_artifacts("archive", targetAttr)
            archive_artifacts("sig", targetAttr)
            if (it.scs) {
              archive_artifacts("scs", targetAttr)
            }
          }
        }
      }

      if (testset != null && it.hwtest_device != null) {
        stage("Test ${displayName}") {
          script {
            flakeref = ghaf_hw_test(targetAttr, it.hwtest_device, testset)
            if (flakeref != null) {
              failedHWTests.add(flakeref)
            }
          }
        }
      }
    }
  }

  return target_jobs
}

return this

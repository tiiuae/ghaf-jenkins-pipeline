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
    export RCLONE_WEBDAV_UNIX_SOCKET_PATH=/run/rclone-jenkins-artifacts.sock
    export RCLONE_WEBDAV_URL=http://localhost
    rclone ${opts}
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
  imgdir = find_img_relpath(flakeref, 'archive')
  remote_path = "artifacts/${env.ARTIFACTS_REMOTE_PATH}"
  img_url = "${env.JENKINS_URL}/${remote_path}/${imgdir}"
  build_url = "${env.JENKINS_URL}/job/${env.JOB_NAME}/${env.BUILD_ID}"
  build_href = "<a href=\"${build_url}\">${env.JOB_NAME}#${env.BUILD_ID}</a>"
  flakeref_trimmed = "${flakeref_trim(flakeref)}"
  // 'short' flakeref: everything after the last occurence of '.' (if any)
  flakeref_short = flakeref_trimmed.replaceAll(/.*\.+/,"")
  description = "Triggered by ${build_href}<br>(${flakeref_short})"
  // Trigger a build in 'ghaf-hw-test' pipeline.
  // 'build' step is documented in https://plugins.jenkins.io/pipeline-build-step/
  job = build(
    job: "ghaf-hw-test",
    propagate: false,
    parameters: [
      string(name: "LABEL", value: "$device_config"),
      string(name: "DEVICE_CONFIG_NAME", value: "$device_config"),
      string(name: "IMG_URL", value: "$img_url"),
      string(name: "DESC", value: "$description"),
      string(name: "TESTSET", value: "$testset"),
      string(name: "TARGET", value: "$flakeref_trimmed"),
    ],
    wait: true,
  )
  println "ghaf-hw-test result (${device_config}:${testset}): ${job.result}"
  // If the test job failed, mark the current step unstable and set
  // the final build result failed, but continue the pipeline execution.
  if (job.result != "SUCCESS") {
    unstable("FAILED: ${device_config} ${testset}")
    currentBuild.result = "FAILURE"
    // Add a link to failed test job(s) on the calling pipeline
    test_href = "<a href=\"${job.absoluteUrl}\">⛔ ${flakeref_short}</a>"
    currentBuild.description = "${currentBuild.description}<br>${test_href}"
  }
  // Copy test results from agent to controller to 'test-results' directory
  copyArtifacts(
      projectName: "ghaf-hw-test",
      selector: specific("${job.number}"),
      target: "ghaf-hw-test/${flakeref_trimmed}/test-results",
  )
  // Archive the test results
  archive_artifacts("ghaf-hw-test")
}

def ghaf_parallel_hw_test(String flakeref, String device_config, String testset='_boot_') {
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
  def build_url = "${env.JENKINS_URL}/job/${env.JOB_NAME}/${env.BUILD_ID}"
  def build_href = "<a href=\"${build_url}\">${env.JOB_NAME}#${env.BUILD_ID}</a>"
  def flakeref_trimmed = "${flakeref_trim(flakeref)}"
  def description = "Triggered by ${build_href}<br>(${flakeref_trimmed})"
  // Trigger a build in 'ghaf-parallel-hw-test' pipeline.
  // 'build' step is documented in https://plugins.jenkins.io/pipeline-build-step/
  def job = build(
    job: "ghaf-parallel-hw-test",
    propagate: false,
    parameters: [
      string(name: "LABEL", value: "$device_config"),
      string(name: "DEVICE_CONFIG_NAME", value: "$device_config"),
      string(name: "IMG_URL", value: "$img_url"),
      string(name: "DESC", value: "$description"),
      string(name: "TESTSET", value: "$testset"),
      string(name: "TARGET", value: "$flakeref_trimmed"),
    ],
    wait: true,
  )
  println "ghaf-parallel-hw-test result (${device_config}:${testset}): ${job.result}"
  // If the test job failed, mark the current step unstable and set
  // the final build result failed, but continue the pipeline execution.
  if (job.result != "SUCCESS") {
    unstable("FAILED: ${device_config} ${testset}")
    currentBuild.result = "FAILURE"
    // Add a link to failed test job(s) on the calling pipeline
    def test_href = "<a href=\"${job.absoluteUrl}\">⛔ ${flakeref_trimmed}</a>"
    currentBuild.description = "${currentBuild.description}<br>${test_href}"
  }
  // Copy test results from agent to controller to 'test-results' directory
  copyArtifacts(
      projectName: "ghaf-parallel-hw-test",
      selector: specific("${job.number}"),
      target: "ghaf-parallel-hw-test/${flakeref_trimmed}/test-results",
  )
  // Archive the test results
  archive_artifacts("ghaf-parallel-hw-test", flakeref_trimmed)
}

def nix_eval_jobs(List<Map> targets) {
  // transform target names into valid nix arrays to be plugged into the expression below
  def x86_targets = "\"${targets.findAll { it.system == "x86_64-linux" }.target.join('" "')}\""
  def aarch64_targets = "\"${targets.findAll { it.system == "aarch64-linux" }.target.join('" "')}\""

  // nix-eval-jobs is used to evaluate the targets in parallel and compute derivation paths.
  // nix expression is used to create an attset on the fly which is a subset of #packages, 
  // but only includes the targets we want to build, to save time
  sh """
    nix-eval-jobs --gc-roots-dir gcroots --force-recurse --expr ' \
      let \
        flake = builtins.getFlake ("git+file://" + toString ./.); \
        lib = (import flake.inputs.nixpkgs { }).lib; \
      in { \
        x86_64-linux = lib.getAttrs [ ${x86_targets} ] flake.packages.x86_64-linux; \
        aarch64-linux = lib.getAttrs [ ${aarch64_targets} ] flake.packages.aarch64-linux; \
      }' > jobs.json
  """

  // target's name and derivation path are read from jobs.json and written into into jobs.txt
  sh "jq -r '.attr + \" \" + .drvPath' < jobs.json > jobs.txt"
}

return this

////////////////////////////////////////////////////////////////////////////////

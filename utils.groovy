#!/usr/bin/env groovy

// SPDX-FileCopyrightText: 2022-2024 TII (SSRC) and the Ghaf contributors
// SPDX-License-Identifier: Apache-2.0

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

def archive_artifacts(String flakeref, ArrayList subdirs) {
  String flakeref_trimmed = "${flakeref_trim(flakeref)}"
  if (!subdirs) {
    println "Warning: skipping archive, subdirs not set"
    return
  }
  // Archive artifacts to env.ARTIFACTS_REMOTE_PATH
  if (!env.ARTIFACTS_REMOTE_PATH) {
    println "Warning: skipping archive, ARTIFACTS_REMOTE_PATH not set"
    return
  }
  // List paths to tar as a string
  String pathTail = "/${flakeref_trimmed}/* "
  String toTar = subdirs.join(pathTail) + pathTail
  sh """
    # tar is missing from Jenkins PATH
    export PATH="$PATH:/run/current-system/sw/bin"
    tar -cvf ${subdirs[0]}/${flakeref_trimmed}.tar ${toTar}
  """
  for (dir in subdirs) {
    run_rclone("copy -L ${dir}/ :webdav:/${env.ARTIFACTS_REMOTE_PATH}/")
  }
  String href="/artifacts/${env.ARTIFACTS_REMOTE_PATH}/"
  currentBuild.description = "<a href=\"${href}\">📦 Artifacts</a>"
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
    // Store the build end time to job's environment
    epoch_seconds = (int) (new Date().getTime() / 1000l)
    env."END_${flakeref_trimmed}_${env.BUILD_TAG}" = epoch_seconds
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
        "buildRun": "${env.BUILD_ID}"
      }
    """
    opts = "--recursive --out ${outdir}/provenance.json"
    sh "nix run github:tiiuae/sbomnix/${sbomnix_hexsha}#provenance -- ${flakeref} ${opts}"
}

def sbomnix(String tool, String flakeref) {
  sbomnix_hexsha = "0b19e055d1f5124fd67d567db342ef4dd21da6f2"
  flakeref_trimmed = "${flakeref_trim(flakeref)}"
  // Sbomnix outputs are stored in directory hierarchy under 'scs/'
  outdir = "scs/${flakeref_trimmed}/scs"
  sh "mkdir -p ${outdir}"
  if (tool == "provenance") {
    provenance(flakeref, outdir, flakeref_trimmed)
  } else if (tool == "sbomnix") {
    sh """
      cd ${outdir}
      nix run github:tiiuae/sbomnix/${sbomnix_hexsha}#sbomnix -- ${flakeref}
    """
  } else if (tool == "vulnxscan") {
    sh """
      nix run github:tiiuae/sbomnix/${sbomnix_hexsha}#vulnxscan -- ${flakeref} --out vulns.csv
      csvcut vulns.csv --not-columns sortcol | csvlook -I >${outdir}/vulns.txt
    """
  }
}

def find_img_relpath(String flakeref, String subdir) {
  flakeref_trimmed = "${flakeref_trim(flakeref)}"
  img_relpath = sh(
    script: """
      cd ${subdir} && \
      find -L ${flakeref_trimmed} -regex '.*\\.\\(img\\|raw\\|zst\\|iso\\)\$' -print -quit
    """, returnStdout: true).trim()
  if(!img_relpath) {
    // Error out stopping the pipeline execution if image was not found
    println "Error: no image found from '${subdir}/${flakeref_trimmed}'"
    sh "exit 1"
  } else {
    println "Found flakeref '${flakeref}' image '${img_relpath}'"
  }
  return img_relpath
}

def boot_test(String flakeref, String device_config, String jenkins_url, String subdir='archive') {
  testagent_nodes = nodesByLabel(label: 'testagent', offline: false)
  if (!testagent_nodes) {
    println "Warning: Skipping boot test '$flakeref', no test agents online"
    unstable("No test agents online")
    return
  }
  if (!env.ARTIFACTS_REMOTE_PATH) {
    println "Warning: skipping boot_test '$flakeref', ARTIFACTS_REMOTE_PATH not set"
    return
  }
  // Compose the image URL; testagent will need this URL to download the image
  imgdir = find_img_relpath(flakeref, subdir)
  remote_path = "artifacts/${env.ARTIFACTS_REMOTE_PATH}"
  img_url = "${jenkins_url}/${remote_path}/${imgdir}"
  build_url = "${jenkins_url}/job/${env.JOB_NAME}/${env.BUILD_ID}"
  build_href = "<a href=\"${build_url}\">${env.JOB_NAME}#${env.BUILD_ID}</a>"
  // 'short' flakeref: everything after the last occurence of '.' (if any)
  flakeref_short = flakeref_trim(flakeref).replaceAll(/.*\.+/,"")
  description = "Triggered by ${build_href}<br>(${flakeref_short})"
  // Trigger a build in 'ghaf-test-boot' pipeline.
  // 'build' step is documented in https://plugins.jenkins.io/pipeline-build-step/
  build(
    job: "ghaf-test-boot",
    propagate: true,
    parameters: [
      string(name: "LABEL", value: "testagent"),
      string(name: "DEVICE_CONFIG_NAME", value: "$device_config"),
      string(name: "IMG_URL", value: "$img_url"),
      string(name: "DESC", value: "$description"),
    ],
    wait: true,
  )
}

return this

////////////////////////////////////////////////////////////////////////////////

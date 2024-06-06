#!/usr/bin/env groovy

// SPDX-FileCopyrightText: 2022-2024 TII (SSRC) and the Ghaf contributors
// SPDX-License-Identifier: Apache-2.0

////////////////////////////////////////////////////////////////////////////////

def flakeref_trim(flakeref) {
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

def nix_build(flakeref, produce_out="false") {
  try {
    flakeref_trimmed = "${flakeref_trim(flakeref)}"
    // Produce build out-links only if it was requested
    if (produce_out == "false") {
      opts = "--no-link"
    } else {
      // Build results are stored in directory hierarchy under 'build/'
      outdir = "build/${flakeref_trimmed}"
      opts = "--out-link ${outdir}"
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
    echo 'Error: ' + e.toString()
  }
}

def provenance(flakeref, outdir, flakeref_trimmed) {
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

def sbomnix(tool, flakeref) {
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

return this

////////////////////////////////////////////////////////////////////////////////

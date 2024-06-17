#!/usr/bin/env groovy

// SPDX-FileCopyrightText: 2022-2024 TII (SSRC) and the Ghaf contributors
// SPDX-License-Identifier: Apache-2.0

////////////////////////////////////////////////////////////////////////////////

def DEF_LABEL = 'testagent'
def TMP_IMG_DIR = "image"

////////////////////////////////////////////////////////////////////////////////

pipeline {
  agent { label "${params.getOrDefault('LABEL', DEF_LABEL)}" }
  options { timestamps () }
  stages {
    stage('Debug print') {
      steps {
        script {
          sh "uname -a"
          sh "pwd"
          println "${params}"
        }
      }
    }
    stage('Image download') {
      steps {
        script {
          if (!params.containsKey('IMG_URL')) {
            println "Missing IMG_URL parameter"
            exit 1
          }
          sh "rm -fr ${TMP_IMG_DIR}"
          sh "wget -nv --show-progress --progress=dot:giga -P ${TMP_IMG_DIR} ${params.IMG_URL}"
          sh "ls -la ${TMP_IMG_DIR}"
          img_relpath = sh(script:"find ${TMP_IMG_DIR} -type f -print -quit", returnStdout:true).trim()
          println "Downloaded image to workspace: ${img_relpath}"
        }
      }
    }
  }
}

////////////////////////////////////////////////////////////////////////////////

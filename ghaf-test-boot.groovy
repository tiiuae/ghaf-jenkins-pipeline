#!/usr/bin/env groovy

// SPDX-FileCopyrightText: 2022-2024 TII (SSRC) and the Ghaf contributors
// SPDX-License-Identifier: Apache-2.0

////////////////////////////////////////////////////////////////////////////////

def REPO_URL = 'https://github.com/tiiuae/ci-test-automation/'
def DEF_LABEL = 'testagent'
def TMP_IMG_DIR = 'image'

////////////////////////////////////////////////////////////////////////////////

def run_cmd(String cmd) {
  // Run cmd returning stdout
  return sh(script: cmd, returnStdout:true).trim()
}

////////////////////////////////////////////////////////////////////////////////

pipeline {
  agent { label "${params.getOrDefault('LABEL', DEF_LABEL)}" }
  options { timestamps () }
  stages {
    stage('Checkout') {
      steps {
        checkout scmGit(
          branches: [[name: 'main']],
          extensions: [cleanBeforeCheckout()],
          userRemoteConfigs: [[url: REPO_URL]]
        )
        script {
          sh 'rm -f Robot-Framework/config/*.json'
          sh 'ln -sv /etc/jenkins/test_config.json Robot-Framework/config'
          sh """
            echo { \\\"Job\\\": \\\"${BUILD_NUMBER}\\\" } > Robot-Framework/config/${BUILD_NUMBER}.json
          """
        }
      }
    }
    stage('Set description') {
      steps {
        script {
          if(!params.containsKey('DESC')) {
            println "Missing DESC parameter, skip setting description"
          } else {
            currentBuild.description = "${params.DESC}"
          }
        }
      }
    }
    stage('Image download') {
      steps {
        script {
          if(!params.containsKey('IMG_URL')) {
            println "Missing IMG_URL parameter"
            sh "exit 1"
          }
          sh "rm -fr ${TMP_IMG_DIR}"
          sh "wget -nv --show-progress --progress=dot:giga -P ${TMP_IMG_DIR} ${params.IMG_URL}"
          img_relpath = run_cmd("find ${TMP_IMG_DIR} -type f -print -quit | grep .")
          println "Downloaded image to workspace: ${img_relpath}"
          // Uncompress, keeping only the decompressed image file
          if(img_relpath.endsWith("zst")) {
            sh "zstd -dfv ${img_relpath} && rm ${img_relpath}"
          }
          sh "ls -la ${TMP_IMG_DIR}"
        }
      }
    }
    stage('Flash') {
      steps {
        script {
          if(!params.getOrDefault('DEVICE_CONFIG_NAME', null)) {
            println "Missing DEVICE_CONFIG_NAME parameter"
            sh "exit 1"
          }
          mount_cmd = unmount_cmd = devstr = null
          if(["orin-agx"].contains(params.DEVICE_CONFIG_NAME)) {
            mount_cmd = '/run/wrappers/bin/sudo AcronameHubCLI -u 0 -s 0x2954223B; sleep 10'
            unmount_cmd = '/run/wrappers/bin/sudo AcronameHubCLI -u 1 -s 0x2954223B'
            devstr = 'PSSD'
            // Device-sepcific configuration needed in other steps are passed
            // as environment variables
            env.DEVICE = 'OrinAGX1'
            env.INCLUDE_TEST_TAGS = 'bootANDorin-agx'
          } else {
            println "Error: unsupported device config '${params.DEVICE_CONFIG_NAME}'"
            sh "exit 1"
          }
          // Mount the target disk
          sh "${mount_cmd}"
          // Read the device name
          dev = run_cmd("lsblk -o model,name | grep ${devstr} | rev | cut -d ' ' -f 1 | rev | grep .")
          println "Using device '$dev'"
          // Write the image
          img_relpath = run_cmd("find ${TMP_IMG_DIR} -type f -print -quit | grep .")
          println "Using image '$img_relpath'"
          sh "/run/wrappers/bin/sudo dd if=${img_relpath} of=/dev/${dev} bs=1M status=progress conv=fsync"
          // Unmount
          sh "${unmount_cmd}"
        }
      }
    }
    stage('Boot test') {
      steps {
        script {
        // TODO: do we really need credentials to access the target devices?
        // Target devices are connected to the testagent, which itself is
        // only available over a private network. What is the risk
        // we are protecting against by having additional authentication
        // for the test devices?
        // The current configuration requires additional manual configuration
        // on the jenkins UI to add the following secrets:
        withCredentials([
          string(credentialsId: 'testagent-dut-pass', variable: 'DUT_PASS'),
          string(credentialsId: 'testagent-plug-pass', variable: 'PLUG_PASS'),
          ]) {
            dir('Robot-Framework/test-suites') {
              sh 'rm -f *.png output.xml report.html log.html'
              // Pass the secrets to the shell as environment variables, as we
              // don't want Groovy to interpolate them. Similary, we pass
              // other variables as environment variables to shell.
              // Ref: https://www.jenkins.io/doc/book/pipeline/jenkinsfile/#string-interpolation
              sh '''
                nix run .#ghaf-robot -- \
                  -v DEVICE:$DEVICE \
                  -v LOGIN:ghaf \
                  -v PASSWORD:$DUT_PASS \
                  -v PLUG_USERNAME:ville-pekka.juntunen@unikie.com \
                  -v PLUG_PASSWORD:$PLUG_PASS \
                  -i $INCLUDE_TEST_TAGS .
              '''
            }
          }
        }
      }
    }
  }
  post {
    always {
      step(
        [$class: 'RobotPublisher',
          archiveDirName: 'robot-plugin',
          outputPath: 'Robot-Framework/test-suites',
          outputFileName: 'output.xml',
          disableArchiveOutput: false,
          reportFileName: 'report.html',
          logFileName: 'log.html',
          passThreshold: 0,
          unstableThreshold: 0,
          onlyCritical: true,
        ]
      )
    }
  }
}

////////////////////////////////////////////////////////////////////////////////
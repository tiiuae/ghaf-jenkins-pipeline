#!/usr/bin/env groovy

// SPDX-FileCopyrightText: 2022-2024 TII (SSRC) and the Ghaf contributors
// SPDX-License-Identifier: Apache-2.0

////////////////////////////////////////////////////////////////////////////////

def REPO_URL = 'https://github.com/tiiuae/ci-test-automation/'
def DEF_LABEL = 'testagent'
def TMP_IMG_DIR = 'image'
def CONF_FILE_PATH = '/etc/jenkins/test_config.json'

////////////////////////////////////////////////////////////////////////////////

def run_cmd(String cmd) {
  // Run cmd returning stdout
  return sh(script: cmd, returnStdout:true).trim()
}

def get_test_conf_property(String file_path, String device, String property) {
  // Get the requested device property data from test_config.json file
  def device_data = readJSON file: file_path
  property_data = "${device_data['addresses'][device][property]}"
  println "Got device '${device}' property '${property}' value: '${property_data}'"
  return property_data
}

def ghaf_robot_test(String testname='boot') {
  if (!env.DEVICE_TAG) {
    sh "echo 'DEVICE_TAG not set'; exit 1"
  }
  if (!env.DEVICE_NAME) {
    sh "echo 'DEVICE_NAME not set'; exit 1"
  }
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
    string(credentialsId: 'testagent-switch-token', variable: 'SW_TOKEN'),
    string(credentialsId: 'testagent-switch-secret', variable: 'SW_SECRET'),
  ]) {
    dir('Robot-Framework/test-suites') {
      if (testname == 'turnoff') {
        env.INCLUDE_TEST_TAGS = "${testname}"
      } else {
        env.INCLUDE_TEST_TAGS = "${testname}AND${env.DEVICE_TAG}"
      }
      sh 'rm -f *.png output.xml report.html log.html'
      // On failure, continue the pipeline execution
      catchError(stageResult: 'FAILURE', buildResult: 'FAILURE') {
        // Pass the secrets to the shell as environment variables, as we
        // don't want Groovy to interpolate them. Similary, we pass
        // other variables as environment variables to shell.
        // Ref: https://www.jenkins.io/doc/book/pipeline/jenkinsfile/#string-interpolation
        sh '''
          nix run .#ghaf-robot -- \
            -v DEVICE:$DEVICE_NAME \
            -v LOGIN:ghaf \
            -v PASSWORD:$DUT_PASS \
            -v PLUG_USERNAME:ville-pekka.juntunen@unikie.com \
            -v PLUG_PASSWORD:$PLUG_PASS \
            -v SWITCH_TOKEN:$SW_TOKEN \
            -v SWITCH_SECRET:$SW_SECRET \
            -v BUILD_ID:${BUILD_NUMBER} \
            -i $INCLUDE_TEST_TAGS .
        '''
        // Move the test output (if any) to a subdirectory
        sh """
          rm -fr $testname; mkdir -p $testname
          mv -f *.png output.xml report.html log.html $testname/ || true
        """
        if (testname == 'boot') {
          // Set an environment variable to indicate boot test passed
          env.BOOT_PASSED = 'true'
        }
      }
    }
  }
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
    stage('Setup') {
      steps {
        script {
          if(!params.containsKey('DESC')) {
            println "Missing DESC parameter, skip setting description"
          } else {
            currentBuild.description = "${params.DESC}"
          }
          env.TESTSET = params.getOrDefault('TESTSET', '_boot_')
          println "Using TESTSET: ${env.TESTSET}"
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
          if(["orin-agx"].contains(params.DEVICE_CONFIG_NAME)) {
            env.DEVICE_NAME = 'OrinAGX1'
            env.DEVICE_TAG = 'orin-agx'
          } else if(["orin-nx"].contains(params.DEVICE_CONFIG_NAME)) {
            env.DEVICE_NAME = 'OrinNX1'
            env.DEVICE_TAG = 'orin-nx'
          } else if(["lenovo-x1"].contains(params.DEVICE_CONFIG_NAME)) {
            env.DEVICE_NAME = 'LenovoX1-2'
            env.DEVICE_TAG = 'lenovo-x1'
          } else {
            println "Error: unsupported device config '${params.DEVICE_CONFIG_NAME}'"
            sh "exit 1"
          }
          hub_serial = get_test_conf_property(CONF_FILE_PATH, env.DEVICE_NAME, 'usbhub_serial')
          mount_cmd = "/run/wrappers/bin/sudo AcronameHubCLI -u 0 -s ${hub_serial}; sleep 10"
          unmount_cmd = "/run/wrappers/bin/sudo AcronameHubCLI -u 1 -s ${hub_serial}"
          // Mount the target disk
          sh "${mount_cmd}"
          // Read the device name
          dev = run_cmd("lsblk -o model,name | grep 'PSSD' | rev | cut -d ' ' -f 1 | rev | grep .")
          println "Using device '$dev'"
          if(["lenovo-x1"].contains(params.DEVICE_CONFIG_NAME)) {
            echo "Wiping filesystem..."
            def SECTOR = 512
            def MIB_TO_SECTORS = 20480
            // Disk size in 512-byte sectors
            def SECTORS = sh(script: "/run/wrappers/bin/sudo blockdev --getsz /dev/${dev}", returnStdout: true).trim()
            // Unmount possible mounted filesystems
            sh "sync; /run/wrappers/bin/sudo umount -q /dev/${dev}* || true"
            // Wipe first 10MiB of disk
            sh "/run/wrappers/bin/sudo dd if=/dev/zero of=/dev/${dev} bs=${SECTOR} count=${MIB_TO_SECTORS} conv=fsync status=none"
            // Wipe last 10MiB of disk
            sh "/run/wrappers/bin/sudo dd if=/dev/zero of=/dev/${dev} bs=${SECTOR} count=${MIB_TO_SECTORS} seek=\$(( ${SECTORS} - ${MIB_TO_SECTORS} )) conv=fsync status=none"
          }
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
      when { expression { env.TESTSET.contains('_boot_')} }
      steps {
        script {
          env.BOOT_PASSED = 'false'
          ghaf_robot_test('boot')
          println "Boot test passed: ${env.BOOT_PASSED}"
        }
      }
    }
    stage('Bat test') {
      when { expression { env.BOOT_PASSED == 'true' && env.TESTSET.contains('_bat_')} }
      steps {
        script {
          ghaf_robot_test('bat')
        }
      }
    }
    stage('Perf test') {
      when { expression { env.BOOT_PASSED == 'true' && env.TESTSET.contains('_perf_')} }
      steps {
        script {
          ghaf_robot_test('performance')
        }
      }
    }
    stage('Turn off') {
      steps {
        script {
          ghaf_robot_test('turnoff')
        }
      }
    }
  }
  post {
    always {
      // Publish all results under Robot-Framework/test-suites subfolders
      step(
        [$class: 'RobotPublisher',
          archiveDirName: 'robot-plugin',
          outputPath: 'Robot-Framework/test-suites',
          outputFileName: '**/output.xml',
          disableArchiveOutput: false,
          reportFileName: '**/report.html',
          logFileName: '**/log.html',
          passThreshold: 0,
          unstableThreshold: 0,
          onlyCritical: true,
        ]
      )
    }
  }
}

////////////////////////////////////////////////////////////////////////////////

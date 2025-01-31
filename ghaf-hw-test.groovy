#!/usr/bin/env groovy

// SPDX-FileCopyrightText: 2022-2024 TII (SSRC) and the Ghaf contributors
// SPDX-License-Identifier: Apache-2.0

////////////////////////////////////////////////////////////////////////////////

def REPO_URL = 'https://github.com/tiiuae/ci-test-automation/'
def DEF_LABEL = 'testagent'
def TMP_IMG_DIR = 'image'
def TMP_SIG_DIR = 'signature'
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
  if (testname == 'turnoff' || testname == 'relay-turnoff') {
    env.INCLUDE_TEST_TAGS = "${testname}"
  } else {
    env.INCLUDE_TEST_TAGS = "${testname}AND${env.DEVICE_TAG}"
  }
  dir("Robot-Framework/test-suites") {
  sh 'rm -f *.png output.xml report.html log.html'
  // On failure, continue the pipeline execution
  try {
  // Pass variables as environment variables to shell.
  // Ref: https://www.jenkins.io/doc/book/pipeline/jenkinsfile/#string-interpolation
    env.COMMIT_HASH = "${params.COMMIT_HASH}"
    sh '''
      nix run .#ghaf-robot -- \
        -v DEVICE:$DEVICE_NAME \
        -v DEVICE_TYPE:$DEVICE_TAG \
        -v BUILD_ID:${BUILD_NUMBER} \
        -v COMMIT_HASH:$COMMIT_HASH \
        -i $INCLUDE_TEST_TAGS .
    '''
    if (testname == 'boot') {
      // Set an environment variable to indicate boot test passed
      env.BOOT_PASSED = 'true'
    }
    if (testname == 'relayboot') {
      // Set an environment variable to indicate boot test passed
      env.BOOT_PASSED = 'true'
    }
    } catch (Exception e) {
    currentBuild.result = "FAILURE"
    unstable("FAILED '${testname}': ${e.toString()}")
    } finally {
    // Move the test output (if any) to a subdirectory
    sh """
      rm -fr $testname; mkdir -p $testname
      mv -f *.png output.xml report.html log.html $testname/ || true
    """
    }
  }
}

////////////////////////////////////////////////////////////////////////////////

pipeline {
  agent { label "${params.getOrDefault('LABEL', DEF_LABEL)}" }
  stages {
    stage('Checkout') {
      steps {
        checkout scmGit(
          branches: [[name: 'main']],
          extensions: [cleanBeforeCheckout()],
          userRemoteConfigs: [[url: REPO_URL]]
        )
      }
    }
    stage('Setup') {
      steps {
        script {
          env.TEST_CONFIG_DIR = 'Robot-Framework/config'
          if(!params.getOrDefault('TARGET', null)) {
            println "Missing TARGET parameter"
            sh "exit 1"
          }
          println "Using TARGET: ${params.TARGET}"
          sh """
            mkdir -p ${TEST_CONFIG_DIR}
            rm -f ${TEST_CONFIG_DIR}/*.json
            ln -sv ${CONF_FILE_PATH} ${TEST_CONFIG_DIR}
            echo { \\\"Job\\\": \\\"${params.TARGET}\\\" } > ${TEST_CONFIG_DIR}/${BUILD_NUMBER}.json
            ls -la ${TEST_CONFIG_DIR}
          """
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
          // Wget occasionally fails due to a failure in name lookup. Below is a
          // hack to force re-try a few times before aborting. Wget options, such
          // as --tries, --waitretry, --retry-connrefused, etc. do not help in case
          // the failure is due to an issue in name resolution which is considered
          // a fatal error. Therefore, we need to add the below retry loop.
          // TODO: remove the below re-try loop when test network DNS works
          // reliably.
          sh """
            retry=1
            max_retry=3
            while ! wget -nv --show-progress --progress=dot:giga -P ${TMP_IMG_DIR} ${params.IMG_URL};
            do
              if (( \$retry >= \$max_retry )); then
                echo "wget failed after \$retry retries"
                exit 1
              fi
              retry=\$(( \$retry + 1 ))
              sleep 5
            done
          """
          img_relpath = run_cmd("find ${TMP_IMG_DIR} -type f -print -quit | grep .")
          println "Downloaded image to workspace: ${img_relpath}"
          // Verify signature using the tooling from: https://github.com/tiiuae/ci-yubi
          sh "wget -nv -P ${TMP_SIG_DIR} ${params.IMG_URL}.sig"
          sig_relpath = run_cmd("find ${TMP_SIG_DIR} -type f -print -quit | grep .")
          println "Downloaded signature to workspace: ${sig_relpath}"
          sh "nix run github:tiiuae/ci-yubi/bdb2dbf#verify -- --path ${img_relpath} --sigfile ${sig_relpath} --cert INT-Ghaf-Devenv-Image"
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
          // Determine the device name
          if(params.DEVICE_CONFIG_NAME == "orin-agx") {
            env.DEVICE_NAME = 'OrinAGX1'
          } else if(params.DEVICE_CONFIG_NAME == "orin-nx") {
            env.DEVICE_NAME = 'OrinNX1'
          } else if(params.DEVICE_CONFIG_NAME == "lenovo-x1") {
            env.DEVICE_NAME = 'LenovoX1-1'
          } else if(params.DEVICE_CONFIG_NAME == "nuc") {
            env.DEVICE_NAME = 'NUC1'
          } else if(params.DEVICE_CONFIG_NAME == "riscv") {
            env.DEVICE_NAME = 'Polarfire1'
          } else {
            println "Error: unsupported device config '${params.DEVICE_CONFIG_NAME}'"
            sh "exit 1"
          }
          // Determine mount commands
          if(params.DEVICE_CONFIG_NAME == "riscv") {
            muxport = get_test_conf_property(CONF_FILE_PATH, env.DEVICE_NAME, 'usb_sd_mux_port')
            dgrep = 'sdmux'
            mount_cmd = "/run/wrappers/bin/sudo usbsdmux ${muxport} host; sleep 10"
            unmount_cmd = "/run/wrappers/bin/sudo usbsdmux ${muxport} dut"
          } else {
            serial = get_test_conf_property(CONF_FILE_PATH, env.DEVICE_NAME, 'usbhub_serial')
            mount_cmd = "/run/wrappers/bin/sudo AcronameHubCLI -u 0 -s ${serial}; sleep 10"
            unmount_cmd = "/run/wrappers/bin/sudo AcronameHubCLI -u 1 -s ${serial}"
          }
          env.DEVICE_TAG = params.DEVICE_CONFIG_NAME
          // Mount the target disk
          sh "${mount_cmd}"
          // Read the device name
          dev = get_test_conf_property(CONF_FILE_PATH, env.DEVICE_NAME, 'ext_drive_by-id')
          println "Using device '$dev'"
          // Wipe possible ZFS leftovers, more details here:
          // https://github.com/tiiuae/ghaf/blob/454b18bc/packages/installer/ghaf-installer.sh#L75
          // TODO: use ghaf flashing scripts or installers?
          if(params.DEVICE_CONFIG_NAME == "lenovo-x1") {
            echo "Wiping filesystem..."
            SECTOR = 512
            MIB_TO_SECTORS = 20480
            // Disk size in 512-byte sectors
            SECTORS = sh(script: "/run/wrappers/bin/sudo blockdev --getsz /dev/disk/by-id/${dev}", returnStdout: true).trim()
            // Unmount possible mounted filesystems
            sh "sync; /run/wrappers/bin/sudo umount -q /dev/disk/by-id/${dev}* || true"
            // Wipe first 10MiB of disk
            sh "/run/wrappers/bin/sudo dd if=/dev/zero of=/dev/disk/by-id/${dev} bs=${SECTOR} count=${MIB_TO_SECTORS} conv=fsync status=none"
            // Wipe last 10MiB of disk
            sh "/run/wrappers/bin/sudo dd if=/dev/zero of=/dev/disk/by-id/${dev} bs=${SECTOR} count=${MIB_TO_SECTORS} seek=\$(( ${SECTORS} - ${MIB_TO_SECTORS} )) conv=fsync status=none"
          }
          // Write the image
          img_relpath = run_cmd("find ${TMP_IMG_DIR} -type f -print -quit | grep .")
          println "Using image '$img_relpath'"
          sh "/run/wrappers/bin/sudo dd if=${img_relpath} of=/dev/disk/by-id/${dev} bs=1M status=progress conv=fsync"
          // Unmount
          sh "${unmount_cmd}"
        }
      }
    }
    stage('Boot test') {
      when { expression { env.TESTSET.contains('_boot_') } }
      steps {
        script {
          env.BOOT_PASSED = 'false'
          ghaf_robot_test('boot')
          println "Boot test passed: ${env.BOOT_PASSED}"
        }
      }
    }
    stage('Boot test with relay') {
      when { expression { env.TESTSET.contains('_relayboot_') } }
      steps {
        script {
          env.BOOT_PASSED = 'false'
          ghaf_robot_test('relayboot')
          println "Boot test passed: ${env.BOOT_PASSED}"
        }
      }
    }
    stage('Pre-merge test') {
      when { expression { env.BOOT_PASSED == 'true' && env.TESTSET.contains('_pre-merge_')} }
      steps {
        script {
          ghaf_robot_test('pre-merge')
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
    stage('GUI test') {
      when { expression { env.BOOT_PASSED == 'true' && params.DEVICE_CONFIG_NAME == "lenovo-x1" && env.TESTSET.contains('_gui_')} }
      steps {
        script {
          ghaf_robot_test('gui')
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
      when { expression { env.BOOT_PASSED == 'true' && env.TESTSET.contains('_boot_')} }
      steps {
        script {
          ghaf_robot_test('turnoff')
        }
      }
    }
    stage('Turn off via relay') {
      when { expression { env.BOOT_PASSED == 'true' && env.TESTSET.contains('_relayboot_')} }
      steps {
        script {
          ghaf_robot_test('relay-turnoff')
        }
      }
    }
  }
  post {
    always {
      // Archive Robot-Framework results as artifacts
      archiveArtifacts allowEmptyArchive: true, artifacts: 'Robot-Framework/test-suites/**/*.html, Robot-Framework/test-suites/**/*.xml, Robot-Framework/test-suites/**/*.png, Robot-Framework/test-suites/**/*.txt'
      // Publish all results under Robot-Framework/test-suites subfolders
      step(
        [$class: 'RobotPublisher',
          archiveDirName: 'robot-plugin',
          outputPath: 'Robot-Framework/test-suites',
          outputFileName: '**/output.xml',
          otherFiles: '**/*.png',
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

#!/usr/bin/env groovy

// SPDX-FileCopyrightText: 2022-2025 TII (SSRC) and the Ghaf contributors
// SPDX-License-Identifier: Apache-2.0

////////////////////////////////////////////////////////////////////////////////

def TMP_IMG_DIR = './image'
def CONF_FILE_PATH = '/etc/jenkins/test_config.json'

////////////////////////////////////////////////////////////////////////////////

properties([
  parameters([
    string(name: 'REPO_URL', defaultValue: 'https://github.com/tiiuae/ci-test-automation.git', description: 'Select ci-test-automation repository. Allow testing also with a forked repository'),
    string(name: 'IMG_URL', defaultValue: '', description: 'Target image url. Need to be given if FLASH_AND_BOOT is true'),
    string(name: 'DEVICE_TAG', defaultValue: '', description: 'Need to be given if there is no IMG_URL, and FLASH_AND_BOOT is false, choose: lenovo-x1, dell-7330, orin-agx, orin-agx-64, orin-nx'),
    string(name: 'BRANCH', defaultValue: 'main', description: 'ci-test-automation branch to checkout'),
    string(name: 'TEST_TAGS', defaultValue: '', description: 'Target test tags device need to match with given image URL!(combination of device and tag(s) or just a tag e.g.: bootANDorin-nx, SP-T65, SP-T45ORSP-T60 etc..)'),
    booleanParam(name: 'REFRESH', defaultValue: false, description: 'Read the Jenkins pipeline file and exit, setting the build status to failure.'),
    booleanParam(name: 'FLASH_AND_BOOT', defaultValue: true, description: 'If this is set then image will be downloaded and drive flashed.'),
    booleanParam(name: 'BOOT', defaultValue: false, description: 'If this is set then device will be turned on before tests.'),
    booleanParam(name: 'TURN_OFF', defaultValue: false, description: 'If this is set then device will be turned off after tests or instead of tests if there are no test tags.'),
    booleanParam(name: 'USE_RELAY', defaultValue: true, description: 'If this is set then relay board will be used to cut power from target device when FLASH_AND_BOOT is enabled')
  ])
])

////////////////////////////////////////////////////////////////////////////////

def parse_image_url_and_set_device() {
  def deviceMap = [
    "orin-agx-"           : [name: 'OrinAGX1',     tag: 'orin-agx'],
    "orin-agx64-"         : [name: 'OrinAGX64',    tag: 'orin-agx-64'],
    "orin-nx-"            : [name: 'OrinNX1',      tag: 'orin-nx'],
    "lenovo-x1-"          : [name: 'LenovoX1-1',   tag: 'lenovo-x1'],
    "dell-latitude-7330-" : [name: 'Dell7330',     tag: 'dell-7330']
  ]
  // Parse out the TARGET from the IMG_URL
  if (params.FLASH_AND_BOOT) {
    if(!params.containsKey('IMG_URL')) {
      error("Missing IMG_URL parameter")
    }
    def match
    if ((match = params.IMG_URL =~ /commit_[0-9a-f]{5,40}\/([^\/]+)/)) {
      env.TARGET = "${match.group(1)}"
      match = null

      def found = false
      for (target in deviceMap.keySet()) {
        if (params.IMG_URL.contains(target)) {
          env.DEVICE_NAME = deviceMap[target].name
          env.DEVICE_TAG  = deviceMap[target].tag
          println("Using DEVICE_TAG: ${env.DEVICE_TAG}, DEVICE_NAME: ${env.DEVICE_NAME}")
          found = true
          break
        }
      }

      if (!found) {
        error("Could not determine device from IMG_URL: ${params.IMG_URL}")
      }

    } else {
      error("Unexpected IMG_URL: ${params.IMG_URL}")
    }
  } else {
    // check if DEVICE_TAG is allowed and assign DEVICE_NAME
    def found = false
    for (entry in deviceMap) {
      if (params.DEVICE_TAG == entry.value.tag) {
        env.DEVICE_TAG  = entry.value.tag
        env.DEVICE_NAME = entry.value.name
        println("Using DEVICE_TAG: ${env.DEVICE_TAG}, DEVICE_NAME: ${env.DEVICE_NAME}")
        found = true
        break
      }
    }

    if (!found) {
      error("FLASH_AND_BOOT is false and DEVICE_TAG '${params.DEVICE_TAG}' is not in the allowed list")
    }
  }

  println("Using DEVICE_NAME: ${env.DEVICE_NAME}")
  println("Using DEVICE_TAG: ${env.DEVICE_TAG}")

  return env.DEVICE_TAG
}

def sh_ret_out(String cmd) {
  // Run cmd returning stdout
  return sh(script: cmd, returnStdout:true).trim()
}

def run_wget(String url, String to_dir) {
  // Downlaod `url` setting the directory prefix `to_dir` preserving
  // the hierarchy of directories locally.
  sh "wget --show-progress --progress=dot:giga --force-directories --timestamping -P ${to_dir} ${url}"
  // Re-run wget: this will not re-download anything, it's needed only to
  // get the local path to the downloaded file
  return sh_ret_out("wget --force-directories --timestamping -P ${to_dir} ${url} 2>&1 | grep -Po '${to_dir}[^’]+'")
}

def get_test_conf_property(String file_path, String device, String property) {
  // Get the requested device property data from test_config.json file
  def device_data = readJSON file: file_path
  property_data = "${device_data['addresses'][device][property]}"
  println "Got device '${device}' property '${property}' value: '${property_data}'"
  return property_data
}

def ghaf_robot_test(String test_tags) {
  env.INCLUDE_TEST_TAGS = null
  if (!env.DEVICE_TAG) {
    error("DEVICE_TAG not set")
  }
  if (!env.DEVICE_NAME) {
    error("DEVICE_NAME not set")
  }
  if (test_tags == 'relayboot' || test_tags == 'boot') {
    env.INCLUDE_TEST_TAGS = "${test_tags}AND${env.DEVICE_TAG}"
    println "Run BOOT test with these tags: -i ${env.INCLUDE_TEST_TAGS}"
  } else {
    if (test_tags) {
      env.INCLUDE_TEST_TAGS = "${test_tags}"
      println "Run test with these tags: -i ${test_tags}"
    } else {
      println "Test tags is empty, give test tags as parameter when build this job!"
    }
  }
  dir("Robot-Framework/test-suites") {
    sh 'rm -f *.txt *.png output.xml report.html log.html'
    // On failure, continue the pipeline execution
    if (params.IMG_URL && params.IMG_URL != '') {
      env.COMMIT_HASH = (params.IMG_URL =~ /commit_([a-f0-9]{40})/)[0][1]
      println "Commit hash: ${env.COMMIT_HASH}"
    } else {
      println "No IMG_URL provided, so there is no COMMIT_HASH"
      env.COMMIT_HASH = "NONE"
    }
    try {
      // Pass variables as environment variables to shell.
      // Ref: https://www.jenkins.io/doc/book/pipeline/jenkinsfile/#string-interpolation
      sh '''
        nix run .#ghaf-robot -- \
          -v DEVICE:$DEVICE_NAME \
          -v DEVICE_TYPE:$DEVICE_TAG \
          -v BUILD_ID:${BUILD_NUMBER} \
          -v COMMIT_HASH:$COMMIT_HASH \
          -i $INCLUDE_TEST_TAGS .
      '''
      if (test_tags == 'relayboot' || test_tags == 'boot') {
        // Set an environment variable to indicate boot test passed
        env.BOOT_PASSED = 'true'
      }
    } catch (Exception e) {
      currentBuild.result = "FAILURE"
      unstable("FAILED '${test_tags}': ${e.toString()}")
    } finally {
      // Move the test output (if any) to a subdirectory
      sh """
        rm -fr $test_tags; mkdir -p $test_tags
        mv -f *.txt *.png output.xml report.html log.html $test_tags/ || true
      """
    }
  }
}

////////////////////////////////////////////////////////////////////////////////

pipeline {
  agent { label parse_image_url_and_set_device() }
  stages {
    stage('Refresh') {
      when { expression { params.getOrDefault('REFRESH', false) } }
      steps {
        script {
          currentBuild.displayName = "Refresh pipeline"
        }
        error("Skipping other stages after re-reading the pipeline.")
      }
    }
    stage('Checkout') {
      steps {
        checkout scmGit(
          branches: [[name: "${params.BRANCH}"]],
          userRemoteConfigs: [[url: REPO_URL]]
        )
      }
    }
    stage('Setup') {
      steps {
        script {
          // Set boot test 'true' if boot test is excluded from wanted test set. Will be set to 'false' later if boot test is included and it fails.
          env.BOOT_PASSED = 'true'
          currentBuild.description = "target: ${env.TARGET}, device: ${env.DEVICE_TAG}"
          env.TEST_CONFIG_DIR = 'Robot-Framework/config'
          sh """
            mkdir -p ${TEST_CONFIG_DIR}
            rm -f ${TEST_CONFIG_DIR}/*.json
            ln -sv ${CONF_FILE_PATH} ${TEST_CONFIG_DIR}
            echo { \\\"Job\\\": \\\"${env.TARGET}\\\" } > ${TEST_CONFIG_DIR}/${BUILD_NUMBER}.json
            ls -la ${TEST_CONFIG_DIR}
          """
        }
      }
    }
    stage('Image download') {
      when { expression { params.getOrDefault('FLASH_AND_BOOT', true) } }
      steps {
        script {
          // env.IMG_WGET stores the path to image as downloaded from the remote
          env.IMG_WGET = run_wget(params.IMG_URL, TMP_IMG_DIR)
          println "Downloaded image to workspace: ${env.IMG_WGET}"
          // Verify signature using the tooling from: https://github.com/tiiuae/ci-yubi
          // sig_path = run_wget("${params.IMG_URL}.sig", TMP_IMG_DIR)
          // println "Downloaded signature to workspace: ${sig_path}"
          // sh "nix run github:tiiuae/ci-yubi/bdb2dbf#verify -- --path ${env.IMG_WGET} --sigfile ${sig_path}"
          // Uncompress
          if(env.IMG_WGET.endsWith(".zst")) {
            sh "zstd -dfv ${env.IMG_WGET}"
            // env.IMG_PATH stores the path to the uncompressed image
            env.IMG_PATH = env.IMG_WGET.substring(0, env.IMG_WGET.lastIndexOf('.'))
          } else {
            env.IMG_PATH = env.IMG_WGET
          }
          println "Uncompressed image at: ${env.IMG_PATH}"
        }
      }
    }
    stage('Flash') {
      when { expression { params.getOrDefault('FLASH_AND_BOOT', true) } }
      steps {
        // TODO: We should use ghaf flashing scripts or installers.
        // We don't want to maintain these flashing details here:
        script {
          // Determine mount commands
          if(params.IMG_URL.contains("microchip-icicle-")) {
            muxport = get_test_conf_property(CONF_FILE_PATH, env.DEVICE_NAME, 'usb_sd_mux_port')
            dgrep = 'sdmux'
            mount_cmd = "/run/wrappers/bin/sudo usbsdmux ${muxport} host; sleep 10"
            unmount_cmd = "/run/wrappers/bin/sudo usbsdmux ${muxport} dut"
          } else {
            serial = get_test_conf_property(CONF_FILE_PATH, env.DEVICE_NAME, 'usbhub_serial')
            dgrep = 'PSSD'
            mount_cmd = "/run/wrappers/bin/sudo AcronameHubCLI -u 0 -s ${serial}; sleep 10"
            unmount_cmd = "/run/wrappers/bin/sudo AcronameHubCLI -u 1 -s ${serial}"
          }
          // Mount the target disk
          sh "${mount_cmd}"
          // Read the device name
          dev = get_test_conf_property(CONF_FILE_PATH, env.DEVICE_NAME, 'ext_drive_by-id')
          println "Using device '$dev'"
          // Wipe possible ZFS leftovers, more details here:
          // https://github.com/tiiuae/ghaf/blob/454b18bc/packages/installer/ghaf-installer.sh#L75
          if(params.IMG_URL.contains("lenovo-x1-")) {
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
          sh "/run/wrappers/bin/sudo dd if=${env.IMG_PATH} of=/dev/disk/by-id/${dev} bs=1M status=progress conv=fsync"
          // Unmount
          sh "${unmount_cmd}"
        }
      }
    }
    stage('Boot test') {
      when { expression { params.getOrDefault('FLASH_AND_BOOT', true) || (params.BOOT == true) } }
      steps {
        script {
          env.BOOT_PASSED = 'false'
          if (params.USE_RELAY) {
            ghaf_robot_test('relayboot')
          } else {
            ghaf_robot_test('boot')
          }
          println "Boot test status: ${env.BOOT_PASSED}"
        }
      }
    }
    stage('HW test') {
      when {
        allOf {
          expression { env.BOOT_PASSED == 'true' }
          expression { params.TEST_TAGS }
        }
      }
      steps {
        script {
          println "Test tags: ${params.TEST_TAGS}"
          ghaf_robot_test(params.TEST_TAGS)
        }
      }
    }
    stage('Turn OFF') {
      when { expression { params.getOrDefault('TURN_OFF', true) } }
      steps {
        script {
          if (params.USE_RELAY) {
            ghaf_robot_test('relay-turnoff')
          } else {
            ghaf_robot_test('turnoff')
          }
        }
      }
    }
  }
  post {
    always {
      // Cleanup TMP_IMG_DIR - we preserve the downloaded image from the latest
      // build so it doesn't need to re-downloaded on consecutive (repeated)
      // builds with same image
      script {
        if (env.IMG_WGET != null && !env.IMG_WGET.isEmpty()) {
          // Remove all files except the image (possibly ucompressed)
          sh "find ${TMP_IMG_DIR} -type f ! -path ${env.IMG_WGET} -exec rm -f {} +"
          // Remove any empty directories
          sh "find ${TMP_IMG_DIR} -depth -type d -empty -exec rmdir {} +"
          // Debug print
          sh "find ${TMP_IMG_DIR}"
        }
      }
      script {
        if (env.BOOT_PASSED != null) {
          // Archive Robot-Framework results as artifacts
          def result_dir = null
          if (params.TEST_TAGS) {
            result_dir = params.TEST_TAGS
          } else if (params.TURN_OFF) {
            if (params.USE_RELAY) {
              result_dir = 'relay-turnoff'
            } else {
              result_dir = 'turnoff'
            }
          } else if (params.BOOT) {
            if (params.USE_RELAY) {
              result_dir = 'relayboot'
            } else {
              result_dir = 'boot'
            }
          }

          def archive = "Robot-Framework/test-suites/${result_dir}/**/*.html, Robot-Framework/test-suites/${result_dir}/**/*.xml, Robot-Framework/test-suites/${result_dir}/**/*.png, Robot-Framework/test-suites/${result_dir}/**/*.txt"
          archiveArtifacts allowEmptyArchive: true, artifacts: archive
          // Publish all results under Robot-Framework/test-suites/$result_dir/ subfolders
          step(
            [$class: 'RobotPublisher',
              archiveDirName: 'robot-plugin',
              outputPath: "Robot-Framework/test-suites/${result_dir}",
              outputFileName: '**/**/output.xml',
              otherFiles: '**/**/*.png',
              otherFiles: '**/**/*.txt',
              disableArchiveOutput: false,
              reportFileName: '**/**/report.html',
              logFileName: '**/**/log.html',
              passThreshold: 0,
              unstableThreshold: 0,
              onlyCritical: true,
            ]
          )
        }
      }
    }
  }
}

////////////////////////////////////////////////////////////////////////////////

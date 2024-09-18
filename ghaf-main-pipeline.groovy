#!/usr/bin/env groovy

// SPDX-FileCopyrightText: 2022-2024 TII (SSRC) and the Ghaf contributors
// SPDX-License-Identifier: Apache-2.0

////////////////////////////////////////////////////////////////////////////////

def REPO_URL = 'https://github.com/tiiuae/ghaf/'
def WORKDIR  = 'ghaf'
def PROD_SERVER = 'ghaf-jenkins-controller-dev'

// Utils module will be loaded in the first pipeline stage
def utils = null

properties([
  githubProjectProperty(displayName: '', projectUrlStr: REPO_URL),
])

////////////////////////////////////////////////////////////////////////////////

pipeline {
  agent { label 'built-in' }
  triggers {
     pollSCM('* * * * *')
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
            env.TARGET_COMMIT = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
            env.ARTIFACTS_REMOTE_PATH = "${env.JOB_NAME}/build_${env.BUILD_ID}-commit_${env.TARGET_COMMIT}"

            if (env.EMAIL_FAILURE.toBoolean() && env.EMAIL_FAILURE_MAIL_LIST.size()>0 ) {
              print "Jenkins UI Configured  --> email failure status: ${env.EMAIL_FAILURE}"
              print "Jenkins UI Configured --> email failure (comma separated) email list: ${env.EMAIL_FAILURE_MAIL_LIST}"
              print "Checking commits and committers..."

              def lastSuccessfulBuildID = 0
              def build = currentBuild.previousBuild
              while (build != null) {
                if (build.result == "SUCCESS")
                {
                  lastSuccessfulBuildID = build.id as Integer
                  break
                }
                build = build.previousBuild
              }

              if (lastSuccessfulBuildID) {
                  def lastSuccessfulCommit = sh(script: """
                      curl --run "curl -s ${env.JENKINS_URL}/job/${env.JOB_NAME}/${lastSuccessfulBuildID}/api/json | jq -r '.actions[] | select(.lastBuiltRevision != null) | .lastBuiltRevision.SHA1'"
                  """, returnStdout: true).trim()
                  print "Found last succesfull build number": lastSuccessfulBuildID
                  print "Found last OK commit:${lastSuccessfulCommit}"

                  def commitMessages = sh(script: "git log ${lastSuccessfulCommit}..${env.TARGET_COMMIT} --pretty=format:'%h %an %ae %s'", returnStdout: true).trim()
                  def commitsBetween = sh(script: "git log ${lastSuccessfulCommit}..${env.TARGET_COMMIT} --oneline", returnStdout: true).trim()

                  if (commitsBetween) {
                    print "Found commits between ${lastSuccessfulCommit} and ${env.TARGET_COMMIT}:"
                    print "------------------------------------------------------------------"
                    print "${commitMessages}"
                    env.BETWEEN_COMMITS = commitMessages
                    print "------------------------------------------------------------------"

                    def committers = sh(script: """
                    git log ${lastSuccessfulCommit}..${env.TARGET_COMMIT} --pretty=format:"%an <%ae>"
                    """, returnStdout: true).trim().split('\n').toList().unique()

                    print "Found unique committers:"
                    print "------------------------------------------------------------------"
                    committers.each { committer ->
                    print "${committer}"
                    env.COMMITTERS = committers.join(", ")
                    }
                    print "------------------------------------------------------------------"
                  }
                  else {
                    def onlyCommit = sh(script: "git log ${env.TARGET_COMMIT} -1 --oneline", returnStdout: true).trim()
                    print "No other commits since last ok commit: ${lastSuccessfulCommit} "
                    print "The only commit is:${onlyCommit}"
                    def committer = sh(script: """
                    git log ${env.TARGET_COMMIT} -1 --pretty=format:"%an <%ae>"
                    """, returnStdout: true).trim()
                    print "The only committer: ${committer}"
                    env.COMMITTERS = committer
                  }
              }
            }
          }
        }
      }
    }
    stage('Build x86_64') {
      steps {
        dir(WORKDIR) {
          script {
            utils.nix_build('.#packages.x86_64-linux.nvidia-jetson-orin-agx-debug-from-x86_64', 'archive')
            utils.nix_build('.#packages.x86_64-linux.nvidia-jetson-orin-nx-debug-from-x86_64', 'archive')
            utils.nix_build('.#packages.x86_64-linux.lenovo-x1-carbon-gen11-debug', 'archive')
            utils.nix_build('.#packages.x86_64-linux.microchip-icicle-kit-debug-from-x86_64', 'archive')
            utils.nix_build('.#packages.x86_64-linux.generic-x86_64-debug', 'archive')
            utils.nix_build('.#packages.x86_64-linux.doc')
          }
        }
      }
    }
    stage('Build aarch64') {
      steps {
        dir(WORKDIR) {
          script {
            utils.nix_build('.#packages.aarch64-linux.nvidia-jetson-orin-agx-debug', 'archive')
            utils.nix_build('.#packages.aarch64-linux.nvidia-jetson-orin-nx-debug', 'archive')
            utils.nix_build('.#packages.aarch64-linux.doc')
          }
        }
      }
    }
    stage('HW test') {
      steps {
        dir(WORKDIR) {
          script {
            utils.ghaf_hw_test('.#packages.x86_64-linux.nvidia-jetson-orin-agx-debug-from-x86_64', 'orin-agx')
            utils.ghaf_hw_test('.#packages.aarch64-linux.nvidia-jetson-orin-agx-debug', 'orin-agx')
            utils.ghaf_hw_test('.#packages.x86_64-linux.nvidia-jetson-orin-nx-debug-from-x86_64', 'orin-nx')
            utils.ghaf_hw_test('.#packages.aarch64-linux.nvidia-jetson-orin-nx-debug', 'orin-nx')
            utils.ghaf_hw_test('.#packages.x86_64-linux.lenovo-x1-carbon-gen11-debug', 'lenovo-x1')
            utils.ghaf_hw_test('.#packages.x86_64-linux.generic-x86_64-debug', 'nuc')
            utils.ghaf_hw_test('.#packages.x86_64-linux.microchip-icicle-kit-debug-from-x86_64', 'riscv')
          }
        }
      }
    }
  }

  post {
    failure {
      script {
        githublink="${env.REPO_URL}/commit/${env.TARGET_COMMIT}"
        servername = sh(script: 'uname -n', returnStdout: true).trim()
        def emailPattern = /^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}$/
        def emailArray
        def linefeedList
        if (servername==PROD_SERVER) {

            if (env.EMAIL_FAILURE.toBoolean() && env.EMAIL_FAILURE_MAIL_LIST.size()>0) {
                emailArray = env.EMAIL_FAILURE_MAIL_LIST.split(',').collect { it.trim() }
                linefeedList = env.COMMITTERS.split(',').join('\r\n')

              if (emailArray.size() >0) {
                  emailArray.each { email ->
                    echo "Processing build failure email for: ${email}"

                    if (!(email ==~ emailPattern)) {
                      print "Not correct email format (like abc@domain.tld), skipping this entry!"
                      return
                    }
                    mail to: email,
                    subject: "Failed Jenkins build information",
                    // Left side arrangement is intentional
                    body: """
FAILED build info:

Committers since last ok build are:
---------------------------------------------------------------------------------------------------------------------
${linefeedList}
---------------------------------------------------------------------------------------------------------------------
Latest commit in Github: ${githublink}
---------------------------------------------------------------------------------------------------------------------
Jenkins build: ${env.BUILD_URL}
---------------------------------------------------------------------------------------------------------------------
All commits between latest and last ok build:
${env.BETWEEN_COMMITS}
                """
                  }
              }
              else { print "Error, can't email as there are no comma separated recipent(s) in Jenkins UI global ENV:EMAIL_FAILURE_MAIL_LIST"
              }
            }
            else {print "Email for failed build not configured properly. Either Jenkins UI global ENV:EMAIL_FAILURE not set to be true"
                  print "Or no comma separated email recipient list in Jenkins UI global ENV:EMAIL_FAILURE_MAIL_LIST"
            }

            if (servername==PROD_SERVER) {
              serverchannel="ghaf-jenkins-builds-failed"
              echo "Slack channel:$serverchannel"
              message= "FAIL build: ${servername} ${env.JOB_NAME} [${env.BUILD_NUMBER}] (<${githublink}|The commits>)  (<${env.BUILD_URL}|The Build>)"
              slackSend (
                channel: "$serverchannel",
                color: '#36a64f', // green
                message: message
              )
            }
            else {
              echo "Slack message not sent (failed build). Check pipeline slack configuration!"
            }
          }
        }
      }
    }
  }

////////////////////////////////////////////////////////////////////////////////

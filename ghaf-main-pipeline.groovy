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

// Which attribute of the flake to evaluate for building
def flakeAttr = ".#hydraJobs"

// Target names must be direct children of the above
def targets = [
  [ target: "docs.aarch64-linux",
    hwtest_device: null ],
  [ target: "docs.x86_64-linux",
    hwtest_device: null ],
  [ target: "generic-x86_64-debug.x86_64-linux",
    hwtest_device: "nuc" ],
  [ target: "lenovo-x1-carbon-gen11-debug.x86_64-linux",
    hwtest_device: "lenovo-x1" ],
  [ target: "microchip-icicle-kit-debug-from-x86_64.x86_64-linux",
    hwtest_device: "riscv" ],
  [ target: "nvidia-jetson-orin-agx-debug.aarch64-linux",
    hwtest_device: "orin-agx" ],
  [ target: "nvidia-jetson-orin-agx-debug-from-x86_64.x86_64-linux",
    hwtest_device: "orin-agx" ],
  [ target: "nvidia-jetson-orin-nx-debug.aarch64-linux",
    hwtest_device: "orin-nx" ],
  [ target: "nvidia-jetson-orin-nx-debug-from-x86_64.x86_64-linux",
    hwtest_device: "orin-nx" ],
]

target_jobs = [:]

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

    stage('Evaluate') {
      steps {
        dir(WORKDIR) {
          script {
            // nix-eval-jobs is used to evaluate the given flake attribute, and output target information into jobs.json
            sh "nix-eval-jobs --gc-roots-dir gcroots --flake ${flakeAttr} --force-recurse > jobs.json"

            // jobs.json is parsed using jq. target's name and derivation path are appended as space separated row into jobs.txt
            sh "jq -r '.attr + \" \" + .drvPath' < jobs.json > jobs.txt"

            targets.each {
              def target = it['target']

              // row that matches this target is grepped from jobs.txt, extracting the pre-evaluated derivation path
              def drvPath = sh (script: "cat jobs.txt | grep ${target} | cut -d ' ' -f 2", returnStdout: true).trim()

              target_jobs[target] = {
                stage("Build ${target}") {
                  def opts = ""
                  if (it['hwtest_device'] != null) {
                    opts = "--out-link archive/${target}"
                  } else {
                    opts = "--no-link"
                  }
                  try {
                    if (drvPath) {
                      sh "nix build -L ${drvPath}\\^* ${opts}"
                    } else {
                      error("Target \"${target}\" was not found in ${flakeAttr}")
                    }
                  } catch (InterruptedException e) {
                    throw e
                  } catch (Exception e) {
                    unstable("FAILED: ${target}")
                    currentBuild.result = "FAILURE"
                    println "Error: ${e.toString()}"
                  }
                }

                if (it['hwtest_device'] != null) {
                  stage("Archive ${target}") {
                    script {
                      utils.archive_artifacts("archive", target)
                    }
                  }

                  stage("Test ${target}") {
                    utils.ghaf_parallel_hw_test(target, it['hwtest_device'], '_boot_bat_')
                  }
                }
              }
            }
          }
        }
      }
    }

    stage('Build targets') {
      steps {
        script {
          parallel target_jobs
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

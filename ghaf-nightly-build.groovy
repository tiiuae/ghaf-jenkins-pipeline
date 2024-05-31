#!/usr/bin/env groovy

/*
SPDX-FileCopyrightText: 2022-2024 TII (SSRC) and the Ghaf contributors
SPDX-License-Identifier: Apache-2.0
*/

pipeline {
  agent any
  parameters {
    string name: 'URL', defaultValue: 'https://github.com/tiiuae/ghaf.git'
    string name: 'BRANCH', defaultValue: 'main'
  }
  triggers {
     cron('@midnight')
  }
  options {
    timestamps()
    disableConcurrentBuilds()
    buildDiscarder logRotator(
      artifactDaysToKeepStr: '7',
      artifactNumToKeepStr: '10',
      daysToKeepStr: '70',
      numToKeepStr: '100'
    )
  }
  stages {
    stage('Checkout') {
      steps {
        dir('ghaf') {
          checkout scmGit(
            branches: [[name: params.BRANCH]],
            extensions: [cleanBeforeCheckout()],
            userRemoteConfigs: [[url: params.URL]]
          )
        }
      }
    }
    stage('Build on x86_64') {
      steps {
        script {
          env.ts_build_begin = sh(script: 'date +%s', returnStdout: true).trim()
        }
        dir('ghaf') {
          sh 'nix build -L .#packages.x86_64-linux.nvidia-jetson-orin-agx-debug-from-x86_64 -o result-crosscompile-jetson-orin-agx-debug'
          sh 'nix build -L .#packages.x86_64-linux.nvidia-jetson-orin-nx-debug-from-x86_64  -o result-crosscompile-jetson-orin-nx-debug'
          sh 'nix build -L .#packages.x86_64-linux.generic-x86_64-debug                     -o result-generic-x86_64-debug'
          sh 'nix build -L .#packages.x86_64-linux.lenovo-x1-carbon-gen11-debug             -o result-lenovo-x1-carbon-gen11-debug'
          sh 'nix build -L .#packages.riscv64-linux.microchip-icicle-kit-debug              -o result-microchip-icicle-kit-debug'
          sh 'nix build -L .#packages.aarch64-linux.nxp-imx8mp-evk-debug                    -o result-nxp-imx8mp-evk-debug'
          sh 'nix build -L .#packages.x86_64-linux.doc                                      -o result-doc'
        }
      }
    }
    stage('Build on aarch64') {
      steps {
        dir('ghaf') {
          sh 'nix build -L .#packages.aarch64-linux.nvidia-jetson-orin-agx-debug -o result-aarch64-jetson-orin-agx-debug'
          sh 'nix build -L .#packages.aarch64-linux.nvidia-jetson-orin-nx-debug  -o result-aarch64-jetson-orin-nx-debug'
          sh 'nix build -L .#packages.aarch64-linux.doc                          -o result-aarch64-doc'
        }
        script {
          env.ts_build_finished = sh(script: 'date +%s', returnStdout: true).trim()
        }
      }
    }
    stage('Provenance') {
      environment {
        PROVENANCE_BUILD_TYPE = "https://github.com/tiiuae/ghaf-infra/blob/c8670cf56a7e891545493891928fac96160a49ea/slsa/v1.0/L1/buildtype.md"
        PROVENANCE_BUILDER_ID = "${env.JENKINS_URL}"
        PROVENANCE_INVOCATION_ID = "${env.BUILD_URL}"
        PROVENANCE_TIMESTAMP_BEGIN = "${env.ts_build_begin}"
        PROVENANCE_TIMESTAMP_FINISHED = "${env.ts_build_finished}"
        PROVENANCE_EXTERNAL_PARAMS = sh(
          returnStdout: true,
          // TODO: Target name should be populated from jenkins, but currently that's not possible 
          // since the environment is only generated once instead of being unique for every target
          script: "./provenance-external-params.sh TARGET"
        ).trim()
        PROVENANCE_INTERNAL_PARAMS = ""
      }
      steps {
        dir('ghaf') {
          sh 'nix run github:tiiuae/sbomnix/0b19e055d1f5124fd67d567db342ef4dd21da6f2#provenance -- .#packages.x86_64-linux.nvidia-jetson-orin-agx-debug-from-x86_64 --recursive --out result-provenance-jetson-orin-agx-debug.json'
          sh 'nix run github:tiiuae/sbomnix/0b19e055d1f5124fd67d567db342ef4dd21da6f2#provenance -- .#packages.x86_64-linux.nvidia-jetson-orin-nx-debug-from-x86_64  --recursive --out result-provenance-jetson-orin-nx-debug.json'
          sh 'nix run github:tiiuae/sbomnix/0b19e055d1f5124fd67d567db342ef4dd21da6f2#provenance -- .#packages.x86_64-linux.generic-x86_64-debug                     --recursive --out result-provenance-generic-x86_64-debug.json'
          sh 'nix run github:tiiuae/sbomnix/0b19e055d1f5124fd67d567db342ef4dd21da6f2#provenance -- .#packages.x86_64-linux.lenovo-x1-carbon-gen11-debug             --recursive --out result-provenance-lenovo-x1-carbon-gen11-debug.json'
          sh 'nix run github:tiiuae/sbomnix/0b19e055d1f5124fd67d567db342ef4dd21da6f2#provenance -- .#packages.riscv64-linux.microchip-icicle-kit-debug              --recursive --out result-provenance-microchip-icicle-kit-debug.json'
          sh 'nix run github:tiiuae/sbomnix/0b19e055d1f5124fd67d567db342ef4dd21da6f2#provenance -- .#packages.aarch64-linux.nxp-imx8mp-evk-debug                    --recursive --out result-provenance-nxp-imx8mp-evk-debug.json'
          sh 'nix run github:tiiuae/sbomnix/0b19e055d1f5124fd67d567db342ef4dd21da6f2#provenance -- .#packages.aarch64-linux.nvidia-jetson-orin-agx-debug            --recursive --out result-provenance-aarch64-jetson-orin-agx-debug.json'
          sh 'nix run github:tiiuae/sbomnix/0b19e055d1f5124fd67d567db342ef4dd21da6f2#provenance -- .#packages.aarch64-linux.nvidia-jetson-orin-nx-debug             --recursive --out result-provenance-aarch64-jetson-orin-nx-debug.json'
        }
      }
    } 
    stage('SBOM') {
      steps {
        dir('ghaf') {
          sh 'nix run github:tiiuae/sbomnix/0b19e055d1f5124fd67d567db342ef4dd21da6f2#sbomnix -- .#packages.x86_64-linux.nvidia-jetson-orin-agx-debug-from-x86_64 --csv result-sbom-crosscompile-jetson-orin-agx-debug.csv --cdx result-sbom-crosscompile-jetson-orin-agx-debug.cdx.json --spdx result-sbom-crosscompile-jetson-orin-agx-debug.spdx.json'
          sh 'nix run github:tiiuae/sbomnix/0b19e055d1f5124fd67d567db342ef4dd21da6f2#sbomnix -- .#packages.x86_64-linux.nvidia-jetson-orin-nx-debug-from-x86_64  --csv result-sbom-crosscompile-jetson-orin-nx-debug.csv  --cdx result-sbom-crosscompile-jetson-orin-nx-debug.cdx.json  --spdx result-sbom-crosscompile-jetson-orin-nx-debug.spdx.json'
          sh 'nix run github:tiiuae/sbomnix/0b19e055d1f5124fd67d567db342ef4dd21da6f2#sbomnix -- .#packages.x86_64-linux.generic-x86_64-debug                     --csv result-sbom-generic-x86_64-debug.csv               --cdx result-sbom-generic-x86_64-debug.cdx.json               --spdx result-sbom-generic-x86_64-debug.spdx.json'
          sh 'nix run github:tiiuae/sbomnix/0b19e055d1f5124fd67d567db342ef4dd21da6f2#sbomnix -- .#packages.x86_64-linux.lenovo-x1-carbon-gen11-debug             --csv result-sbom-lenovo-x1-carbon-gen11-debug.csv       --cdx result-sbom-lenovo-x1-carbon-gen11-debug.cdx.json       --spdx result-sbom-lenovo-x1-carbon-gen11-debug.spdx.json'
          sh 'nix run github:tiiuae/sbomnix/0b19e055d1f5124fd67d567db342ef4dd21da6f2#sbomnix -- .#packages.riscv64-linux.microchip-icicle-kit-debug              --csv result-sbom-microchip-icicle-kit-debug.csv         --cdx result-sbom-microchip-icicle-kit-debug.cdx.json         --spdx result-sbom-microchip-icicle-kit-debug.spdx.json'
          sh 'nix run github:tiiuae/sbomnix/0b19e055d1f5124fd67d567db342ef4dd21da6f2#sbomnix -- .#packages.aarch64-linux.nxp-imx8mp-evk-debug                    --csv result-sbom-nxp-imx8mp-evk-debug.csv               --cdx result-sbom-nxp-imx8mp-evk-debug.cdx.json               --spdx result-sbom-nxp-imx8mp-evk-debug.spdx.json'
          sh 'nix run github:tiiuae/sbomnix/0b19e055d1f5124fd67d567db342ef4dd21da6f2#sbomnix -- .#packages.aarch64-linux.nvidia-jetson-orin-agx-debug            --csv result-sbom-aarch64-jetson-orin-agx-debug.csv      --cdx result-sbom-aarch64-jetson-orin-agx-debug.cdx.json      --spdx result-sbom-aarch64-jetson-orin-agx-debug.spdx.json'
          sh 'nix run github:tiiuae/sbomnix/0b19e055d1f5124fd67d567db342ef4dd21da6f2#sbomnix -- .#packages.aarch64-linux.nvidia-jetson-orin-nx-debug             --csv result-sbom-aarch64-jetson-orin-nx-debug.csv       --cdx result-sbom-aarch64-jetson-orin-nx-debug.cdx.json       --spdx result-sbom-aarch64-jetson-orin-nx-debug.spdx.json'
        }
      }
     }
    stage('Vulnxscan runtime') {
      steps {
        dir('ghaf') {
          sh 'nix run github:tiiuae/sbomnix/0b19e055d1f5124fd67d567db342ef4dd21da6f2#vulnxscan -- .#packages.x86_64-linux.nvidia-jetson-orin-agx-debug-from-x86_64 --out result-vulns-jetson-orin-agx-debug.csv'
          sh 'nix run github:tiiuae/sbomnix/0b19e055d1f5124fd67d567db342ef4dd21da6f2#vulnxscan -- .#packages.x86_64-linux.nvidia-jetson-orin-nx-debug-from-x86_64  --out result-vulns-jetson-orin-nx-debug.csv'
          sh 'nix run github:tiiuae/sbomnix/0b19e055d1f5124fd67d567db342ef4dd21da6f2#vulnxscan -- .#packages.x86_64-linux.generic-x86_64-debug                     --out result-vulns-generic-x86_64-debug.csv'
          sh 'nix run github:tiiuae/sbomnix/0b19e055d1f5124fd67d567db342ef4dd21da6f2#vulnxscan -- .#packages.x86_64-linux.lenovo-x1-carbon-gen11-debug             --out result-vulns-lenovo-x1-carbon-gen11-debug.csv'
          sh 'nix run github:tiiuae/sbomnix/0b19e055d1f5124fd67d567db342ef4dd21da6f2#vulnxscan -- .#packages.riscv64-linux.microchip-icicle-kit-debug              --out result-vulns-microchip-icicle-kit-debug.csv'
          sh 'nix run github:tiiuae/sbomnix/0b19e055d1f5124fd67d567db342ef4dd21da6f2#vulnxscan -- .#packages.aarch64-linux.nxp-imx8mp-evk-debug                    --out result-vulns-nxp-imx8mp-evk-debug.csv'
          sh 'nix run github:tiiuae/sbomnix/0b19e055d1f5124fd67d567db342ef4dd21da6f2#vulnxscan -- .#packages.aarch64-linux.nvidia-jetson-orin-agx-debug            --out result-vulns-aarch64-jetson-orin-agx-debug.csv'
          sh 'nix run github:tiiuae/sbomnix/0b19e055d1f5124fd67d567db342ef4dd21da6f2#vulnxscan -- .#packages.aarch64-linux.nvidia-jetson-orin-nx-debug             --out result-vulns-aarch64-jetson-orin-nx-debug.csv'
          sh 'csvcut result-vulns-jetson-orin-agx-debug.csv --not-columns sortcol | csvlook -I > result-vulns-jetson-orin-agx-debug.txt'
          sh 'csvcut result-vulns-jetson-orin-nx-debug.csv --not-columns sortcol | csvlook -I > result-vulns-jetson-orin-nx-debug.txt'
          sh 'csvcut result-vulns-generic-x86_64-debug.csv --not-columns sortcol | csvlook -I > result-vulns-generic-x86_64-debug.txt'
          sh 'csvcut result-vulns-lenovo-x1-carbon-gen11-debug.csv --not-columns sortcol | csvlook -I > result-vulns-lenovo-x1-carbon-gen11-debug.txt'
          sh 'csvcut result-vulns-microchip-icicle-kit-debug.csv --not-columns sortcol | csvlook -I > result-vulns-microchip-icicle-kit-debug.txt'
          sh 'csvcut result-vulns-nxp-imx8mp-evk-debug.csv --not-columns sortcol | csvlook -I > result-vulns-nxp-imx8mp-evk-debug.txt'
          sh 'csvcut result-vulns-aarch64-jetson-orin-agx-debug.csv --not-columns sortcol | csvlook -I > result-vulns-aarch64-jetson-orin-agx-debug.txt'
          sh 'csvcut result-vulns-aarch64-jetson-orin-nx-debug.csv --not-columns sortcol | csvlook -I > result-vulns-aarch64-jetson-orin-nx-debug.txt'
        }
      }
    }
  }
  post {
    always {
      sh "RCLONE_WEBDAV_UNIX_SOCKET_PATH=/run/rclone-jenkins-artifacts.sock RCLONE_WEBDAV_URL=http://localhost rclone sync -L 'ghaf/' :webdav:/${env.BUILD_TAG}/ --include '/result-*' --include '/result-*/**'"
      script {
        currentBuild.description = "<a href=\"/artifacts/${env.BUILD_TAG}/\" target=\"_blank\">📦 Artifacts</a>"
      }
    }
  }
}

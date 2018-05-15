def mysh(cmd) {
    sh('#!/bin/sh -e\n' + cmd)
}

def runSbt(cmd) {
    withCredentials([
        usernamePassword(
            credentialsId: 'jenkins-artifactory',
            passwordVariable: 'DOCKER_PASS',
            usernameVariable: 'DOCKER_USER')
    ]) {
        try {
            mysh "cd project-code && sbt -Dsbt.override.build.repos=true -Dsbt.boot.realm='Artifactory Realm' -Dsbt.boot.host='docker.dev.idnow.de' -Dsbt.boot.user='${env.DOCKER_USER}' -Dsbt.boot.password='${env.DOCKER_PASS}' ${cmd}"
        } catch (err) {
            currentBuild.displayName = "${currentBuild.displayName} : ${cmd} failed\n"
            currentBuild.result = 'FAILED'
        }
    }
}

def isBuildNotFailed() {
    return currentBuild.result == null || currentBuild.result == 'SUCCESS' 
}

pipeline {

    agent any

    options {
        ansiColor('xterm')
        disableConcurrentBuilds()
    }

    stages {
        stage('Patch SBT build') {
            script {
                mysh "echo '\n\npublishTo := Some(\"Artifactory Realm\" at \"https://docker.dev.idnow.de/artifactory/sbt;build.timestamp=\" + new java.util.Date().getTime)' >> project-code/build.sbt"
            }
        }
        stage('Clean') {
            script { 
                runSbt("clean")
            }
        }
        stage('Update') {
            script {
                runSbt("update")
            }

        }
        stage('Compile') {
            script {
                runSbt("compile")
            }
        }
        stage('Package') {
            script {
                runSbt("publishLocal publish")
                archiveArtifacts artifacts: 'project-code/plugin/target/**/play2-war-plugin*.jar', excludes: 'project-code/plugin/target/**/play2-war-plugin*-javadoc.jar, project-code/plugin/target/**/play2-war-plugin*-sources.jar', fingerprint: true
            }
        }
    }
    post {
        always {
            step([$class: 'CordellWalkerRecorder'])
            warnings(
                    canComputeNew: false,
                    canResolveRelativePaths: false,
                    categoriesPattern: '',
                    consoleParsers: [[parserName: 'Scala Compiler (scalac)']],
                    defaultEncoding: '',
                    excludePattern: '',
                    healthy: '',
                    includePattern: '',
                    messagesPattern: '',
                    unHealthy: ''
            )
            cleanWs()
            step([
                    $class                  : 'Mailer',
                    notifyEveryUnstableBuild: true,
                    recipients              : 'dev@idnow.de',
                    sendToIndividuals       : true
            ])
        }
    }
}
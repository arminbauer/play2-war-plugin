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

properties([
		buildDiscarder(logRotator(artifactDaysToKeepStr: '10', artifactNumToKeepStr: '10', daysToKeepStr: '180', numToKeepStr: '10')),
		// [$class: 'ScannerJobProperty', doNotScan: false],
		disableConcurrentBuilds(),
		[
				$class                       : 'ThrottleJobProperty',
				categories                   : [],
				limitOneJobWithMatchingParams: true,
				maxConcurrentPerNode         : 0,
				maxConcurrentTotal           : 0,
				paramsToUseForLimit          : '',
				throttleEnabled              : true,
				throttleOption               : 'project'
		]
])
node {
	throttle(['SBT']) {
		ansiColor('xterm') {
			stage('Clone') {
				checkout([
					$class: 'GitSCM',
					branches: [[name: "*/${env.BRANCH_NAME}"]],
					doGenerateSubmoduleConfigurations: false,
					extensions: [[$class: 'CleanBeforeCheckout']],
					submoduleCfg: [],
					userRemoteConfigs: [[credentialsId: 'jenkins-generated-ssh-key', url: 'ssh://akaempfe@172.17.0.1/idnow/workspace/idnow/play2-war-plugin']]]
				)
			}
			stage('Patch SBT build') {
				mysh "echo '\n\npublishTo := Some(\"Artifactory Realm\" at \"https://docker.dev.idnow.de/artifactory/sbt;build.timestamp=\" + new java.util.Date().getTime)' >> project-code/build.sbt"
			}
			stage('Clean') {
				runSbt("clean")
			}
			stage('Update') {
				runSbt("update")
			}
			stage('Compile') {
				runSbt("compile")
			}
			stage('Package') {
				runSbt("publishLocal publish")
				archiveArtifacts artifacts: 'project-code/plugin/target/**/play2-war-plugin*.jar', excludes: 'project-code/plugin/target/**/play2-war-plugin*-javadoc.jar, project-code/plugin/target/**/play2-war-plugin*-sources.jar', fingerprint: true
			}
		}
		stage('Post') {
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
			step([$class: 'CordellWalkerRecorder'])
			cleanWs()
			// step([
			// 		$class: 'Mailer',
			// 		notifyEveryUnstableBuild: true,
			// 		recipients: 'dev@idnow.de',
			// 		sendToIndividuals: true
			// ])
		}
	}
}
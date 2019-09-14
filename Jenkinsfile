
pipeline {

  agent any

  options {
    ansiColor('xterm')
    disableConcurrentBuilds()
  }
  environment {
    SBT_CREDENTIALS="$HOME/.ivy2/.credentials"
    SBT_OPTS="-Dsbt.override.build.repos=true -Dfile.encoding=UTF8 -Xms4096m -Xmx4096m -Xss1M -XX:+CMSClassUnloadingEnabled -Dsbt.log.noformat=true"
  }

  stages {
    stage ('Patch build.sbt') {
      steps {
        script {
          withCredentials([usernamePassword(credentialsId: 'jenkins-artifactory', passwordVariable: 'DOCKER_PASS', usernameVariable: 'DOCKER_USER')]) {
            sh """
              echo 'publishTo := Some("Artifactory Realm" at "https://${ARTIFACTORY_HOST}/artifactory/sbt;build.timestamp=" + new java.util.Date().getTime)' >> project-code/build.sbt;
              echo 'credentials += Credentials("Artifactory Realm", "${ARTIFACTORY_HOST}", "${env.DOCKER_USER}", "${env.DOCKER_PASS}")' >> project-code/build.sbt;
              echo 'resolvers += "Artifactory" at "https://${ARTIFACTORY_HOST}/artifactory/sbt/"' >> project-code/build.sbt;
            """
          }
        }
      }
    }
    stage('Compile') {
      steps {
        script {
          sh("sbt clean update compile")
        }
      }
    }
    stage('Package') {
      steps {
        script {
          sh ("sbt publishLocal publish")
          archiveArtifacts artifacts: 'project-code/plugin/target/**/play2-war-plugin*.jar', excludes: 'project-code/plugin/target/**/play2-war-plugin*-javadoc.jar, project-code/plugin/target/**/play2-war-plugin*-sources.jar', fingerprint: true
        }
      }
    }
  }
  post {
    always {
      step([$class: 'CordellWalkerRecorder'])
      cleanWs()
    }
  }
}

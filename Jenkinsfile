pipeline {
  agent {
    docker {
      image 'maven:3'
      args '-v /root/.m2/:/root/.m2/'
    }
    
  }
  stages {
    stage('Build') {
      steps {
        sh 'mvn -B clean install'
      }
    }
  }
}
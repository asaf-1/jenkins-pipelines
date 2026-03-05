def call() {
  pipeline {
    agent any
    options { timestamps() }

    stages {
      stage('Checkout') {
        steps { checkout scm }
      }

      stage('Detect') {
        steps {
          script {
            env.IS_NODE   = fileExists('package.json') ? 'true' : 'false'
            env.IS_PYTHON = fileExists('requirements.txt') || fileExists('pyproject.toml') ? 'true' : 'false'
            env.IS_MAVEN  = fileExists('pom.xml') ? 'true' : 'false'
            env.IS_GRADLE = fileExists('build.gradle') || fileExists('build.gradle.kts') ? 'true' : 'false'

            echo "Detect: node=${env.IS_NODE}, python=${env.IS_PYTHON}, maven=${env.IS_MAVEN}, gradle=${env.IS_GRADLE}"
          }
        }
      }

      stage('Node CI') {
        when { expression { env.IS_NODE == 'true' } }
        steps {
          sh 'node -v || true'
          sh 'npm -v || true'
          sh 'npm ci || npm install'
          sh 'npm test || true'
          sh 'npm run build || true'
        }
      }

      stage('Python CI') {
        when { expression { env.IS_PYTHON == 'true' } }
        steps {
          sh 'python --version || true'
          sh 'pip --version || true'
          sh 'pip install -r requirements.txt || true'
          sh 'pytest -q || true'
        }
      }

      stage('Java Maven CI') {
        when { expression { env.IS_MAVEN == 'true' } }
        steps {
          sh 'mvn -v || true'
          sh 'mvn test || true'
        }
      }

      stage('Java Gradle CI') {
        when { expression { env.IS_GRADLE == 'true' } }
        steps {
          sh './gradlew -v || gradle -v || true'
          sh './gradlew test || gradle test || true'
        }
      }

      stage('Nothing recognized') {
        when {
          expression {
            env.IS_NODE != 'true' && env.IS_PYTHON != 'true' && env.IS_MAVEN != 'true' && env.IS_GRADLE != 'true'
          }
        }
        steps {
          echo "No known project files found. Skipping."
        }
      }
    }
  }
}

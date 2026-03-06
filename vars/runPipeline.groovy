def call() {
  pipeline {
    agent any

    options {
      timestamps()
      ansiColor('xterm')
      buildDiscarder(logRotator(numToKeepStr: '20'))
      disableConcurrentBuilds()
    }

    environment {
      // Flags for detection
      IS_NODE   = 'false'
      IS_PYTHON = 'false'
      IS_MAVEN  = 'false'
      IS_GRADLE = 'false'
    }

    stages {

      stage('Checkout') {
        steps {
          checkout scm
        }
      }

      stage('Detect') {
  steps {
    script {
      sh 'pwd'
      sh 'ls -la'

      def hasPackageJson = sh(script: '[ -f package.json ] && echo true || echo false', returnStdout: true).trim() == 'true'
      def hasPnpmLock    = sh(script: '[ -f pnpm-lock.yaml ] && echo true || echo false', returnStdout: true).trim() == 'true'
      def hasYarnLock    = sh(script: '[ -f yarn.lock ] && echo true || echo false', returnStdout: true).trim() == 'true'

      def hasReqs        = sh(script: '[ -f requirements.txt ] && echo true || echo false', returnStdout: true).trim() == 'true'
      def hasPyProject   = sh(script: '[ -f pyproject.toml ] && echo true || echo false', returnStdout: true).trim() == 'true'
      def hasSetupPy     = sh(script: '[ -f setup.py ] && echo true || echo false', returnStdout: true).trim() == 'true'

      def hasPom         = sh(script: '[ -f pom.xml ] && echo true || echo false', returnStdout: true).trim() == 'true'
      def hasGradle      = sh(script: '[ -f build.gradle ] && echo true || echo false', returnStdout: true).trim() == 'true'
      def hasGradleKts   = sh(script: '[ -f build.gradle.kts ] && echo true || echo false', returnStdout: true).trim() == 'true'

      echo "raw detect => packageJson=${hasPackageJson}, pnpm=${hasPnpmLock}, yarn=${hasYarnLock}, reqs=${hasReqs}, pyproject=${hasPyProject}, setupPy=${hasSetupPy}, pom=${hasPom}, gradle=${hasGradle}, gradleKts=${hasGradleKts}"

      env.IS_NODE   = (hasPackageJson || hasPnpmLock || hasYarnLock) ? 'true' : 'false'
      env.IS_PYTHON = (hasReqs || hasPyProject || hasSetupPy) ? 'true' : 'false'
      env.IS_MAVEN  = hasPom ? 'true' : 'false'
      env.IS_GRADLE = (hasGradle || hasGradleKts) ? 'true' : 'false'

      echo "Detect: node=${env.IS_NODE}, python=${env.IS_PYTHON}, maven=${env.IS_MAVEN}, gradle=${env.IS_GRADLE}"
    }
  }
}

      stage('Node CI') {
        when { expression { env.IS_NODE == 'true' } }
        steps {
          sh '''
            set +e
            echo "== Node CI =="

            node -v || true
            npm -v || true

            # install deps
            if [ -f package-lock.json ]; then
              npm ci || npm install
            else
              npm install
            fi

            # run tests (if defined)
            npm test || true

            # build (if defined)
            npm run build || true
          '''
        }
      }

      stage('Python CI') {
        when { expression { env.IS_PYTHON == 'true' } }
        steps {
          sh '''
            set +e
            echo "== Python CI =="

            python --version || true
            pip --version || true

            if [ -f requirements.txt ]; then
              pip install -r requirements.txt || true
            fi

            pytest -q || true
          '''
        }
      }

      stage('Java Maven CI') {
        when { expression { env.IS_MAVEN == 'true' } }
        steps {
          sh '''
            set +e
            echo "== Maven CI =="

            mvn -v || true
            mvn test || true
          '''
        }
      }

      stage('Java Gradle CI') {
        when { expression { env.IS_GRADLE == 'true' } }
        steps {
          sh '''
            set +e
            echo "== Gradle CI =="

            ./gradlew -v || gradle -v || true
            ./gradlew test || gradle test || true
          '''
        }
      }

      // ✅ השדרוג שביקשת: פרסום תוצאות טסטים (JUnit) בג'נקינס
      stage('Publish Test Results') {
        steps {
          // אם אין קובץ XML זה לא יפיל את הבילד
          junit allowEmptyResults: true, testResults: '**/test-results/*.xml, **/junit*.xml, **/TEST-*.xml'
        }
      }

      stage('Nothing recognized') {
        when {
          expression {
            env.IS_NODE != 'true' &&
            env.IS_PYTHON != 'true' &&
            env.IS_MAVEN != 'true' &&
            env.IS_GRADLE != 'true'
          }
        }
        steps {
          echo "No known project files found. Skipping."
        }
      }
    }

    post {
      always {
        echo "Pipeline finished. Check logs above."
      }
    }
  }
}

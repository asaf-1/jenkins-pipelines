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

      def hasPackageJson = sh(script: 'test -f package.json', returnStatus: true) == 0
      def hasPnpmLock    = sh(script: 'test -f pnpm-lock.yaml', returnStatus: true) == 0
      def hasYarnLock    = sh(script: 'test -f yarn.lock', returnStatus: true) == 0

      def hasReqs        = sh(script: 'test -f requirements.txt', returnStatus: true) == 0
      def hasPyProject   = sh(script: 'test -f pyproject.toml', returnStatus: true) == 0
      def hasSetupPy     = sh(script: 'test -f setup.py', returnStatus: true) == 0

      def hasPom         = sh(script: 'test -f pom.xml', returnStatus: true) == 0
      def hasGradle      = sh(script: 'test -f build.gradle', returnStatus: true) == 0
      def hasGradleKts   = sh(script: 'test -f build.gradle.kts', returnStatus: true) == 0

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

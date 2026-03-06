def call() {
  pipeline {
    agent any

    options {
      timestamps()
      ansiColor('xterm')
      buildDiscarder(logRotator(numToKeepStr: '20'))
      disableConcurrentBuilds()
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
          }
        }
      }

      stage('Node CI') {
        when {
          expression {
            sh(script: '[ -f package.json ] || [ -f pnpm-lock.yaml ] || [ -f yarn.lock ]', returnStatus: true) == 0
          }
        }
        steps {
          sh '''
            set +e
            echo "== Node CI =="

            node -v || true
            npm -v || true

            if [ -f package-lock.json ]; then
              npm ci || npm install
            else
              npm install
            fi

            PLAYWRIGHT_PROJECT=false
            if [ -f playwright.config.ts ] || [ -f playwright.config.js ] || [ -f playwright.config.mjs ] || [ -f playwright.config.cjs ]; then
              PLAYWRIGHT_PROJECT=true
            fi

            TEST_SCRIPT_EXISTS=false
            if [ -f package.json ]; then
              node -e "const p=require('./package.json'); process.exit(p.scripts && p.scripts.test ? 0 : 1)" >/dev/null 2>&1
              if [ $? -eq 0 ]; then
                TEST_SCRIPT_EXISTS=true
              fi
            fi

            BUILD_SCRIPT_EXISTS=false
            if [ -f package.json ]; then
              node -e "const p=require('./package.json'); process.exit(p.scripts && p.scripts.build ? 0 : 1)" >/dev/null 2>&1
              if [ $? -eq 0 ]; then
                BUILD_SCRIPT_EXISTS=true
              fi
            fi

            echo "Playwright project: $PLAYWRIGHT_PROJECT"
            echo "Test script exists: $TEST_SCRIPT_EXISTS"
            echo "Build script exists: $BUILD_SCRIPT_EXISTS"

            if [ "$PLAYWRIGHT_PROJECT" = "true" ]; then
              npx playwright --version || true
              PLAYWRIGHT_BROWSERS_PATH=0 npx playwright install
            fi

            if [ "$TEST_SCRIPT_EXISTS" = "true" ]; then
              echo "Running npm test..."
              npm run test || true
            elif [ "$PLAYWRIGHT_PROJECT" = "true" ]; then
              echo "No npm test script found. Running Playwright directly..."
              npx playwright test || true
            else
              echo "No test script found. Skipping tests."
            fi

            if [ "$BUILD_SCRIPT_EXISTS" = "true" ]; then
              echo "Running npm build..."
              npm run build || true
            else
              echo "No build script found. Skipping build."
            fi
          '''
        }
        post {
          always {
            archiveArtifacts artifacts: 'playwright-report/**, test-results/**', allowEmptyArchive: true

            publishHTML(target: [
              allowMissing: true,
              alwaysLinkToLastBuild: true,
              keepAll: true,
              reportDir: 'playwright-report',
              reportFiles: 'index.html',
              reportName: 'Playwright HTML Report'
            ])
          }
        }
      }

      stage('Python CI') {
        when {
          expression {
            sh(script: '[ -f requirements.txt ] || [ -f pyproject.toml ] || [ -f setup.py ]', returnStatus: true) == 0
          }
        }
        steps {
          sh '''
            set +e
            echo "== Python CI =="

            python --version || python3 --version || true
            pip --version || pip3 --version || true

            if [ -f requirements.txt ]; then
              pip install -r requirements.txt || pip3 install -r requirements.txt || true
            fi

            pytest -q || true
          '''
        }
      }

      stage('Java Maven CI') {
        when {
          expression {
            sh(script: '[ -f pom.xml ]', returnStatus: true) == 0
          }
        }
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
        when {
          expression {
            sh(script: '[ -f build.gradle ] || [ -f build.gradle.kts ]', returnStatus: true) == 0
          }
        }
        steps {
          sh '''
            set +e
            echo "== Gradle CI =="

            ./gradlew -v || gradle -v || true
            ./gradlew test || gradle test || true
          '''
        }
      }

      stage('Publish Test Results') {
        steps {
          junit allowEmptyResults: true, testResults: 'test-results/results.xml, **/TEST-*.xml, **/surefire-reports/*.xml'
        }
      }

      stage('Nothing recognized') {
        when {
          expression {
            sh(
              script: '[ ! -f package.json ] && [ ! -f pnpm-lock.yaml ] && [ ! -f yarn.lock ] && [ ! -f requirements.txt ] && [ ! -f pyproject.toml ] && [ ! -f setup.py ] && [ ! -f pom.xml ] && [ ! -f build.gradle ] && [ ! -f build.gradle.kts ]',
              returnStatus: true
            ) == 0
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

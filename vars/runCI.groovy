def call() {
  pipeline {
    agent any

    options {
      timestamps()
      buildDiscarder(logRotator(numToKeepStr: '20'))
      disableConcurrentBuilds()
    }

    environment {
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
            env.IS_NODE   = (fileExists('package.json') || fileExists('yarn.lock') || fileExists('pnpm-lock.yaml')) ? 'true' : 'false'
            env.IS_PYTHON = (fileExists('requirements.txt') || fileExists('pyproject.toml') || fileExists('setup.py')) ? 'true' : 'false'
            env.IS_MAVEN  = fileExists('pom.xml') ? 'true' : 'false'
            env.IS_GRADLE = (fileExists('build.gradle') || fileExists('build.gradle.kts')) ? 'true' : 'false'

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

            # Install deps
            if [ -f package-lock.json ]; then
              npm ci || npm install
            else
              npm install
            fi

            # If Playwright exists in repo, install browsers + deps (won't fail pipeline if not)
            npx playwright --version || true
            npx playwright install --with-deps || true

            # Prefer Playwright tests if available; otherwise npm test (both won't fail pipeline if missing)
            npx playwright test || true
            npm test || true

            # Build is optional
            npm run build || true
          '''
        }
        post {
          always {
            // Save Playwright HTML report folder if it exists
            archiveArtifacts artifacts: 'playwright-report/**', allowEmptyArchive: true

            // Show Playwright HTML report inside Jenkins (requires HTML Publisher plugin)
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
        when { expression { env.IS_PYTHON == 'true' } }
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

      // ✅ JUnit results in Jenkins (works for any framework that outputs JUnit XML)
      stage('Publish Test Results') {
        steps {
          junit allowEmptyResults: true, testResults: '**/test-results/*.xml, **/junit*.xml, **/TEST-*.xml, **/surefire-reports/*.xml'
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
        echo "Pipeline finished."
      }
    }
  }
}

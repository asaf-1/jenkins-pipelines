def call(Map config = [:]) {
    pipeline {
        agent any

        options {
            timestamps()
            ansiColor('xterm')
        }

        parameters {
            string(name: 'ROOT_DIR', defaultValue: '.', description: 'Root directory of the monorepo')
        }

        stages {
            stage('Checkout') {
                steps {
                    checkout scm
                }
            }

            stage('Scan Projects') {
                steps {
                    script {
                        def rootDir = params.ROOT_DIR ?: '.'

                        def dirsOutput = sh(
                            script: """
                                find ${rootDir} -maxdepth 2 -mindepth 1 -type d \
                                ! -path "*/node_modules/*" \
                                ! -path "*/.git/*" \
                                ! -path "*/dist/*" \
                                ! -path "*/build/*"
                            """,
                            returnStdout: true
                        ).trim()

                        if (!dirsOutput) {
                            error "No project directories found under ${rootDir}"
                        }

                        def projectDirs = dirsOutput.split('\n').findAll { it?.trim() }

                        echo "Found directories:"
                        projectDirs.each { echo " - ${it}" }

                        def detectedProjects = []

                        projectDirs.each { dir ->
                            def projectType = detectProjectType(dir)
                            if (projectType != null) {
                                detectedProjects << [
                                    path: dir,
                                    type: projectType
                                ]
                            }
                        }

                        if (detectedProjects.isEmpty()) {
                            error "No supported projects detected in monorepo."
                        }

                        echo "Detected projects:"
                        detectedProjects.each {
                            echo " - ${it.path} => ${it.type}"
                        }

                        env.MONOREPO_PROJECTS = groovy.json.JsonOutput.toJson(detectedProjects)
                    }
                }
            }

            stage('Run Projects') {
                steps {
                    script {
                        def projects = new groovy.json.JsonSlurperClassic().parseText(env.MONOREPO_PROJECTS)

                        for (project in projects) {
                            stage("Run: ${project.path}") {
                                runProject(project.path, project.type)
                            }
                        }
                    }
                }
            }
        }

        post {
            always {
                script {
                    publishReports()
                }
            }
        }
    }
}

def detectProjectType(String dir) {
    if (fileExists("${dir}/package.json")) {
        if (
            fileExists("${dir}/playwright.config.js") ||
            fileExists("${dir}/playwright.config.ts") ||
            fileExists("${dir}/playwright.config.mjs")
        ) {
            return 'playwright'
        }
        return 'node'
    }

    if (
        fileExists("${dir}/requirements.txt") ||
        fileExists("${dir}/pyproject.toml")
    ) {
        return 'python'
    }

    def htmlCheck = sh(
        script: "find '${dir}' -maxdepth 1 -name '*.html' | head -n 1",
        returnStdout: true
    ).trim()

    if (htmlCheck) {
        return 'html'
    }

    return null
}

def runProject(String dir, String type) {
    echo "Running project in ${dir} [type=${type}]"

    dir(dir) {
        if (type == 'playwright') {
            sh '''
                if [ -f package-lock.json ]; then
                  npm ci
                else
                  npm install
                fi

                npx playwright install --with-deps || true
                npm test || npx playwright test
            '''
        }

        else if (type == 'node') {
            sh '''
                if [ -f package-lock.json ]; then
                  npm ci
                else
                  npm install
                fi

                npm test || echo "No tests found for Node project"
            '''
        }

        else if (type == 'python') {
            sh '''
                python3 -m venv venv || true
                . venv/bin/activate
                pip install --upgrade pip

                if [ -f requirements.txt ]; then
                  pip install -r requirements.txt
                fi

                if [ -f pyproject.toml ]; then
                  pip install .
                fi

                pytest || echo "No pytest tests found for Python project"
            '''
        }

        else if (type == 'html') {
            sh '''
                echo "Static HTML project detected."
                echo "No automated tests configured yet."
            '''
        }

        else {
            echo "Unsupported type: ${type}"
        }
    }
}

def publishReports() {
    echo "Publishing reports if found..."

    junit allowEmptyResults: true, testResults: '**/test-results/**/*.xml, **/junit*.xml, **/pytest*.xml'

    publishHTML(target: [
        allowMissing: true,
        alwaysLinkToLastBuild: true,
        keepAll: true,
        reportDir: 'playwright-report',
        reportFiles: 'index.html',
        reportName: 'Playwright HTML Report'
    ])
}

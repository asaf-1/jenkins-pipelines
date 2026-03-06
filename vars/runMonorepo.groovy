import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic

def call(Map config = [:]) {
    def rootDir = (config.rootDir ?: '.').toString()

    pipeline {
        agent any

        options {
            skipDefaultCheckout(true)
            timestamps()
            ansiColor('xterm')
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
                        def candidateDirs = scanCandidateDirs(rootDir)

                        echo 'Candidate directories after skip rules:'
                        candidateDirs.each { echo " - ${it}" }

                        def detectedProjects = []

                        candidateDirs.each { projectPath ->
                            def projectType = detectProjectType(projectPath)
                            if (projectType) {
                                detectedProjects << [
                                    path: projectPath,
                                    type: projectType
                                ]
                            }
                        }

                        detectedProjects = dedupeProjects(detectedProjects)
                        detectedProjects = sortProjectsForExecution(detectedProjects)

                        if (detectedProjects.isEmpty()) {
                            error "No supported projects were detected under ${rootDir}"
                        }

                        echo 'Detected buildable projects (execution order):'
                        detectedProjects.each { echo " - ${it.path} => ${it.type}" }

                        writeFile(
                            file: '.monorepo-projects.json',
                            text: JsonOutput.prettyPrint(JsonOutput.toJson(detectedProjects))
                        )
                    }
                }
            }

            stage('Run Projects') {
                steps {
                    script {
                        def projects = new JsonSlurperClassic().parseText(readFile('.monorepo-projects.json'))

                        for (def project in projects) {
                            stage("Run: ${project.path}") {
                                catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                                    runProject(project.path as String, project.type as String)
                                }
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

def scanCandidateDirs(String rootDir) {
    def quotedRoot = shellQuote(rootDir)

    def output = sh(
        script: "find ${quotedRoot} -maxdepth 4 -type d | sort",
        returnStdout: true
    ).trim()

    def dirs = ['.']

    if (output) {
        dirs.addAll(
            output
                .split('\\r?\\n')
                .collect { normalizePath(it) }
                .findAll { it }
        )
    }

    dirs = dirs.findAll { !shouldSkipPath(it) }.unique()

    def rest = dirs.findAll { it != '.' }.sort()
    return ['.'] + rest
}

def shouldSkipPath(String path) {
    def p = normalizePath(path)

    if (p == '.') {
        return false
    }

    if (p == './Python_Projects') {
        return true
    }

    if (p.contains('@tmp')) {
        return true
    }

    def blockedSegments = [
        '.git',
        '.github',
        'node_modules',
        'dist',
        'build',
        '.venv',
        'venv',
        'tests',
        'e2e',
        'SCAPPER PROJECT',
        'Reports',
        'playwright-report',
        'test-results'
    ]

    def segments = p.tokenize('/')

    return segments.any { blockedSegments.contains(it) }
}

def detectProjectType(String projectPath) {
    if (
        fileExists("${projectPath}/package.json") &&
        (
            fileExists("${projectPath}/playwright.config.ts") ||
            fileExists("${projectPath}/playwright.config.js") ||
            fileExists("${projectPath}/playwright.config.mjs") ||
            fileExists("${projectPath}/playwright.config.cjs")
        )
    ) {
        return 'playwright'
    }

    if (fileExists("${projectPath}/package.json")) {
        return 'node'
    }

    if (
        fileExists("${projectPath}/requirements.txt") ||
        fileExists("${projectPath}/pyproject.toml")
    ) {
        return 'python'
    }

    if (hasTopLevelPythonFiles(projectPath)) {
        return 'python'
    }

    return null
}

def sortProjectsForExecution(List projects) {
    return projects.sort { a, b ->
        def pa = projectPriority(a.path as String, a.type as String)
        def pb = projectPriority(b.path as String, b.type as String)

        if (pa != pb) {
            return pa <=> pb
        }

        return (a.path as String) <=> (b.path as String)
    }
}

def projectPriority(String path, String type) {
    if (type == 'python') return 1
    if (type == 'node') return 2
    if (type == 'playwright' && path != '.') return 3
    if (type == 'playwright' && path == '.') return 4
    return 5
}

def runProject(String projectPath, String projectType) {
    echo "Running project in ${projectPath} [type=${projectType}]"

    dir(projectPath) {
        if (projectType == 'playwright') {
            sh '''
                echo "Installing dependencies for Playwright project..."
                if [ -f package-lock.json ]; then
                  npm ci
                else
                  npm install
                fi

                echo "Installing Chromium only (faster for CI)..."
                npx playwright install chromium || true
            '''

            timeout(time: 8, unit: 'MINUTES') {
                withCredentials([
                    string(credentialsId: 'google-sheet-id', variable: 'GOOGLE_SHEET_ID'),
                    string(credentialsId: 'google-sheet-tab', variable: 'GOOGLE_SHEET_TAB'),
                    file(credentialsId: 'google-service-account-json', variable: 'GOOGLE_SA_FILE')
                ]) {
                    if (fileExists('playwright.monorepo.config.ts')) {
                        sh '''
                            export SHEET_ID="$GOOGLE_SHEET_ID"
                            export GOOGLE_SHEET_ID="$GOOGLE_SHEET_ID"
                            export GOOGLE_SHEET_TAB="$GOOGLE_SHEET_TAB"
                            export GOOGLE_SA_PATH="$GOOGLE_SA_FILE"
                            export GOOGLE_APPLICATION_CREDENTIALS="$GOOGLE_SA_FILE"

                            echo "Running Playwright with monorepo config, Chromium only, no retries..."
                            npx playwright test -c playwright.monorepo.config.ts --project=chromium --workers=1 --retries=0
                        '''
                    } else {
                        sh '''
                            export SHEET_ID="$GOOGLE_SHEET_ID"
                            export GOOGLE_SHEET_ID="$GOOGLE_SHEET_ID"
                            export GOOGLE_SHEET_TAB="$GOOGLE_SHEET_TAB"
                            export GOOGLE_SA_PATH="$GOOGLE_SA_FILE"
                            export GOOGLE_APPLICATION_CREDENTIALS="$GOOGLE_SA_FILE"

                            echo "Running Playwright with default config, Chromium only, no retries..."
                            npx playwright test --project=chromium --workers=1 --retries=0
                        '''
                    }
                }
            }
        }

        else if (projectType == 'node') {
            sh '''
                echo "Installing dependencies for Node project..."
                if [ -f package-lock.json ]; then
                  npm ci
                else
                  npm install
                fi
            '''

            def executedSomething = false

            if (hasPackageScript('test')) {
                executedSomething = true
                sh '''
                    echo "Running npm test..."
                    npm test
                '''
            }

            if (hasPackageScript('build')) {
                executedSomething = true
                sh '''
                    echo "Running npm run build..."
                    npm run build
                '''
            }

            if (!executedSomething) {
                echo 'No test/build script found in package.json. Dependency installation completed, skipping execution.'
            }
        }

        else if (projectType == 'python') {
            sh '''
                echo "Preparing Python environment..."
                python3 --version
                python3 -m venv .jenkins-venv
                . .jenkins-venv/bin/activate
                python -m pip install --upgrade pip

                if [ -f requirements.txt ]; then
                  echo "Installing requirements.txt..."
                  pip install -r requirements.txt
                fi

                if [ -f pyproject.toml ]; then
                  echo "pyproject.toml found. Trying local install..."
                  pip install . || true
                fi
            '''

            if (hasPythonTests()) {
                sh '''
                    echo "Python tests detected. Running pytest..."
                    . .jenkins-venv/bin/activate

                    if ! python -c "import pytest" >/dev/null 2>&1; then
                      pip install pytest
                    fi

                    mkdir -p test-results
                    pytest --junitxml=test-results/pytest-results.xml
                '''
            } else {
                sh '''
                    echo "No pytest tests detected. Running syntax compilation on top-level Python files..."
                    . .jenkins-venv/bin/activate

                    find . -maxdepth 1 -type f -name "*.py" | while IFS= read -r file; do
                      echo "Compiling ${file}"
                      python -m py_compile "$file"
                    done
                '''
            }
        }

        else {
            echo "Unsupported project type: ${projectType}"
        }
    }
}

def hasPackageScript(String scriptName) {
    return sh(
        script: "node -e \"const fs=require('fs'); const p=JSON.parse(fs.readFileSync('package.json','utf8')); process.exit(p.scripts && p.scripts['${scriptName}'] ? 0 : 1)\"",
        returnStatus: true
    ) == 0
}

def hasTopLevelPythonFiles(String projectPath) {
    def quotedPath = shellQuote(projectPath)

    return sh(
        script: "find ${quotedPath} -maxdepth 1 -type f -name \"*.py\" | head -n 1",
        returnStdout: true
    ).trim()
}

def hasPythonTests() {
    return sh(
        script: "find . -maxdepth 2 -type f \\( -name \"test_*.py\" -o -name \"*_test.py\" \\) | head -n 1",
        returnStdout: true
    ).trim()
}

def publishReports() {
    echo 'Publishing reports if they exist...'

    def xmlFound = sh(
        script: "find . -type f -name \"*.xml\" | head -n 1",
        returnStdout: true
    ).trim()

    if (xmlFound) {
        junit(
            allowEmptyResults: true,
            testResults: '**/test-results/**/*.xml, **/junit*.xml, **/pytest*.xml, **/results.xml'
        )
    } else {
        echo 'No XML test reports found.'
    }

    def htmlReports = sh(
        script: "find . -type f -path \"*/playwright-report/index.html\" | sort",
        returnStdout: true
    ).trim()

    if (!htmlReports) {
        echo 'No Playwright HTML reports found.'
        return
    }

    htmlReports
        .split('\\r?\\n')
        .collect { it.trim() }
        .findAll { it }
        .each { reportFile ->
            def reportDir = reportFile.replaceAll('/index\\.html$', '')
            def reportName = reportDir == './playwright-report'
                ? 'Playwright HTML Report (root)'
                : "Playwright HTML Report - ${reportDir}".replaceAll('[^A-Za-z0-9 _\\-().]', '_')

            publishHTML(target: [
                allowMissing: true,
                alwaysLinkToLastBuild: true,
                keepAll: true,
                reportDir: reportDir,
                reportFiles: 'index.html',
                reportName: reportName
            ])
        }
}

def dedupeProjects(List projects) {
    def seen = [] as Set
    def result = []

    projects.each { project ->
        if (!seen.contains(project.path)) {
            seen << project.path
            result << project
        }
    }

    return result
}

def normalizePath(String path) {
    if (path == null) {
        return null
    }

    def p = path.trim().replace('\\', '/')

    if (!p) {
        return null
    }

    if (p == '.' || p == './.') {
        return '.'
    }

    if (p.endsWith('/')) {
        p = p[0..-2]
    }

    if (!p.startsWith('.') && !p.startsWith('/')) {
        p = "./${p}"
    }

    return p
}

def shellQuote(String value) {
    return "'${value.replace("'", "'\"'\"'")}'"
}

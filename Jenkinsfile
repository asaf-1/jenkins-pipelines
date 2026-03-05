pipeline {
    agent any

    stages {

        stage('Checkout') {
            steps {
                checkout scm
            }
        }

        stage('Detect Project') {
            steps {
                script {

                    if (fileExists('package.json')) {
                        env.PROJECT_TYPE = "node"
                    }
                    else if (fileExists('requirements.txt')) {
                        env.PROJECT_TYPE = "python"
                    }
                    else if (fileExists('pom.xml')) {
                        env.PROJECT_TYPE = "java"
                    }
                    else {
                        env.PROJECT_TYPE = "static"
                    }

                    echo "Detected project: ${env.PROJECT_TYPE}"
                }
            }
        }

        stage('Node Build') {
            when { expression { env.PROJECT_TYPE == "node" } }
            steps {
                sh 'npm install'
                sh 'npm test || true'
            }
        }

        stage('Python Build') {
            when { expression { env.PROJECT_TYPE == "python" } }
            steps {
                sh 'pip install -r requirements.txt'
                sh 'pytest || true'
            }
        }

        stage('Java Build') {
            when { expression { env.PROJECT_TYPE == "java" } }
            steps {
                sh 'mvn clean install'
            }
        }

        stage('Static Project') {
            when { expression { env.PROJECT_TYPE == "static" } }
            steps {
                echo "Static project - nothing to build"
            }
        }

    }
}

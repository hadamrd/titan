// Titan e2e fixture pipeline — the Groovy synthesis form.
//
// The builder-API equivalent of titan-pipeline.yml. FixturePipelineEquivalenceTest
// synthesises this and asserts the resulting PipelineModel matches the one the YAML
// parser produces from titan-pipeline.yml — proving Groovy synthesis is at feature
// parity with the YAML grammar.
//
// Strings carrying ${{ ... }} are single-quoted: a Groovy double-quoted string would
// try to interpolate them.

agent 'linux'
failurePolicy 'blockOnFailure'

parameter name: 'notifyUrl', type: 'string', defaultValue: '',
        description: 'Optional webhook the Notify stage POSTs the build report to.'

cron 'H/15 * * * *'

stage('Checkout') {
    checkout url: 'https://github.com/hadamrd/titan-e2e-fixture.git', branch: 'main'
}

// The two stacks are parallel chains off Checkout; each chain is sequential.
parallel {
    branch {
        stage('Backend Lint') {
            image 'maven:3.9-eclipse-temurin-17'
            sh 'mvn -B -ntp -Dmaven.repo.local=backend/.m2-repo -f backend/pom.xml checkstyle:check'
        }
        stage('Backend Test') {
            image 'maven:3.9-eclipse-temurin-17'
            sh 'mvn -B -ntp -Dmaven.repo.local=backend/.m2-repo -f backend/pom.xml test'
            junit testResults: 'backend/target/surefire-reports/TEST-*.xml'
        }
        stage('Backend Build') {
            image 'maven:3.9-eclipse-temurin-17'
            sh 'mvn -B -ntp -Dmaven.repo.local=backend/.m2-repo -f backend/pom.xml package -DskipTests'
            archiveArtifacts artifacts: 'backend/target/titan-e2e-backend.jar'
        }
    }
    branch {
        stage('Frontend Lint') {
            image 'node:20-alpine'
            sh 'cd frontend && npm install --no-audit --no-fund && npm run lint'
        }
        stage('Frontend Test') {
            image 'node:20-alpine'
            sh 'cd frontend && npm test'
            junit testResults: 'frontend/test-results/junit.xml'
        }
        stage('Frontend Build') {
            image 'node:20-alpine'
            sh 'cd frontend && npm run build'
            archiveArtifacts artifacts: 'frontend/dist/**'
        }
    }
}

gate name: 'Publish Approval', requiresApproval: true, approvers: ['admin']

stage('Report') {
    setOutput values: [
            result: 'passed',
            backendArtifact: 'backend/target/titan-e2e-backend.jar',
            frontendArtifact: 'frontend/dist',
    ]
}

stage('Notify') {
    when "params.notifyUrl != ''"
    httpRequest url: '${{ params.notifyUrl }}',
            method: 'POST',
            contentType: 'application/json',
            body: '{"text":"titan-e2e-fixture build ${{ steps[\'Report\'].outputs.result }} ✅"}'
}

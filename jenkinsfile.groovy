def projectKey = gitProperties.getGitProjectKey()
echo "projectKey = ${projectKey}"
def repoName = gitProperties.getGitRepoName()
echo "repoName = ${repoName}"
def helmChartFileName = "helm/${artifactId}" as Object
echo "helmChartFileName = ${helmChartFileName}"
def gitCommitHash = sh(script: "git rev-parse --short=8 HEAD", returnStdout: true).trim()
echo "gitCommitHash = ${gitCommitHash}"
def projectNameWithCommitHash = "${artifactId}-${gitCommitHash}" as Object
echo "projectNameWithCommitHash = ${projectNameWithCommitHash}"

stage("Unit tests") {
    echo "mvn clean test"
    String mavenTestGoals = "clean org.jacoco:jacoco-maven-plugin:0.8.5:prepare-agent test -Dimage.tag=${imageTag} -Dgit.tag=${BRANCH_NAME}-${BUILD_NUMBER}"
    arty.mvn.run pom: pom_location, goals: mavenTestGoals
    testingReporter.maven("UNIT",imageTag)
}

stage("Build a War file") {
    echo "mvn package"
    String mavenBuildGoals = "package -DskipTests -Dimage.tag=${imageTag} -Dgit.tag=${BRANCH_NAME}-${BUILD_NUMBER}"
    arty.mvn.deployer.deployArtifacts = false
    arty.mvn.run pom: pom_location, goals: mavenBuildGoals
}

stage("Build docker image") {
    echo "Docker building"
    sh "docker build -t ${imageNameAndTag} --build-arg JAR_PATH=target/*.jar ."
    sh "docker tag ${dockerImageRepository}:${imageTag} ${dockerImageRepository}:${imageTagLatest}"
}

stage("Push docker image") {
    try {
        echo "Docker push"
        withCredentials([usernamePassword(credentialsId: artyUser, usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
            dockerPush(USERNAME, PASSWORD, dockerImageRepository, imageTag)
            dockerPush(USERNAME, PASSWORD, dockerImageRepository, "${imageTagLatest}")
        }


    } finally {
        echo "finally"
        sh "docker rmi ${dockerImageRepository}:${imageTag}"
        sh "docker rmi ${dockerImageRepository}:${imageTagLatest}"
    }

}

stage("Build chart") {
    echo "Create Chart Artifact"
    helm.packChart(helmChartFileName, "${imageTag}")
}

stage("Push chart to Nexus") {
    echo "Push Chart to Nexus"
    def haArtifactoryServer = Nexus.server "http://localhost:8081/"
    def helmChartFileNamePackaged = helmChartFileName.tokenize('/').last() + "-" + imageTag + ".tgz"
    artifactory.deployFile(haArtifactoryServer, helmRepoPrefix, helmChartFileNamePackaged)
    fullURL = "${Nexus}/${helmRepoPrefix}/${helmChartFileNamePackaged}"
    echo "fullURL = ${fullURL}"
}

stage("Run Sonarqube Scan") {
    sonarQubeScan(projectKey, repoName, BRANCH_NAME, emails)
}




/*
 * titan-artifact-s3 — ArtifactStore backend for S3 / Cloudflare R2 (design/46).
 * Uses AWS SDK v2 with url-connection-client (no Netty/Apache).
 */

plugins {
    id("titan.java-library")
}

group = "io.adaptiq.titan"
version = "1.0.0-rc1"

// AWS SDK v2 BOM — aligns s3 + url-connection-client to one version.
dependencies {
    api(platform(libs.aws.bom))

    implementation(project(":titan-pipeline-model"))
    implementation(libs.aws.s3) {
        // Exclude heavier HTTP clients — url-connection-client is sufficient
        // and avoids classpath conflicts (design/46 D3).
        exclude(group = "software.amazon.awssdk", module = "apache-client")
        exclude(group = "software.amazon.awssdk", module = "netty-nio-client")
    }
    implementation(libs.awsUrlConnectionClient)
    compileOnly(libs.spotbugs.annotations)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.testcontainers.junit)
    testImplementation(libs.testcontainers.minio)
}

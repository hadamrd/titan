/*
 * titan-pipeline-model — shared pipeline-definition parser and model.
 * Depends on Jackson + Groovy AST.
 *
 * JaCoCo floors: LINE 84%, BRANCH 70% baseline; set to baseline minus ~5 points.
 */

plugins {
    id("titan.java-library")
}

group = "io.adaptiq.titan"
version = "1.0.0-rc1"

// JaCoCo coverage floors (preserved from pom.xml)
ext["jacocoMinLineCoverage"]   = "0.75"
ext["jacocoMinBranchCoverage"] = "0.65"

dependencies {
    implementation(libs.jackson.databind)
    implementation(libs.jackson.dataformat.yaml)
    compileOnly(libs.spotbugs.annotations)
    implementation(libs.groovy)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.networknt.schema)
}

// design/47 §5 — regenerate the committed titan-pipeline.schema.json. Removed after the run.
tasks.register<JavaExec>("regenSchema") {
    group = "build"
    description = "Regenerate titan-pipeline.schema.json from TitanGrammar + scopes."
    mainClass.set("io.adaptiq.titan.flow.parser.TitanSchemaGenerator")
    classpath = sourceSets.main.get().runtimeClasspath
    args = listOf("${rootProject.projectDir}")
}

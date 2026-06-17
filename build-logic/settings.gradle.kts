/*
 * build-logic — composite build that supplies the titan.java-library
 * convention plugin to every Gradle module in this repo.
 */
dependencyResolutionManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
    versionCatalogs {
        // build-logic lives one level below the root, so point explicitly to
        // the root catalog file (auto-discovery only works for the root build).
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"

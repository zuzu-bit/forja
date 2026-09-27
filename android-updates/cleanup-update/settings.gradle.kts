pluginManagement {
    repositories {
        google { content { includeGroupByRegex("androidx.*"); includeGroupByRegex("com\\.android.*"); includeGroupByRegex("com\\.google.*") } }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        google { content { includeGroupByRegex("androidx.*"); includeGroupByRegex("com\\.android.*"); includeGroupByRegex("com\\.google\\.android.*"); includeGroupByRegex("com\\.google\\.mlkit.*"); includeGroupByRegex("com\\.google\\.firebase.*"); includeGroupByRegex("com\\.google\\.testing.*") } }
        mavenCentral()
    }
}
rootProject.name = "forja-cleanup-update"
include(":feature")

// Separate rendering probe; never part of the delivered feature/APK.
if (providers.gradleProperty("forjaQaProbe").isPresent) {
    include(":qaProbe")
    project(":qaProbe").projectDir = file("../repair-update/qa")
}

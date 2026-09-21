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

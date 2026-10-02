rootProject.name = "fitkingia"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// core      → domínio puro (modelos + motores determinísticos). Sem IO, sem Android.
// knowledge → banco de conhecimento: seeds JSON → fitness.db (SQLite) → KnowledgeBase.
// cli       → ferramenta de linha de comando para exercitar os motores.
include(":core", ":knowledge", ":cli")

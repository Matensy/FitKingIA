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
// coach     → IA local (offline, sem LLM pago): entende português, conversa e chama o motor.
// cli       → ferramenta de linha de comando para exercitar os motores.
include(":core", ":knowledge", ":coach", ":cli")

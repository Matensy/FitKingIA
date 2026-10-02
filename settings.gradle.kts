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
// appcore   → lógica do app sem Android: questionário só de toque, user.db, casos de uso.
// app       → aplicativo Android (APK) com questionário só de toque; build sem Android Gradle Plugin.
include(":core", ":knowledge", ":coach", ":cli", ":appcore", ":app")

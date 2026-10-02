plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Bytecode Java 11: o maior que o ProGuard consegue rebaixar para o dx do APK (ver app/build.gradle.kts).
java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
    }
}

// Lógica do aplicativo sem Android: questionário, user.db e casos de uso. Testável na JVM.
dependencies {
    api(project(":knowledge"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.params)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}

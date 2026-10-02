plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// Bytecode Java 17: mesmo alvo que o futuro app Android consumirá.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":core"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.sqlite.jdbc)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.junit.params)
    testRuntimeOnly(libs.junit.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// Gera o banco de conhecimento distribuível (fitness.db) a partir dos seeds JSON.
val buildKnowledgeDb by tasks.registering(JavaExec::class) {
    group = "fitkingia"
    description = "Gera build/fitness.db a partir de src/main/resources/knowledge/*.json"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.fitkingia.knowledge.BuildKnowledgeDbKt")
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
    val out = layout.buildDirectory.file("fitness.db")
    args(out.get().asFile.absolutePath)
    outputs.file(out)
    inputs.dir("src/main/resources")
}

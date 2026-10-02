plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
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

dependencies {
    implementation(project(":knowledge"))
    implementation(project(":coach"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
}

application {
    mainClass.set("com.fitkingia.cli.MainKt")
    applicationName = "fitking"
    applicationDefaultJvmArgs = listOf("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    workingDir = rootProject.projectDir
}

tasks.test {
    useJUnitPlatform()
}

// O perfil de exemplo (examples/) vai no jar para o comando `demo` funcionar sem argumentos.
sourceSets {
    main {
        resources.srcDir(rootProject.file("examples"))
    }
}

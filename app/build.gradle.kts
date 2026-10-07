import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.io.ByteArrayOutputStream
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// ---------------------------------------------------------------------------------------------
// App Android SEM o Android Gradle Plugin.
//
// O SDK do Google não é baixável neste ambiente, então o APK é montado com as ferramentas
// livres do Debian/Ubuntu (apt: aapt dalvik-exchange libandroid-23-java zipalign apksigner):
//
//   Kotlin (JVM) ──► jar único ──► ProGuard (backport p/ Java 7: lambdas/indy viram classes)
//        ──► dx (DEX, minSdk 26) ──► aapt2 (manifesto + recursos + assets/fitness.db)
//        ──► zip classes*.dex ──► zipalign ──► apksigner (v1 + v2)
//
// A UI é feita com Views criadas em código (sem XML de layout, sem AndroidX), o que dispensa
// bibliotecas do maven.google.com. Compila contra android.jar (API 23) e roda em Android 8+.
// ---------------------------------------------------------------------------------------------

plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

val appVersionName = "0.2.0"
val appVersionCode = 2
val minSdk = 26
val targetSdk = 34

val sdkDir: String = providers.environmentVariable("ANDROID_SDK_DIR").getOrElse("/usr/lib/android-sdk")
val androidJar = file("$sdkDir/platforms/android-23/android.jar")
val buildTools = file("$sdkDir/build-tools/debian")
val dxJar: String = providers.environmentVariable("DX_JAR").getOrElse("/usr/share/java/com.android.dx.jar")
val zipalignBin: String = providers.environmentVariable("ZIPALIGN").getOrElse("/usr/bin/zipalign")
val apksignerBin: String = providers.environmentVariable("APKSIGNER").getOrElse("/usr/bin/apksigner")

val proguard: Configuration by configurations.creating

interface InjectedExec { @get:Inject val ops: ExecOperations }
val execOps = objects.newInstance<InjectedExec>().ops

dependencies {
    compileOnly(files(androidJar))
    implementation(project(":appcore")) {
        exclude(group = "org.xerial") // JDBC só no JVM; no Android o SQLite é o do sistema.
    }
    proguard(libs.proguard.base)

    testImplementation(files(androidJar)) // como no AGP: stubs no classpath; o Robolectric troca pelo framework real
    testImplementation(libs.robolectric) {
        exclude(group = "androidx.test") // só existe no Maven do Google
        exclude(group = "androidx.test.espresso")
    }
    testImplementation(libs.junit4)
    testImplementation(libs.sqlite.jdbc)
}

tasks.test {
    useJUnit()
    systemProperty("robolectric.logging", "stderr")
    // ./gradlew :app:test -Pscreenshots → capturas das telas em app/build/screenshots
    if (project.hasProperty("screenshots")) {
        systemProperty("screenshots", "true")
        systemProperty("screenshotDir", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
        outputs.upToDateWhen { false }
    }
}

val apkDir = layout.buildDirectory.dir("apk")

// 1) Um jar com o app e todas as dependências de runtime.
val fatJar by tasks.registering(Jar::class) {
    group = "apk"
    archiveFileName.set("app-all.jar")
    destinationDirectory.set(apkDir)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    // Só classes interessam ao dx; versões multi-release e module-info são de Java 9+.
    include("**/*.class")
    exclude("META-INF/**", "module-info.class")
}

// 2) Backport para bytecode Java 7: o ART não tem LambdaMetafactory/StringConcatFactory.
val backport by tasks.registering(JavaExec::class) {
    group = "apk"
    val input = fatJar.flatMap { it.archiveFile }
    val output = apkDir.map { it.file("app-backported.jar") }
    inputs.file(input)
    outputs.file(output)
    classpath = proguard
    mainClass.set("proguard.ProGuard")
    val jmod = File(System.getProperty("java.home"), "jmods/java.base.jmod")
    doFirst {
        val libs = buildList {
            add("-libraryjars"); add(androidJar.absolutePath)
            if (jmod.exists()) { add("-libraryjars"); add("${jmod.absolutePath}(!**.jar;!module-info.class)") }
        }
        args(listOf("-injars", input.get().asFile.absolutePath, "-outjars", output.get().asFile.absolutePath) + libs + listOf(
            // "Manter tudo" em vez de -dontshrink: força o ProGuard a resolver todas as referências,
            // senão o backport move métodos estáticos de interface sem corrigir quem os chama.
            "-keep", "class ** { *; }", "-dontoptimize", "-dontobfuscate", "-dontpreverify",
            "-target", "1.7", "-keepattributes", "*", "-dontwarn", "**", "-dontnote", "**", "-ignorewarnings", "-forceprocessing",
        ))
    }
}

// 3) DEX.
val dex by tasks.registering(JavaExec::class) {
    group = "apk"
    val input = backport.map { it.outputs.files.singleFile }
    val output = apkDir.map { it.dir("dex") }
    inputs.files(backport)
    outputs.dir(output)
    classpath = files(dxJar)
    mainClass.set("com.android.dx.command.Main")
    maxHeapSize = "2g"
    doFirst {
        output.get().asFile.deleteRecursively(); output.get().asFile.mkdirs()
        args("--dex", "--min-sdk-version=$minSdk", "--multi-dex", "--output=${output.get().asFile.absolutePath}", input.get().absolutePath)
    }
}

// 4) Recursos + manifesto + assets (fitness.db gerado a partir dos seeds, schema do user.db).
val assetsDir = layout.buildDirectory.dir("apk/assets")
val apkAssets by tasks.registering(Copy::class) {
    group = "apk"
    dependsOn(":knowledge:buildKnowledgeDb")
    from(project(":knowledge").layout.buildDirectory.file("fitness.db"))
    from(project(":knowledge").file("src/main/resources/schema/user.sql"))
    into(assetsDir)
}

val resApk by tasks.registering(Exec::class) {
    group = "apk"
    val compiled = apkDir.map { it.file("res.zip") }
    val out = apkDir.map { it.file("res.apk") }
    inputs.dir("src/main/res"); inputs.file("src/main/AndroidManifest.xml"); inputs.files(apkAssets)
    outputs.file(out)
    doFirst {
        execOps.exec {
            commandLine("$buildTools/aapt2", "compile", "--dir", file("src/main/res").absolutePath, "-o", compiled.get().asFile.absolutePath)
        }
    }
    commandLine(
        "$buildTools/aapt2", "link", "-o", out.get().asFile.absolutePath,
        "-I", androidJar.absolutePath, "--manifest", file("src/main/AndroidManifest.xml").absolutePath,
        "-A", assetsDir.get().asFile.absolutePath,
        "--min-sdk-version", "$minSdk", "--target-sdk-version", "$targetSdk",
        "--version-code", "$appVersionCode", "--version-name", appVersionName,
        "--auto-add-overlay", compiled.get().asFile.absolutePath,
    )
}

// 5) Junta DEX + recursos, alinha e assina.
val apk by tasks.registering {
    group = "apk"
    description = "Gera build/outputs/FitKingIA-$appVersionName.apk assinado (chave de depuração em app/signing)."
    dependsOn(dex, resApk)
    val out = layout.buildDirectory.file("outputs/FitKingIA-$appVersionName.apk")
    outputs.file(out)
    inputs.files(dex, resApk)
    doLast {
        val work = apkDir.get().asFile
        val unaligned = File(work, "unaligned.apk")
        resApk.get().outputs.files.singleFile.copyTo(unaligned, overwrite = true)
        val dexFiles = File(work, "dex").listFiles { f -> f.name.endsWith(".dex") }!!.sortedBy { it.name }
        execOps.exec { workingDir = File(work, "dex"); commandLine(listOf("zip", "-q", "-X", unaligned.absolutePath) + dexFiles.map { it.name }) }
        val aligned = File(work, "aligned.apk")
        execOps.exec { commandLine(zipalignBin, "-p", "-f", "4", unaligned.absolutePath, aligned.absolutePath) }
        val target = out.get().asFile.also { it.parentFile.mkdirs() }
        execOps.exec {
            commandLine(apksignerBin, "sign", "--ks", file("signing/debug.p12").absolutePath, "--ks-type", "PKCS12",
                "--ks-pass", "pass:android", "--ks-key-alias", "fitkingia", "--key-pass", "pass:android",
                "--min-sdk-version", "$minSdk", "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
                "--out", target.absolutePath, aligned.absolutePath)
        }
        File(target.parentFile, target.name + ".idsig").delete()
        println("APK: ${target.absolutePath} (${target.length() / 1024} KB)")
    }
}

// Confere se o código do app (e do core) só chama classes/métodos que existem no Android 8.0.
// A biblioteca de referência é android.jar (API 23) para android.* e a API Java 8 (ct.sym do
// JDK) para java.* — o libcore do Android 8 segue o OpenJDK 8. Assim, um método de Java 9+
// (ex.: List.of, Optional.isEmpty) usado por engano quebra o build em vez de travar o celular.
val java8Api by tasks.registering {
    group = "apk"
    val out = apkDir.map { it.file("java8-api.jar") }
    outputs.file(out)
    doLast {
        val ct = File(System.getProperty("java.home"), "lib/ct.sym")
        ZipFile(ct).use { zip ->
            ZipOutputStream(out.get().asFile.outputStream()).use { jar ->
                val seen = HashSet<String>()
                for (e in zip.entries().asSequence()) {
                    val parts = e.name.split("/", limit = 3)
                    if (parts.size < 3 || '8' !in parts[0] || !e.name.endsWith(".sig")) continue
                    val path = parts[2].removeSuffix(".sig") + ".class"
                    if (!path.startsWith("java/") || !seen.add(path)) continue
                    jar.putNextEntry(ZipEntry(path))
                    zip.getInputStream(e).use { it.copyTo(jar) }
                    jar.closeEntry()
                }
            }
        }
    }
}

val checkAndroidApi by tasks.registering(JavaExec::class) {
    group = "apk"
    description = "Falha se o código do FitKingIA referenciar APIs ausentes no Android 8.0 (API 26)."
    val input = backport.map { it.outputs.files.singleFile }
    val report = apkDir.map { it.file("api-check.txt") }
    inputs.files(backport, java8Api)
    outputs.file(report)
    classpath = proguard
    mainClass.set("proguard.ProGuard")
    val out = ByteArrayOutputStream()
    standardOutput = out
    errorOutput = out
    isIgnoreExitValue = true
    doFirst {
        args("-injars", input.get().absolutePath, "-outjars", apkDir.get().file("api-check.jar").asFile.absolutePath,
            "-libraryjars", "${androidJar.absolutePath}(!java/**)",
            "-libraryjars", apkDir.get().file("java8-api.jar").asFile.absolutePath,
            "-keep", "class ** { *; }", "-dontoptimize", "-dontobfuscate", "-dontpreverify", "-dontnote", "**",
            "-ignorewarnings", "-forceprocessing")
    }
    doLast {
        val text = out.toString()
        report.get().asFile.writeText(text)
        val warnings = text.lines().filter { it.startsWith("Warning: ") && "can't find" in it }
        val ours = warnings.filter { it.startsWith("Warning: com.fitkingia") }
        if (ours.isNotEmpty()) throw GradleException("APIs ausentes no Android 8.0:\n" + ours.distinct().joinToString("\n"))
        println("API 26: nenhuma referência ausente no código do FitKingIA (${warnings.size} avisos em bibliotecas de terceiros; ver ${report.get().asFile})")
    }
}

tasks.named("apk") { dependsOn(checkAndroidApi) }

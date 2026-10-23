import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.xuncorp.spw.workshop.gradle.PluginPermission
import org.gradle.api.tasks.bundling.Zip

plugins {
    id("java-library")
    kotlin("jvm") version "2.4.20"
    alias(libs.plugins.compose)
    id("com.xuncorp.spw.workshop") version "0.1.0-dev22"
}

group = "dev.saltartist"
version = "0.1.9"

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget = JvmTarget.JVM_25
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

dependencies {
    // 宿主在运行时提供 Kotlin 与 Compose，插件内不得重复打包。
    compileOnly(kotlin("stdlib"))
    compileOnly("net.java.dev.jna:jna:5.18.1")
    compileOnly(libs.workshop.api)
    compileOnly(libs.compose.ui)
    compileOnly(libs.compose.foundation)
    compileOnly(libs.compose.runtime)
    compileOnly("org.jetbrains.compose.material:material:1.12.1")
    testImplementation(kotlin("test"))
    testImplementation("com.github.Moriafly.spw-workshop-api:spw-workshop-api:${libs.versions.workshop.get()}") {
        // ConfigHelper contract tests need the API, without the host's UI/native runtime.
        isTransitive = false
    }
    testImplementation(libs.compose.ui) {
        // Palette tests use color math only; no native renderer is needed.
        exclude(group = "org.jetbrains.skiko")
    }
    testImplementation(libs.compose.foundation) {
        // Directory interval tests construct the DSL only; no native UI renderer.
        exclude(group = "org.jetbrains.skiko")
    }
}

spmod {
    PluginClass = "dev.saltartist.SaltArtistPlugin"
    PluginId = "dev.saltartist"
    PluginVersion = project.version.toString()
    PluginProvider = "asukua"
    PluginName = "Salt Artist"
    // Manifest 只安全承载 ASCII；中文描述会在宿主插件列表里变成乱码。
    // 描述控制在 50 字符内，避免 MANIFEST 72 字节换行把单词截断成 "wir\n ed"。
    PluginDescription = "Apple Music style artist page for Salt Player"
    PluginOpenSourceUrl = "https://github.com/asukua/salt-artist"
    PluginHasConfig = true
    PluginPermissions = listOf(PluginPermission.CLASS_TRANSFORM, PluginPermission.LIBRARY_READ)
}

tasks.named<Zip>("plugin") {
    from("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md")
    // dev22 默认只把描述符放进 classes/；宿主从插件根目录读取权限声明。
    from(zipTree(tasks.jar.flatMap { it.archiveFile })) {
        include("META-INF/MANIFEST.MF")
    }
}

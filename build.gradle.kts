import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.ethan.wdt"
version = providers.gradleProperty("pluginVersion").get()

val localIdePath = providers.gradleProperty("intellijPlatform.localIdePath")

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        if (localIdePath.isPresent) {
            local(localIdePath)
        } else {
            intellijIdea("2024.3")
        }
        bundledModule("com.intellij.modules.vcs")
        bundledModule("intellij.platform.vcs.dvcs.impl")
        bundledPlugin("Git4Idea")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation(kotlin("test-junit"))
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
        // IDEA 2024.3 内置 Kotlin 2.0.21，限制 API 版本以避免生成新版标准库引用
        apiVersion.set(KotlinVersion.KOTLIN_2_0)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "243"
        }
    }

    signing {
        certificateChain = providers.environmentVariable("CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("PRIVATE_KEY")
        password = providers.environmentVariable("PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

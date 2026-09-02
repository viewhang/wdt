import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = "com.ethan.wdt"
version = "0.1.0"

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
            intellijIdea("2026.2.1")
        }
        bundledModule("com.intellij.modules.vcs")
        bundledModule("intellij.platform.vcs.dvcs")
        bundledPlugin("Git4Idea")
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation(kotlin("test-junit"))
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
        }
    }
}

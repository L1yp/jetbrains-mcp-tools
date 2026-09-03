import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "2.1.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("org.tomlj:tomlj:1.1.1")

    intellijPlatform {
        intellijIdeaUltimate(providers.gradleProperty("platformVersion"))
        pluginVerifier()
        testFramework(TestFrameworkType.Platform)
    }

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.add("-Xmulti-dollar-interpolation")
    }
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "232"
            untilBuild = "251.*"
        }
    }
    pluginVerification {
        ides {
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2023.2.7")
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2024.2.5")
            create(IntelliJPlatformType.IntellijIdeaUltimate, "2025.1.7.1")
        }
    }
}

tasks {
    test {
        useJUnitPlatform()
    }
}

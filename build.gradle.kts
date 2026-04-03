plugins {
    kotlin("jvm") version "2.3.20"
    `java-library`
    `maven-publish`
    signing
    id("net.thebugmc.gradle.sonatype-central-portal-publisher") version "1.2.4"
}

group = "io.featureflip"
version = "0.1.0"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:5.3.2")
    implementation("com.squareup.okhttp3:okhttp-sse:5.3.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.21.2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.21.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")

    // Note: androidx.lifecycle is accessed via reflection at runtime when available.
    // No compile-time dependency needed — the SDK works on both Android and pure JVM.

    testImplementation("org.junit.jupiter:junit-jupiter:6.0.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.squareup.okhttp3:mockwebserver3-junit5:5.3.2")
    testImplementation("org.assertj:assertj-core:3.27.7")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    compilerOptions {
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

centralPortal {
    username = System.getenv("MAVEN_USERNAME") ?: ""
    password = System.getenv("MAVEN_PASSWORD") ?: ""

    pom {
        name.set("Featureflip Android SDK")
        description.set("Android/Kotlin SDK for Featureflip - a feature flag SaaS platform")
        url.set("https://featureflip.io")

        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }

        developers {
            developer {
                name.set("Featureflip Team")
                organization.set("Canopy Labs LLC")
                organizationUrl.set("https://featureflip.io")
            }
        }

        scm {
            url.set("https://github.com/canopy-labs/featureflip-android")
        }
    }
}

signing {
    val signingKey = findProperty("signing.key") as String? ?: System.getenv("GPG_PRIVATE_KEY")
    val signingPassword = findProperty("signing.password") as String? ?: System.getenv("GPG_PASSPHRASE")
    if (signingKey != null && signingPassword != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
    }
    sign(publishing.publications)
}

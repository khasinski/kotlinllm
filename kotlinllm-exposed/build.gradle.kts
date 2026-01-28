plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `java-library`
}

val exposedVersion = "0.46.0"

dependencies {
    api(project(":kotlinllm-core"))

    // Exposed
    api("org.jetbrains.exposed:exposed-core:$exposedVersion")
    api("org.jetbrains.exposed:exposed-dao:$exposedVersion")
    api("org.jetbrains.exposed:exposed-jdbc:$exposedVersion")
    api("org.jetbrains.exposed:exposed-kotlin-datetime:$exposedVersion")
    api("org.jetbrains.exposed:exposed-json:$exposedVersion")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("com.h2database:h2:2.2.224")
}

kotlin {
    jvmToolchain(21)
}

java {
    withSourcesJar()
    withJavadocJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "kotlinllm-exposed"
            from(components["java"])

            pom {
                name.set("KotlinLLM Exposed")
                description.set("JetBrains Exposed persistence adapter for KotlinLLM")
            }
        }
    }
}

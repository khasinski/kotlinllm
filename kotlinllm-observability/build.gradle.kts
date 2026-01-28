plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    api(project(":kotlinllm-core"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Optional: SLF4J for logging
    compileOnly("org.slf4j:slf4j-api:2.0.9")

    // Optional: Micrometer for metrics
    compileOnly("io.micrometer:micrometer-core:1.12.2")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("org.slf4j:slf4j-simple:2.0.9")
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
            artifactId = "kotlinllm-observability"
            from(components["java"])

            pom {
                name.set("KotlinLLM Observability")
                description.set("Metrics and logging for KotlinLLM")
            }
        }
    }
}

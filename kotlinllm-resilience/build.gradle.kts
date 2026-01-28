plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    api(project(":kotlinllm-core"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
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
            artifactId = "kotlinllm-resilience"
            from(components["java"])

            pom {
                name.set("KotlinLLM Resilience")
                description.set("Rate limiting and circuit breaker for KotlinLLM")
            }
        }
    }
}

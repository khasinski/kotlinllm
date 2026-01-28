plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `java-library`
}

dependencies {
    // Minimal dependencies - just like RubyLLM's philosophy
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("com.squareup.okhttp3:okhttp-sse:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    api(kotlin("reflect"))

    // Optional: SLF4J for logging (user can provide their own implementation)
    compileOnly("org.slf4j:slf4j-api:2.0.9")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
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
            artifactId = "kotlinllm-core"
            from(components["java"])

            pom {
                name.set("KotlinLLM Core")
                description.set("A beautiful Kotlin API for LLMs - inspired by RubyLLM")
            }
        }
    }
}

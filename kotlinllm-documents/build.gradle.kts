plugins {
    kotlin("jvm")
    `java-library`
}

dependencies {
    api(project(":kotlinllm-core"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Document processing
    implementation("org.apache.pdfbox:pdfbox:3.0.1")
    implementation("org.jsoup:jsoup:1.17.2")

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
            artifactId = "kotlinllm-documents"
            from(components["java"])

            pom {
                name.set("KotlinLLM Documents")
                description.set("Document loaders and chunking for KotlinLLM")
            }
        }
    }
}

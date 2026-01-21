plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    kotlin("plugin.jpa") version "1.9.22"
    `java-library`
    `maven-publish`
}

dependencies {
    api(project(":kotlinllm-core"))

    // JPA API (implementation provided by user's JPA provider)
    compileOnly("jakarta.persistence:jakarta.persistence-api:3.1.0")

    // Optional Hibernate (for users who want it)
    compileOnly("org.hibernate.orm:hibernate-core:6.4.1.Final")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    testImplementation("jakarta.persistence:jakarta.persistence-api:3.1.0")
    testImplementation("org.hibernate.orm:hibernate-core:6.4.1.Final")
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
            artifactId = "kotlinllm-jpa"
            from(components["java"])

            pom {
                name.set("KotlinLLM JPA")
                description.set("JPA persistence adapter for KotlinLLM")
                url.set("https://github.com/khasinski/kotlinllm")

                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
            }
        }
    }
}

plugins {
    kotlin("jvm") version "1.9.22" apply false
    kotlin("plugin.serialization") version "1.9.22" apply false
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
}

group = "com.kotlinllm"
version = "0.9.0"

// Nexus publishing for Maven Central (new Sonatype Central Portal)
nexusPublishing {
    repositories {
        sonatype {
            nexusUrl.set(uri("https://central.sonatype.com/api/v1/publisher"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/api/v1/publisher"))
            username.set(providers.environmentVariable("MAVEN_USERNAME"))
            password.set(providers.environmentVariable("MAVEN_PASSWORD"))
        }
    }
}

allprojects {
    group = "com.kotlinllm"
    version = rootProject.version

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "signing")
    apply(plugin = "maven-publish")

    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        kotlinOptions {
            jvmTarget = "21"
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }

    // Common publishing configuration for all modules
    afterEvaluate {
        configure<PublishingExtension> {
            publications.withType<MavenPublication> {
                pom {
                    url.set("https://github.com/khasinski/kotlinllm")

                    licenses {
                        license {
                            name.set("MIT License")
                            url.set("https://opensource.org/licenses/MIT")
                        }
                    }

                    developers {
                        developer {
                            id.set("khasinski")
                            name.set("Chris Hasiński")
                            email.set("krzysztof.hasinski@gmail.com")
                        }
                    }

                    scm {
                        connection.set("scm:git:git://github.com/khasinski/kotlinllm.git")
                        developerConnection.set("scm:git:ssh://github.com:khasinski/kotlinllm.git")
                        url.set("https://github.com/khasinski/kotlinllm")
                    }
                }
            }
        }

        configure<SigningExtension> {
            val signingKey = providers.environmentVariable("SIGNING_KEY").orNull
            val signingPassword = providers.environmentVariable("SIGNING_PASSWORD").orNull

            if (signingKey != null && signingPassword != null) {
                useInMemoryPgpKeys(signingKey, signingPassword)
                sign(extensions.getByType<PublishingExtension>().publications)
            }
        }
    }
}

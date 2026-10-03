plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

kotlin {
    jvmToolchain(17)
}

java {
    withSourcesJar()
}

dependencies {
    api(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.mockwebserver)
}

group = "com.hossamrakha"
version = "0.1.0"

publishing {
    publications {
        register<MavenPublication>("authRefresh") {
            artifactId = "auth-refresh"
            from(components["java"])
            pom {
                name.set("auth-refresh")
                description.set(
                    "Synchronized token refresh for OkHttp: one lock-guarded refresh on 401, " +
                        "each failed request replayed once, session teardown on failure."
                )
                url.set("https://github.com/hossam1244/auth-refresh")
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
                developers {
                    developer {
                        id.set("hossam1244")
                        name.set("Hossam Rakha")
                    }
                }
                scm {
                    connection.set("scm:git:git://github.com/hossam1244/auth-refresh.git")
                    developerConnection.set("scm:git:ssh://github.com/hossam1244/auth-refresh.git")
                    url.set("https://github.com/hossam1244/auth-refresh")
                }
            }
        }
    }
}

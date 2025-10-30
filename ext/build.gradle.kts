plugins {
    id("java-library")
    id("maven-publish")
    alias(libs.plugins.gradle.shadow)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlinx.serialization)
}

dependencies {
    compileOnly(libs.echo.common)
    compileOnly(libs.kotlin.stdlib)

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.echo.common)

    // HTTP Client (Keep)
    implementation("com.squareup.okhttp3:okhttp:5.2.1") 

    // FIX 1: Add the explicit Coroutines dependency to prevent IllegalAccessError
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // FIX 2: Use a recent, stable serialization version
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3") 
}


java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

// Extension properties goto `gradle.properties` to set values

val extType: String by project
val extId: String by project
val extClass: String by project

val extIconUrl: String? by project
val extName: String by project
val extDescription: String? by project

val extAuthor: String by project
val extAuthorUrl: String? by project

val extRepoUrl: String? by project
val extUpdateUrl: String? by project

val gitHash = execute("git", "rev-parse", "HEAD").take(7)
val gitCount = execute("git", "rev-list", "--count", "HEAD").toInt()
val verCode = gitCount
val verName = "v$gitHash"

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            groupId = "dev.brahmkshatriya.echo.extension"
            artifactId = extId
            version = verName

            from(components["java"])
        }
    }
}

tasks {
    // --- ADDED THIS BLOCK ---
    // Disables the standard, non-shaded JAR.
    // This helps ensure the EAPK build uses the output from shadowJar.
    named("jar") {
        enabled = false
    }
    // ------------------------

    shadowJar {
        archiveBaseName.set(extId)
        archiveVersion.set(verName)

        // --- FIX FOR IllegalAccessError ---
        // This isolates your extension's libraries from the main app's libraries.
        relocate("kotlinx.coroutines", "shadow.kotlinx.coroutines")
        relocate("kotlinx.serialization", "shadow.kotlinx.serialization")
        relocate("kotlin", "shadow.kotlin")
        // ------------------------------------

        manifest {
            attributes(
                mapOf(
                    "Extension-Id" to extId,
                    "Extension-Type" to extType,
                    "Extension-Class" to extClass,

                    "Extension-Version-Code" to verCode,
                    "Extension-Version-Name" to verName,

                    "Extension-Icon-Url" to extIconUrl,
                    "Extension-Name" to extName,
                    "Extension-Description" to extDescription,

                    "Extension-Author" to extAuthor,
                    "Extension-Author-Url" to extAuthorUrl,

                    "Extension-Repo-Url" to extRepoUrl,
                    "Extension-Update-Url" to extUpdateUrl
                )
            )
        }
    }
}

fun execute(vararg command: String): String = providers.exec {
    commandLine(*command)
}.standardOutput.asText.get().trim()

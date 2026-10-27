plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

val ngrokVersion = "1.1.1"

dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    implementation("com.github.mwiede:jsch:2.28.8")
    // The Android app ships its own native loader for ngrok, so only the API is
    // needed at compile time; the JVM runner adds the desktop natives below.
    compileOnly("com.ngrok:ngrok-java:$ngrokVersion")
    compileOnly("com.ngrok:ngrok-java-native:$ngrokVersion")

    runtimeOnly("com.ngrok:ngrok-java:$ngrokVersion")
    runtimeOnly("com.ngrok:ngrok-java-native:$ngrokVersion:linux-x86_64")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.17")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("com.colabmcp.core.cli.MainKt")
    applicationName = "colab-mcp-bridge"
}

tasks.test {
    useJUnit()
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
}

// Self-contained runnable jar for x86_64 Linux (used to test the bridge without an emulator).
tasks.register<Jar>("fatJar") {
    archiveBaseName.set("colab-mcp-bridge-jvm")
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = "com.colabmcp.core.cli.MainKt" }
    from(sourceSets.main.get().output)
    dependsOn(configurations.runtimeClasspath)
    from({ configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) } }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/9/module-info.class")
    }
}

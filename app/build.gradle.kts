plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val ngrokVersion = "1.1.1"

// ngrok-java ships its Android natives as plain jar resources and extracts them to a temp
// file at runtime. We instead package them as regular per-ABI jniLibs and load them with
// System.loadLibrary (see app/src/main/java/com/ngrok/Runtime.java).
val ngrokNatives: Configuration by configurations.creating { isTransitive = false }
val ngrokClasses: Configuration by configurations.creating { isTransitive = false }

dependencies {
    ngrokNatives("com.ngrok:ngrok-java-native:$ngrokVersion:linux-android-aarch_64")
    ngrokNatives("com.ngrok:ngrok-java-native:$ngrokVersion:linux-android-armv7")
    ngrokClasses("com.ngrok:ngrok-java-native:$ngrokVersion")
}

val ngrokJniDir = layout.buildDirectory.dir("generated/ngrok/jniLibs")

/**
 * ngrok-java's JNI_OnLoad returns JNI_VERSION_1_8 (0x10008). Desktop JVMs accept that, but
 * Android only accepts 1.2/1.4/1.6, so System.loadLibrary fails, Runtime.init never runs and
 * every connect panics with "runtime not initialized". The library uses no 1.8-only JNI
 * functions, so its JNI_OnLoad is patched to return JNI_VERSION_1_6 (0x10006).
 */
fun patchJniOnLoadVersion(lib: File, abi: String) {
    fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    val (pattern, replacement) = when (abi) {
        // movz w0, #0x8 ; movk w0, #0x1, lsl #16  ->  movz w0, #0x6 ; ...
        "arm64-v8a" -> hex("000180522000a072") to hex("c00080522000a072")
        // movs r0, #8 ; movt r0, #1 ; pop {r7, pc}  ->  movs r0, #6 ; ...
        "armeabi-v7a" -> hex("0820c0f2010080bd") to hex("0620c0f2010080bd")
        else -> error("No JNI_OnLoad patch for $abi")
    }
    val bytes = lib.readBytes()
    val hits = (0..bytes.size - pattern.size).filter { i -> pattern.indices.all { bytes[i + it] == pattern[it] } }
    check(hits.size == 1) { "Expected the JNI_OnLoad version constant exactly once in $abi/libngrok_java.so, found ${hits.size}" }
    replacement.copyInto(bytes, hits[0])
    lib.writeBytes(bytes)
}

val extractNgrokNatives by tasks.registering {
    inputs.files(ngrokNatives)
    outputs.dir(ngrokJniDir)
    doLast {
        val out = ngrokJniDir.get().asFile
        out.deleteRecursively()
        ngrokNatives.files.forEach { jar ->
            val abi = when {
                jar.name.contains("aarch_64") -> "arm64-v8a"
                jar.name.contains("armv7") -> "armeabi-v7a"
                else -> return@forEach
            }
            copy {
                from(zipTree(jar)) { include("libngrok_java.so") }
                into(File(out, abi))
            }
            patchJniOnLoadVersion(File(out, "$abi/libngrok_java.so"), abi)
        }
    }
}

val ngrokClassesJar by tasks.registering(Jar::class) {
    archiveFileName.set("ngrok-native-classes.jar")
    destinationDirectory.set(layout.buildDirectory.dir("generated/ngrok"))
    from({ ngrokClasses.files.map { zipTree(it) } }) {
        include("com/ngrok/**/*.class")
        // NativeSession reads agent.version from here and reports it to ngrok.
        include("native.properties")
        exclude("com/ngrok/Runtime.class", "com/ngrok/Runtime$*.class")
    }
}

android {
    namespace = "com.colabmcp.bridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.colabmcp.bridge"
        minSdk = 26
        targetSdk = 36
        versionCode = 6
        versionName = "1.0.5"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    // Release APKs are signed with a local, git-ignored keystore (keep it to install updates
    // over an existing install). Without it, the Android debug key is used.
    val keystore = file(System.getenv("COLAB_MCP_KEYSTORE") ?: "signing/colab-mcp-bridge.jks")
    val keystorePassword = System.getenv("COLAB_MCP_KEYSTORE_PASSWORD") ?: "colab-mcp-bridge"
    signingConfigs {
        if (keystore.exists()) {
            create("sideload") {
                storeFile = keystore
                storePassword = keystorePassword
                keyAlias = "colab-mcp-bridge"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("sideload") ?: signingConfigs.getByName("debug")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64")
            isUniversalApk = true
        }
    }

    sourceSets["main"].jniLibs.srcDir(ngrokJniDir)

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.systemProperty("robolectric.logging.enabled", "true")
            it.systemProperty("robolectric.dependency.repo.url", "https://maven-central.storage-download.googleapis.com/maven2/")
            // Live Colab credentials for the integration test (optional).
            System.getenv("COLAB_TEST_ADC")?.let { adc -> it.environment("COLAB_TEST_ADC", adc) }
            it.testLogging { events("passed", "failed", "skipped"); showStandardStreams = true }
        }
    }

    packaging {
        jniLibs { useLegacyPackaging = true }
        resources {
            excludes += setOf(
                "META-INF/*.kotlin_module", "META-INF/versions/9/module-info.class", "module-info.class",
                "META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES", "libngrok_java.so",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.named("preBuild") { dependsOn(extractNgrokNatives, ngrokClassesJar) }

dependencies {
    implementation(project(":core")) {
        exclude(group = "com.ngrok")
        exclude(group = "org.slf4j", module = "slf4j-nop")
    }
    implementation("com.ngrok:ngrok-java:$ngrokVersion")
    implementation(files(ngrokClassesJar))
    implementation("org.slf4j:slf4j-api:2.0.17")
    implementation("org.slf4j:slf4j-nop:2.0.17")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation(platform("androidx.compose:compose-bom:2025.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core-ktx:1.7.0")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    implementation("androidx.compose.ui:ui-tooling-preview")
}

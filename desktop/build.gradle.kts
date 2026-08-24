plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

val desktopPlatform = sherpaPlatform()
val nativeBuildDirectory = layout.buildDirectory.dir("native/cmake")
val nativeOutputDirectory = layout.buildDirectory.dir("generated/native/$desktopPlatform")
val nativeLibraryName = when {
    desktopPlatform.startsWith("osx-") -> "libmichelina_desktop_audio.dylib"
    desktopPlatform.startsWith("win-") -> "michelina_desktop_audio.dll"
    else -> "libmichelina_desktop_audio.so"
}
val deepFilterLibraryName = when {
    desktopPlatform.startsWith("osx-") -> "libdf.dylib"
    desktopPlatform.startsWith("win-") -> "df.dll"
    else -> "libdf.so"
}
val defaultDeepFilterLibrary = layout.buildDirectory.file(
    "deepfilter/native/$desktopPlatform/$deepFilterLibraryName",
)
val deepFilterLibraryFile = (
    providers.environmentVariable("MELINA_DEEPFILTER_LIBRARY").orNull
        ?: providers.environmentVariable("MICHELINA_DEEPFILTER_LIBRARY").orNull
    )
    ?.let(::file)
    ?: defaultDeepFilterLibrary.get().asFile
val packageIcon = file(
    when {
        desktopPlatform.startsWith("osx-") -> "src/main/resources/branding/melina.icns"
        desktopPlatform.startsWith("win-") -> "src/main/resources/branding/melina.ico"
        else -> "src/main/resources/branding/melina.png"
    },
)

val configureDesktopNative by tasks.registering(Exec::class) {
    inputs.file("src/main/cpp/CMakeLists.txt")
    inputs.file("src/main/cpp/rnnoise_desktop_jni.cpp")
    inputs.file("src/main/cpp/ulunas_desktop_jni.cpp")
    inputs.file("src/main/cpp/deepfilter_desktop_jni.cpp")
    inputs.file("src/main/cpp/onnxruntime_c_api.h")
    inputs.file("src/main/cpp/onnxruntime_ep_c_api.h")
    inputs.dir("../app/src/main/cpp/third_party/rnnoise")
    outputs.file(nativeBuildDirectory.map { it.file("CMakeCache.txt") })
    doFirst {
        nativeOutputDirectory.get().asFile.mkdirs()
        commandLine(
            "cmake",
            "-S", file("src/main/cpp").absolutePath,
            "-B", nativeBuildDirectory.get().asFile.absolutePath,
            "-DCMAKE_BUILD_TYPE=Release",
            "-DMICHELINA_NATIVE_OUTPUT_DIR=${nativeOutputDirectory.get().asFile.absolutePath}",
        )
        environment("JAVA_HOME", System.getProperty("java.home"))
    }
}

val buildDesktopNative by tasks.registering(Exec::class) {
    dependsOn(configureDesktopNative)
    inputs.files(configureDesktopNative.map { it.inputs.files })
    outputs.file(nativeOutputDirectory.map { it.file(nativeLibraryName) })
    commandLine(
        "cmake",
        "--build", nativeBuildDirectory.get().asFile.absolutePath,
        "--config", "Release",
        "--parallel",
    )
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(project(":audio-core"))
    implementation("com.k2fsa:sherpa-onnx-jvm:1.13.4")
    runtimeOnly("com.k2fsa:sherpa-onnx-native-lib-${sherpaPlatform()}:1.13.4")
    testImplementation("junit:junit:4.13.2")
}

application {
    mainClass.set("it.michelina.focus.desktop.MichelinaDesktopKt")
    applicationName = "melina"
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}

tasks.test {
    useJUnit()
}

tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processResources") {
    dependsOn(buildDesktopNative)
    from(nativeOutputDirectory) {
        into("native/$desktopPlatform")
    }
    if (deepFilterLibraryFile.isFile) {
        inputs.file(deepFilterLibraryFile)
        from(deepFilterLibraryFile) {
            into("native/$desktopPlatform")
            rename { deepFilterLibraryName }
        }
    }
}

tasks.register("verifyDeepFilterPackaging") {
    group = "verification"
    description = "Verifies that the platform libDF runtime is present in the desktop JAR."
    dependsOn("jar")
    doLast {
        check(deepFilterLibraryFile.isFile) {
            "DeepFilterNet runtime not found: ${deepFilterLibraryFile.absolutePath}"
        }
        val expectedResource = "native/$desktopPlatform/$deepFilterLibraryName"
        val jarFile = tasks.named<org.gradle.jvm.tasks.Jar>("jar").get().archiveFile.get().asFile
        java.util.zip.ZipFile(jarFile).use { archive ->
            check(archive.getEntry(expectedResource) != null) {
                "DeepFilterNet runtime not packaged in ${jarFile.name}: $expectedResource"
            }
        }
    }
}

tasks.register<Exec>("packageAppImage") {
    group = "distribution"
    description = "Builds a native desktop app image with a bundled Java runtime."
    dependsOn("installDist")

    val installDirectory = layout.buildDirectory.dir("install/melina")
    val packageDirectory = layout.buildDirectory.dir("package")
    inputs.dir(installDirectory)
    outputs.dir(packageDirectory)
    doFirst {
        delete(packageDirectory)
        commandLine(
            "jpackage",
            "--type", "app-image",
            "--input", installDirectory.get().dir("lib").asFile.absolutePath,
            "--dest", packageDirectory.get().asFile.absolutePath,
            "--name", "Melina",
            // macOS CFBundleShortVersionString must start with a positive integer.
            "--app-version", "1.0.14",
            "--vendor", "Melina",
            "--description", "Real-time local assistive listening processor",
            "--icon", packageIcon.absolutePath,
            "--main-jar", "desktop.jar",
            "--main-class", "it.michelina.focus.desktop.MichelinaDesktopKt",
            "--add-modules", "java.base,java.desktop,java.logging,jdk.unsupported",
            "--java-options", "--enable-native-access=ALL-UNNAMED",
            "--java-options", "-Xmx2g",
        )
    }
}

sourceSets {
    main {
        resources.srcDir("../app/src/main/assets")
    }
}

fun sherpaPlatform(): String {
    val os = System.getProperty("os.name").lowercase()
    val architecture = System.getProperty("os.arch").lowercase()
    val arm64 = architecture == "aarch64" || architecture == "arm64"
    return when {
        os.contains("mac") -> if (arm64) "osx-aarch64" else "osx-x64"
        os.contains("win") -> if (arm64) "win-arm64" else "win-x64"
        os.contains("linux") -> if (arm64) "linux-aarch64" else "linux-x64"
        else -> error("Desktop platform not supported: $os $architecture")
    }
}

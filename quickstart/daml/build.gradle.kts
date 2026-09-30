// Copyright (c) 2025, Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: 0BSD

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath(Deps.transcode.plugin)
        classpath("org.apache.commons:commons-compress:1.27.1")
    }
}

import com.digitalasset.transcode.codegen.java.gradle.JavaCodegenTask

plugins {
    id("base")
    id("de.undercouch.download") version "5.6.0"
}

// Enable Transcode codegen by default for the renamed helvet-amm package
val enableClearportxCodegen = providers.environmentVariable("CLEARPORTX_ENABLE_CODEGEN")
    .map { it.equals("true", ignoreCase = true) }
    .orElse(true)

fun cleanEnvWith(overrides: Map<String, String>): Map<String, String> {
    val env = System.getenv().toMutableMap()
    overrides.keys.forEach { env.remove(it) }
    env.putAll(overrides)
    return env
}

tasks.register<Exec>("compileDaml") {
    dependsOn("verifyDamlSdkVersion")
    val sdkVars = computeSdkVariables()
    val requiredVersion = sdkVars["damlSdkVersion"] as String
    val homeDir = File(System.getProperty("user.home"))
    val damlBinPrimary = File(homeDir, ".daml/sdk/$requiredVersion/daml")
    val damlBinSecondary = File(homeDir, ".daml/sdk/$requiredVersion/daml/daml")
    val damlBin = when {
        damlBinPrimary.isFile && damlBinPrimary.canExecute() -> damlBinPrimary.absolutePath
        damlBinSecondary.isFile && damlBinSecondary.canExecute() -> damlBinSecondary.absolutePath
        else -> throw GradleException(
            "DAML binary for SDK $requiredVersion not found. Tried: " +
                "${damlBinPrimary.absolutePath}, ${damlBinSecondary.absolutePath}"
        )
    }
    environment = cleanEnvWith(mapOf("DAML_SDK_VERSION" to requiredVersion))
    commandLine(damlBin, "damlc", "build", "--all")
}

tasks.register<Exec>("testDaml") {
    dependsOn("verifyDamlSdkVersion")
    val sdkVars = computeSdkVariables()
    val requiredVersion = sdkVars["damlSdkVersion"] as String
    commandLine("daml", "test", "--project-root", "licensing-tests")
      .setEnvironment(mapOf("DAML_SDK_VERSION" to requiredVersion))
}

tasks.register<com.digitalasset.transcode.codegen.java.gradle.JavaCodegenTask>("codeGen") {
    dar.from("$projectDir/licensing/.daml/dist/quickstart-licensing-0.0.1.dar")
    destination = file("$rootDir/backend/build/generated-daml-bindings")
    dependsOn("compileDaml")
}

tasks.register<com.digitalasset.transcode.codegen.java.gradle.JavaCodegenTask>("codeGenClearportX") {
    // Use the renamed helvet-amm DAR produced by `daml build` in clearportx/
    // Include licensing DAR too so shared splice-api types are generated consistently
    // (prevents orphan class references when both DARs depend on the same splice APIs).
    val helvetDar = file("$rootDir/clearportx/.daml/dist/helvet-amm-1.0.0.dar")
    val helvetDistCopy = file("$rootDir/clearportx/dist/helvet-amm-1.0.0.dar")
    val chosen: File? = when {
        helvetDar.exists() -> helvetDar
        helvetDistCopy.exists() -> helvetDistCopy
        else -> null
    }
    val licensingDar = file("$projectDir/licensing/.daml/dist/quickstart-licensing-0.0.1.dar")
    onlyIf {
        val enabled = enableClearportxCodegen.get()
        if (!enabled) {
            println("⚠️  Skipping helvet-amm codegen. Set CLEARPORTX_ENABLE_CODEGEN=true to enable.")
        }
        enabled && chosen != null
    }
    if (chosen != null) {
        println("Using helvet-amm DAR for codegen: ${chosen.absolutePath}")
        dar.from(chosen.absolutePath)
    }
    if (licensingDar.exists()) {
        dar.from(licensingDar.absolutePath)
    }
    // Splice FeaturedApp v1 API DAR - required for FeaturedAppRight_CreateActivityMarker choice
    // bindings. Shipped with quickstart; provider grant is verified at runtime.
    val featuredAppDar = file("$projectDir/dars/splice-api-featured-app-v1-1.0.0.dar")
    if (featuredAppDar.exists()) {
        dar.from(featuredAppDar.absolutePath)
    }
    // Fail at execution time (not configuration) so a missing DAR doesn't break
    // unrelated tasks like `./gradlew tasks`. The onlyIf predicate above already
    // gates whether this codegen task runs.
    doFirst {
        if (!featuredAppDar.exists()) {
            throw GradleException("Missing FeaturedApp v1 DAR at ${featuredAppDar.absolutePath}")
        }
    }
    destination = file("$rootDir/backend/build/generated-daml-bindings")
    dependsOn("compileDaml")
}

tasks.register<com.digitalasset.transcode.codegen.java.gradle.JavaCodegenTask>("codeGenClearportXProd") {
    // Deprecated: was used for legacy production DAR. Superseded by helvet-amm codegen.
    onlyIf { false }
    destination = file("$rootDir/backend/build/generated-daml-bindings")
}

tasks.named("build") {
    if (enableClearportxCodegen.get()) {
        // codeGenClearportX now bundles licensing + helvet-amm DARs, so no need to run codeGen separately
        dependsOn("codeGenClearportX")
    } else {
        dependsOn("codeGen")
        doFirst {
            println("⚠️  helvet-amm codegen disabled — using licensing-only bindings.")
        }
    }
}
// Helper function to compute SDK variables
fun computeSdkVariables(): Map<String, Any> {
    val osName = System.getProperty("os.name").lowercase()
    val osArch = System.getProperty("os.arch").lowercase()
    val isWindows = osName.contains("win")
    val isMac = osName.contains("mac")
    val isUnix = osName.contains("nix") || osName.contains("nux") || osName.contains("aix")

    val sdkOs = when {
        isWindows -> "windows-x86_64"
        isMac -> "macos-x86_64"
        isUnix -> when {
            osArch.contains("arm") || osArch.contains("aarch64") -> "linux-aarch64"
            else -> "linux-x86_64"
        }
        else -> throw Exception("Unsupported OS: $osName")
    }

    val damlSdkRuntimeVersion = VersionFiles.dotenv["DAML_RUNTIME_VERSION"] as String
    val damlSdkVersion = VersionFiles.damlYamlSdk
    val sdkArchive = "daml-sdk-$damlSdkRuntimeVersion-$sdkOs-ee.tar.gz"
    val sdkUrl = "https://digitalasset.jfrog.io/artifactory/sdk-ee/$damlSdkRuntimeVersion/$sdkArchive"
    val sdkDir = file("$projectDir/.sdk")
    val sdkArchiveFile = file("${sdkDir}/${sdkArchive}")
    val extractedDir = file("${sdkDir}/extracted")

    return mapOf(
        "damlSdkRuntimeVersion" to damlSdkRuntimeVersion,
        "damlSdkVersion" to damlSdkVersion,
        "sdkOs" to sdkOs,
        "sdkArchive" to sdkArchive,
        "sdkUrl" to sdkUrl,
        "sdkDir" to sdkDir,
        "sdkArchiveFile" to sdkArchiveFile,
        "extractedDir" to extractedDir
    )
}

// Task to download the SDK archive
tasks.register<de.undercouch.gradle.tasks.download.Download>("fetchDamlSdk") {
    val sdkVars = computeSdkVariables()
    val sdkUrl = sdkVars["sdkUrl"] as String
    val sdkArchiveFile = sdkVars["sdkArchiveFile"] as File
    val sdkDir = sdkVars["sdkDir"] as File

    src(sdkUrl)
    dest(sdkArchiveFile)
    overwrite(false)
    onlyIfModified(true)

    doFirst {
        sdkDir.mkdirs()
        val (username, password) = Credentials.execFromNetRc("digitalasset.jfrog.io")
        username(username)
        password(password)
    }

    doLast {
        println("Downloaded $sdkUrl to ${sdkArchiveFile.absolutePath}")
    }
}

// Task to unpack the SDK archive
tasks.register<UnpackTarGzTask>("unpackDamlSdk") {
    dependsOn("fetchDamlSdk")
    val sdkVars = computeSdkVariables()
    archiveFile = sdkVars["sdkArchiveFile"] as File
    destinationDir = sdkVars["extractedDir"] as File
}

// Task to run the install script
tasks.register<Exec>("installDamlSdk") {
    dependsOn("unpackDamlSdk")
    val sdkVars = computeSdkVariables()
    val sdkDir = sdkVars["sdkDir"] as File
    val extractedDir = sdkVars["extractedDir"] as File
    val damlSdkRuntimeVersion = sdkVars["damlSdkRuntimeVersion"] as String
    val damlSdkVersion = sdkVars["damlSdkVersion"] as String
    val sdkOs = sdkVars["sdkOs"] as String

    doFirst {
        val topLevelDirs = extractedDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        if (topLevelDirs.isEmpty()) {
            throw Exception("No directories found in $extractedDir")
        }
        workingDir = topLevelDirs.first()
    }
    commandLine(
        if (sdkOs == "windows-x86_64") "./install.bat" else "./install.sh",
        "--install-with-custom-version",
        damlSdkVersion
    )
    doLast {
        println("Installed Daml SDK runtime $damlSdkRuntimeVersion as $damlSdkVersion")
        println("Cleaning up downloaded files")
        sdkDir.deleteRecursively()
    }
}

// Task to ensure the right Daml SDK version is installed
tasks.register("verifyDamlSdkVersion") {
    val sdkVars = computeSdkVariables()
    val requiredVersion = sdkVars["damlSdkVersion"] as String

    doLast {
        val output = java.io.ByteArrayOutputStream()
        exec {
            commandLine = listOf("daml", "version")
            standardOutput = output
            isIgnoreExitValue = true
        }

        val versionLine = output.toString()
            .lineSequence()
            .firstOrNull { it.contains(requiredVersion) }
            ?.trim()

        if (versionLine == null) {
            throw GradleException(
                """
                ❌ Could not find required DAML SDK version:
                   Required:  $requiredVersion

                💡 Please try running: make install-daml-sdk
                """.trimIndent()
            )
        } else {
            println("✅ DAML SDK version $requiredVersion is installed.")
        }

    }
}

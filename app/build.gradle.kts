import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}
val releaseSigningPath = providers.gradleProperty("releaseSigningProperties").orNull
val releaseSigningFile = rootProject.file(releaseSigningPath ?: ".release-signing/release.properties")
if (releaseSigningPath != null) {
    require(releaseSigningFile.isFile) { "Release signing properties file does not exist" }
}
val releaseSigning = Properties().apply {
    if (releaseSigningFile.isFile) releaseSigningFile.inputStream().use { load(it) }
}
android {
    namespace = "org.blefinder"
    compileSdk = 37
    defaultConfig {
        applicationId = "org.blefinder"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    testOptions { unitTests.isIncludeAndroidResources = true }
    buildTypes { release { isMinifyEnabled = false } }
    if (releaseSigningFile.isFile) {
        val signing = signingConfigs.create("release") {
            storeFile = rootProject.file(requireNotNull(releaseSigning.getProperty("storeFile")))
            storePassword = requireNotNull(releaseSigning.getProperty("storePassword"))
            keyAlias = requireNotNull(releaseSigning.getProperty("keyAlias"))
            keyPassword = requireNotNull(releaseSigning.getProperty("keyPassword"))
        }
        buildTypes.getByName("release").signingConfig = signing
    }
    // Reuse the original field-test certificate when replacing a fallback APK.
    providers.gradleProperty("fieldTestKeystore").orNull?.let { path ->
        signingConfigs.getByName("debug") {
            storeFile = rootProject.file(path)
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
}
androidComponents {
    beforeVariants(selector().withBuildType("release")) {
        it.hostTests.getValue(com.android.build.api.variant.HostTestBuilder.UNIT_TEST_TYPE).enable = true
    }
}
ksp { arg("room.schemaLocation", "$projectDir/schemas") }
dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.room:room-runtime:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.17")
    testImplementation("androidx.test:core:1.7.0")
}

// Export Gradle's selected artifacts, never a directory scan of unrelated cached versions.
listOf("debug", "release").forEach { variant ->
    tasks.register("export${variant.replaceFirstChar { it.uppercase() }}RuntimeArtifacts") {
        val runtime = configurations.named("${variant}RuntimeClasspath")
        val output = layout.buildDirectory.file("reports/$variant-runtime-artifacts.txt")
        outputs.file(output)
        outputs.upToDateWhen { false }
        doLast {
            val report = output.get().asFile
            report.parentFile.mkdirs()
            report.writeText(runtime.get().files.sortedBy { it.absolutePath }
                .joinToString("\n", postfix = "\n") { it.absolutePath })
        }
    }
}

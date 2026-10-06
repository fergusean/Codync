import java.security.MessageDigest
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.google.services)
}

val productVersion = rootProject.file("../project.yml").readLines()
    .first { it.trimStart().startsWith("MARKETING_VERSION:") }
    .substringAfter(':').trim()
// Explicit, emulator-only R8 acceptance; ordinary release artifacts stay unsigned.
val releaseTests = providers.gradleProperty("codyncReleaseTests").map(String::toBoolean).getOrElse(false)

val voiceModel = configurations.create("voiceModel") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val voiceAssets = layout.buildDirectory.dir("generated/voiceAssets")
@CacheableTask
abstract class VoiceModelAssets : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.NONE) abstract val archives: ConfigurableFileCollection
    @get:Input abstract val expectedHash: Property<String>
    @get:OutputDirectory abstract val assetDirectory: DirectoryProperty
    @TaskAction fun generate() {
        val archive = archives.singleFile
        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        check(digest.digest().joinToString("") { "%02x".format(it) } == expectedHash.get()) {
            "The pinned offline voice model's integrity check failed."
        }
        val root = assetDirectory.get().asFile
        root.deleteRecursively()
        val destination = root.resolve("voice").toPath().normalize()
        ZipFile(archive).use { zip ->
            for (entry in zip.entries()) {
                val output = destination.resolve(entry.name).normalize()
                check(output.startsWith(destination)) { "Invalid voice model archive path." }
                if (entry.isDirectory) output.toFile().mkdirs()
                else {
                    output.parent.toFile().mkdirs()
                    zip.getInputStream(entry).use { input -> output.toFile().outputStream().use { input.copyTo(it) } }
                }
            }
        }
    }
}
val prepareVoiceModel = tasks.register<VoiceModelAssets>("prepareVoiceModel") {
    archives.from(voiceModel)
    expectedHash.set("30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498")
    assetDirectory.set(voiceAssets)
}

fun javaString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""

android {
    namespace = "com.codync.android"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.sgnl24.codync.android"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = productVersion
        testInstrumentationRunner = if (releaseTests) "com.codync.android.VoiceReleaseRunner" else "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "CLOUD_URL", javaString(providers.gradleProperty("codyncCloudUrl").getOrElse("https://codync-cloud.signal24.workers.dev")))
        buildConfigField("String", "PUSH_RELAY_URL", javaString(providers.gradleProperty("codyncPushRelayUrl").getOrElse("https://codync-relay.signal24.workers.dev")))
        buildConfigField("String", "CLERK_PUBLISHABLE_KEY", javaString(providers.gradleProperty("codyncClerkPublishableKey").getOrElse("pk_test_cGlja2VkLWhhbGlidXQtOTgxMi5jbGVyay5hY2NvdW50cy5kZXYk")))
    }
    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        create("releaseAcceptance") {
            initWith(getByName("release"))
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-r8-test"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            proguardFiles("release-acceptance.pro")
        }
    }
    if (releaseTests) testBuildType = "releaseAcceptance"
    buildFeatures.compose = true
    buildFeatures.buildConfig = true
    sourceSets.getByName("androidTest").assets.srcDir(rootProject.file("../../docs/reference/fixtures"))
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
androidComponents.beforeVariants(androidComponents.selector().withBuildType("releaseAcceptance")) { it.enable = releaseTests }
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(prepareVoiceModel) { task -> task.assetDirectory }
}
afterEvaluate {
    if (releaseTests) tasks.named<com.google.gms.googleservices.GoogleServicesTask>("processReleaseAcceptanceGoogleServices") {
        googleServicesJsonFiles.set(listOf(project.layout.projectDirectory.file("src/debug/google-services.json").asFile))
    }
}
kotlin.compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)

dependencies {
    add(voiceModel.name, "com.alphacephei.models:vosk-model-small-en-us:0.15@zip")
    implementation(libs.vosk) { exclude(group = "net.java.dev.jna", module = "jna") }
    implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
    coreLibraryDesugaring(libs.desugar)
    implementation(libs.commonmark)
    implementation(libs.commonmark.tables)
    implementation(libs.commonmark.strikethrough)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    implementation(libs.clerk)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(libs.work)
    implementation(libs.glance)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(project(":core"))
    implementation(project(":design"))
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.compose)
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    debugImplementation(libs.compose.tooling)
    debugImplementation(libs.compose.test.manifest)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}

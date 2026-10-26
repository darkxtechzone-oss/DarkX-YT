plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.darkx.yt"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.darkx.yt"
        minSdk = 29; targetSdk = 34
        val vc = (project.findProperty("verCode") as String?)?.toIntOrNull() ?: 1
        val gh = (project.findProperty("ghRepo") as String?) ?: ""
        versionCode = vc
        versionName = "1.$vc"
        buildConfigField("String", "GH_REPO", "\"$gh\"")
        // Add "armeabi-v7a" here too if you need old 32-bit phones (APK gets bigger)
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    buildFeatures { buildConfig = true }
    packaging { jniLibs { useLegacyPackaging = true } }
    signingConfigs {
        create("dx") {
            storeFile = file("darkxyt.p12")
            storePassword = "darkxyt"
            keyAlias = "darkxyt"
            keyPassword = "darkxyt"
        }
    }
    buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("dx") } }
    lint { checkReleaseBuilds = false; abortOnError = false }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("io.github.junkfood02.youtubedl-android:library:0.17.4")
    implementation("io.github.junkfood02.youtubedl-android:ffmpeg:0.17.4")
    implementation("androidx.core:core:1.12.0")
}

plugins { id("com.android.application") }
val manifest = groovy.json.JsonSlurper().parse(rootProject.file("plugin.json")) as Map<*, *>
fun literal(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\""
android {
    namespace = "app.lynli.klang.jam"
    compileSdk = 37
    compileSdkMinor = 2
    defaultConfig {
        applicationId = "app.lynli.klang.jam"
        minSdk = 36
        targetSdk = 36
        versionCode = 3
        versionName = manifest["version"] as String
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "SUPABASE_URL", literal(System.getenv("SUPABASE_URL")?.takeIf { it.isNotBlank() } ?: "https://pbvojjvjboeykhrbjrgo.supabase.co"))
        buildConfigField("String", "SUPABASE_KEY", literal(System.getenv("SUPABASE_PUBLISHABLE_KEY")?.takeIf { it.isNotBlank() } ?: "sb_publishable_e58FPKaFe0oDxDAmOz7p0A_y4XCRGyi"))
    }
    signingConfigs {
        create("release") {
            System.getenv("JAM_KEYSTORE_FILE")?.let {
                storeFile = rootProject.file(it)
                storePassword = System.getenv("JAM_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("JAM_KEY_ALIAS")
                keyPassword = System.getenv("JAM_KEY_PASSWORD") ?: System.getenv("JAM_KEYSTORE_PASSWORD")
            }
        }
    }
    buildTypes { release {
        isMinifyEnabled = true
        signingConfig = signingConfigs.getByName(if (System.getenv("JAM_KEYSTORE_FILE") != null) "release" else "debug")
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    } }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.1")
    implementation("io.github.webrtc-sdk:android:144.7559.05")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20250517")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
}

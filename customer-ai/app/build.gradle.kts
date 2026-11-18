plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

val authApiBaseUrl = providers.gradleProperty("authApiBaseUrl").orElse("").get()
    .replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.blvckson.flyawaycustomer"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.blvckson.flyawaycustomer"
        minSdk = 26
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
        buildConfigField("String", "AUTH_API_BASE_URL", "\"$authApiBaseUrl\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
    kotlinOptions { jvmTarget = "1.8" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.android.gms:play-services-tasks:18.2.0")
}

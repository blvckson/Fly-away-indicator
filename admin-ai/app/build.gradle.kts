plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

val authApiBaseUrl = providers.gradleProperty("authApiBaseUrl").orElse("").get()
    .replace("\\", "\\\\").replace("\"", "\\\"")

android {
    namespace = "com.blvckson.flyawayadmin"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.blvckson.flyawayadmin"
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

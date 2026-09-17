plugins { id("com.android.application") }
android {
    namespace = "com.pawwork.template"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.pawwork.template"
        minSdk = 26
        targetSdk = 29   // v1-only signing installs fine
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

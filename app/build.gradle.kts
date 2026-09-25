plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

android {
    namespace = "com.sd.wake7"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sd.wake7"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

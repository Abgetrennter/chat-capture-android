plugins {
    id("com.android.application")
}

android {
    namespace = "app.chatcapture.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.chatcapture.android"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

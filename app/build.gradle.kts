plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.resonanse.golwatchface"
    compileSdk = 35

    defaultConfig {
        // Deliberately distinct from any other side-loaded face, so this one
        // installs alongside rather than replacing it.
        applicationId = "com.resonanse.golwatchface"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.wear.watchface:watchface:1.2.1")
    implementation("androidx.wear.watchface:watchface-complications-rendering:1.2.1")
    implementation("androidx.wear.watchface:watchface-style:1.2.1")
    implementation("androidx.wear.watchface:watchface-editor:1.2.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    compileOnly("com.google.android.wearable:wearable:2.9.0")

    // The simulation core is plain Kotlin with no Android dependency, so it is
    // covered by ordinary JVM unit tests: ./gradlew testDebugUnitTest
    testImplementation("junit:junit:4.13.2")
}

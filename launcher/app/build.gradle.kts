plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing comes from the environment, never from the repo:
//   SEEKER_KEYSTORE, SEEKER_KEYSTORE_PASSWORD, SEEKER_KEY_ALIAS, SEEKER_KEY_PASSWORD
// Without them, release builds come out unsigned.
val releaseKeystore: String? = System.getenv("SEEKER_KEYSTORE")?.takeIf { it.isNotBlank() }

android {
    namespace = "com.seeker.tv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.seeker.tv"
        minSdk = 30
        targetSdk = 34
        versionCode = 13
        versionName = "0.9.0"
    }

    // Each flavor compiles only its game modes; WHEEL is in all of them. Same applicationId for all,
    // so installing one flavor over another swaps the modes and keeps the settings.
    flavorDimensions += "modes"
    productFlavors {
        create("wheel") { dimension = "modes" }
        create("flap") { dimension = "modes" }
        create("invaders") { dimension = "modes" }
        create("hunt") { dimension = "modes" }
        create("all") { dimension = "modes" }
    }
    sourceSets {
        getByName("flap").java.srcDir("src/modeFlap/java")
        getByName("invaders").java.srcDir("src/modeInvaders/java")
        getByName("hunt").java.srcDir("src/modeHunt/java")
        getByName("all").java.srcDirs("src/modeFlap/java", "src/modeInvaders/java", "src/modeHunt/java")
    }

    signingConfigs {
        if (releaseKeystore != null) create("release") {
            storeFile = file(releaseKeystore)
            storePassword = System.getenv("SEEKER_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("SEEKER_KEY_ALIAS")
            keyPassword = System.getenv("SEEKER_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = if (releaseKeystore != null) signingConfigs.getByName("release") else null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}

// seeker-<flavor>-<version>.apk (debug builds add -debug)
android.applicationVariants.all {
    val variant = this
    outputs.all {
        val suffix = if (variant.buildType.name == "release") "" else "-${variant.buildType.name}"
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
            "seeker-${variant.flavorName}-${variant.versionName}$suffix.apk"
    }
}

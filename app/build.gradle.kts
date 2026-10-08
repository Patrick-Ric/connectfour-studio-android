plugins {
    id("com.android.application")
}

android {
    // Placeholder application ID – see DECISIONS.md (adjust before publishing).
    namespace = "io.github.patrickric.connectfourstudio"
    compileSdk = 37
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "io.github.patrickric.connectfourstudio"
        minSdk = 21
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Unsigned on purpose: F-Droid signs (or verifies) the APK itself.
            signingConfig = null
            // No META-INF/version-control-info.textproto (reproducible builds).
            vcsInfo.include = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Reproducible builds: no dependency metadata blob, no VCS info file.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    androidResources {
        // The opening book is already deflate-compressed by us.
        noCompress += "cfb"
        // Only the six languages the program is translated into.
        localeFilters += listOf("en", "de", "fr", "es", "nl", "it")
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        checkReleaseBuilds = true
        lintConfig = file("lint.xml")
    }

    // APK distribution: never split languages (in-app language switch).
    bundle {
        language {
            enableSplit = false
        }
    }

    packaging {
        resources {
            excludes += listOf("META-INF/*.version", "META-INF/**/*.kotlin_builtins", "kotlin/**")
        }
    }
}

dependencies {
    implementation(project(":core"))
}

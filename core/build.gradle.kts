// Pure Kotlin/JVM module: game rules, BitBully engine port, levels, match logic.
// No Android dependency, so everything here is covered by plain JUnit tests.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    // The engine reference tests need the 12-ply book in memory (about 21 MB).
    maxHeapSize = "1g"
    // Repository paths used by the tests (book asset, strings.xml, fixtures).
    systemProperty("cfs.root", rootProject.projectDir.absolutePath)
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

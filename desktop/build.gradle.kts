import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Windows desktop version of SF2 Extractor (Compose for Desktop).
// The SF2 engine (parser/writer) is shared with the Android app, it is not copied.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    sourceSets["main"].kotlin.srcDir("../app/src/main/java/com/ilmer/sf2tool/core")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.1")
}

compose.desktop {
    application {
        mainClass = "com.ilmer.sf2tool.desktop.MainKt"
        jvmArgs += listOf("-Xmx2g", "-Dfile.encoding=UTF-8")

        nativeDistributions {
            targetFormats(TargetFormat.Exe)
            packageName = "SF2Extractor"
            packageVersion = "1.0.0"
            description = "SF2 Extractor"
            vendor = "Ilmer"
            modules("java.desktop", "jdk.unsupported")

            windows {
                iconFile.set(project.file("icon.ico"))
                menuGroup = "SF2 Extractor"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "6b1d2c1e-5d3a-4a0f-9a63-2f6c3e8a9b41"
            }
        }
    }
}

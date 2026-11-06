import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { id("org.jetbrains.kotlin.multiplatform") }

kotlin {
    // Android consumes the JVM target. iOS targets can be added later; commonMain has no JVM APIs.
    jvm { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    sourceSets {
        commonMain.dependencies { implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1") }
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmTest.dependencies { implementation("org.xerial:sqlite-jdbc:3.49.1.0") }
    }
}

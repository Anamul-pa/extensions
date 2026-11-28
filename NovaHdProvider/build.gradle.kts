import org.jetbrains.kotlin.gradle.dsl.JvmTarget

version = 1

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

cloudstream {
    description = "NovaHD Movies and TV Series Provider"
    language = "en"
    authors = listOf("Anamul")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
}

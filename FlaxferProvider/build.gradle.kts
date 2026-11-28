import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

version = 1

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
    }
}

cloudstream {
    description = "Flaxfer Movies, TV Shows and Anime Provider"
    language = "en"
    authors = listOf("Anamul")
    status = 1
    tvTypes = listOf("Movie", "TvSeries", "Anime")
}

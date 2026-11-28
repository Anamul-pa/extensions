version = 1

android {
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

cloudstream {
    description = "NovaHD Movies and TV Series Provider"
    language = "en"
    authors = listOf("Anamul")
    status = 1
    tvTypes = listOf("Movie", "TvSeries")
}

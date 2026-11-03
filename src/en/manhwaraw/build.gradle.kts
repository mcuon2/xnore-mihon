import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

dependencies {
    implementation(project(":lib:cronet"))
    implementation(project(":lib:randomua"))
}

keiyoushi {
    name = "Manhwa-raw"
    versionCode = 1
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"

    source {
        lang = "en"
        baseUrl {
            custom("https://manhwa-raw.com")
        }
    }

    deeplink {
        path("/manga/..*")
    }
}

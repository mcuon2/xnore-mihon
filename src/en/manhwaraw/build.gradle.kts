import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Manhwa-raw"
    versionCode = 4
    contentWarning = ContentWarning.NSFW
    libVersion = "1.6"
    theme = "madara"

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

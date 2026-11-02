package eu.kanade.tachiyomi.extension.vi.truyenvn

import kotlinx.serialization.Serializable

@Serializable
class ChapterListResponseDto(
    val data: ChapterListDataDto? = null,
)

@Serializable
class ChapterListDataDto(
    val html: String = "",
)

@Serializable
class GenreOption(
    val name: String,
    val id: String,
)

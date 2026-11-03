package eu.kanade.tachiyomi.extension.en.manhwaraw

import eu.kanade.tachiyomi.source.model.Filter
import kotlinx.serialization.Serializable

@Serializable
class GenreOption(
    val name: String,
    val slug: String,
)

class SortFilter(defaultOrder: Int = 0) :
    Filter.Select<String>(
        "Order by",
        options.map { it.first }.toTypedArray(),
        defaultOrder,
    ) {
    val selected: String
        get() = options[state].second

    companion object {
        private val options = listOf(
            "Latest" to "modified",
            "A-Z" to "alphabet",
            "Rating" to "rating",
            "Trending" to "trending",
            "Most Views" to "views",
            "New" to "new-manga",
        )
    }
}

class TagFilter :
    Filter.Select<String>(
        "Tag / Status",
        options.map { it.first }.toTypedArray(),
    ) {
    val selected: String
        get() = options[state].second

    companion object {
        private val options = listOf(
            "All" to "",
            "English" to "english",
            "Raw" to "raw",
            "Completed (Finished)" to "fin",
        )
    }
}

class GenreFilter(genres: List<GenreOption>) :
    Filter.Select<String>(
        "Genre",
        (listOf("All") + genres.map { it.name }).toTypedArray(),
    ) {
    private val slugs = listOf("") + genres.map { it.slug }

    val selected: String
        get() = slugs[state]
}

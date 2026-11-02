package eu.kanade.tachiyomi.extension.vi.truyenvn

import eu.kanade.tachiyomi.source.model.Filter

class SortFilter(defaultOrder: Int = 0) :
    Filter.Select<String>(
        "Sắp xếp",
        options.map { it.first }.toTypedArray(),
        defaultOrder,
    ) {
    val selected: String
        get() = options[state].second

    companion object {
        private val options = listOf(
            "Mới cập nhật" to "latest",
            "Trending" to "trending",
            "Đọc nhiều" to "views",
            "Truyện mới" to "new-manga",
        )
    }
}

class StatusCheckBox(name: String, val id: String) : Filter.CheckBox(name)

class StatusFilter :
    Filter.Group<StatusCheckBox>(
        "Tình trạng",
        listOf(
            StatusCheckBox("OnGoing", "1"),
            StatusCheckBox("Completed", "2"),
            StatusCheckBox("Canceled", "3"),
            StatusCheckBox("On Hold", "4"),
            StatusCheckBox("Upcoming", "5"),
        ),
    )

class GenreCheckBox(name: String, val id: String) : Filter.CheckBox(name)

class GenreFilter(genres: List<GenreOption>) :
    Filter.Group<GenreCheckBox>(
        "Thể loại",
        genres.map { GenreCheckBox(it.name, it.id) },
    )

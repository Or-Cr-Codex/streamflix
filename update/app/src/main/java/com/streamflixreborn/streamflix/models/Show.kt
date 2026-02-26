package com.streamflixreborn.streamflix.models

import com.streamflixreborn.streamflix.adapters.AppAdapter

sealed interface Show : AppAdapter.Item {
    val id: String
    var isFavorite: Boolean
}

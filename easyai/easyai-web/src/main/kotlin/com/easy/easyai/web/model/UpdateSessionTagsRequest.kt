package com.easy.easyai.web.model

/** Request to overwrite the tag set of a session. An empty list clears all tags. */
data class UpdateSessionTagsRequest(
    val tags: List<String>
)

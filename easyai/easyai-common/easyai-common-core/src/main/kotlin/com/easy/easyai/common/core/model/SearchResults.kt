package com.easy.easyai.common.core.model

interface SearchResults<R, T> : Timestamped {

    /**
     * Request that generated the results
     */
    val request: R

    /**
     * Results of the search
     */
    val results: List<T>

}

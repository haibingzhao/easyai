package com.easy.easyai.common.core.model

/**
 * Standard interface for paginated data requests and results.
 */
interface Paginated {

    /**
     * The current page number, from 1
     */
    val page: Int

    /**
     * The number of items per page
     */
    val pageSize: Int

}

interface PaginatedDataRequest : Paginated {

    fun offset(): Int {
        return (page - 1) * pageSize
    }

    fun limit(): Int {
        return pageSize
    }
}

interface PaginatedDataResult : Paginated {

    /**
     * The total number of results
     */
    val total: Int

    fun nextPage(): Int {
        return page + 1
    }

    fun previousPage(): Int {
        return page - 1
    }

    fun totalPages(): Int {
        return total / pageSize
    }

    fun hasNext(): Boolean {
        return page < totalPages()
    }

    fun hasPrevious(): Boolean {
        return page > 1
    }

}

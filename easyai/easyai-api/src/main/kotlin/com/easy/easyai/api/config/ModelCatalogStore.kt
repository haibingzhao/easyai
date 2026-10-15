package com.easy.easyai.api.config

import com.easy.easyai.api.model.ModelCatalogEntry

/**
 * Storage for the global model catalog (shared across users, no owner partition).
 * Rows are written only by catalog refreshes.
 */
interface ModelCatalogStore {
    /** Exact lookup by API model id; null when the catalog does not carry it (or is empty). */
    suspend fun getByModelId(modelId: String): ModelCatalogEntry?

    /** Every catalog row, ordered by model id — for the management page and future LLM routing. */
    suspend fun listAll(): List<ModelCatalogEntry>

    /** Insert when the model id is new, otherwise update in place. @return true if a row was inserted. */
    suspend fun upsert(entry: ModelCatalogEntry): Boolean
}

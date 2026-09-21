package com.easy.easyai.autoconfigure.storage

import com.easy.easyai.core.storage.ObjectStorageResolver
import com.easy.easyai.core.storage.StorageSettingsService
import com.easy.easyai.core.storage.StorageSettingsStore
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Auto-configuration for object storage, expressed as an [ObjectStorageResolver] rather than a
 * single [com.easy.easyai.core.storage.ObjectStorage] bean: per-user rows (edited live in the
 * frontend Settings page) resolved through the shared `system` row as fallback.
 *
 * The database is the only configuration source — there are no storage properties. Without
 * R2DBC or stored rows the resolver simply answers null everywhere, which keeps every
 * storage-dependent feature off, exactly like a missing bean did.
 *
 * Two backends share one contract:
 * - `type=aliyun`: Aliyun OSS, the production object store
 * - `type=local`: a directory on disk, for dev / offline / desktop deployments
 */
@Configuration(proxyBeanMethods = false)
class ObjectStorageAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(ObjectStorageResolver::class)
    open fun objectStorageResolver(
        @Autowired(required = false) store: StorageSettingsStore? = null
    ): ObjectStorageResolver = DefaultObjectStorageResolver(store)

    @Bean
    @ConditionalOnMissingBean(StorageSettingsService::class)
    open fun storageSettingsService(
        @Autowired(required = false) store: StorageSettingsStore? = null,
        resolver: ObjectStorageResolver
    ): StorageSettingsService = DefaultStorageSettingsService(store, resolver)
}

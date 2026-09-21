package com.easy.easyai.autoconfigure.media

import com.easy.easyai.core.media.MediaProviderResolver
import com.easy.easyai.core.media.MediaProviderService
import com.easy.easyai.core.media.MediaProviderStore
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Auto-configuration for media-generation provider credentials, expressed as a [MediaProviderResolver]
 * plus a [MediaProviderService]: per-user, per-kind rows (edited live in the frontend Settings page)
 * resolved through the shared `system` row as fallback.
 *
 * The database is the only configuration source — there are no media properties. Without R2DBC or
 * stored rows the resolver answers null everywhere, which hides every generation tool, exactly like
 * a missing bean would. Registered in the same module as storage so both credential layers share one
 * activation surface; the classes stay independent.
 */
@Configuration(proxyBeanMethods = false)
class MediaProviderAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(MediaProviderResolver::class)
    open fun mediaProviderResolver(
        @Autowired(required = false) store: MediaProviderStore? = null
    ): MediaProviderResolver = DefaultMediaProviderResolver(store)

    @Bean
    @ConditionalOnMissingBean(MediaProviderService::class)
    open fun mediaProviderService(
        @Autowired(required = false) store: MediaProviderStore? = null,
        resolver: MediaProviderResolver
    ): MediaProviderService = DefaultMediaProviderService(store, resolver)
}

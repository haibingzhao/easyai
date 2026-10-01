package com.easy.easyai.autoconfigure.media

import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.core.media.MediaProviderResolver
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Auto-configuration for media-generation model entries, exposed as a single [MediaProviderResolver]
 * over `model_provider_config` rows partitioned by `model_type`. Writes go through
 * `ModelConfigService` (the Models page CRUD); this layer is read-only and hot-reloads via
 * [MediaProviderResolver.refresh] when a generation row is saved or deleted.
 *
 * The database is the only configuration source — there are no media properties. Without R2DBC or
 * stored rows the resolver answers empty everywhere, which hides every generation tool, exactly like
 * a missing bean would. Registered in the same module as storage so both credential layers share one
 * activation surface; the classes stay independent.
 */
@Configuration(proxyBeanMethods = false)
class MediaProviderAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(MediaProviderResolver::class)
    open fun mediaProviderResolver(
        @Autowired(required = false) store: com.easy.easyai.api.config.ModelProviderConfigStore? = null
    ): MediaProviderResolver = DefaultMediaProviderResolver(store)
}

package com.easy.easyai.core.agent

import com.easy.easyai.core.model.EasyAiMessage

/**
 * Default implementation that passes through messages unchanged.
 */
class DefaultTransformContextService : TransformContextService {
    override suspend fun transform(input: TransformContextInput): List<EasyAiMessage> = input.messages
}
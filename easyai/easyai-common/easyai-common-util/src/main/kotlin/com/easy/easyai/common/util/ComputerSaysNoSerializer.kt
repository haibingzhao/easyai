package com.easy.easyai.common.util

import tools.jackson.core.JsonGenerator
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.SerializationContext

/**
 * Use @JsonSerialize(using = ComputerSaysNoSerializer.class) to prevent serialization of
 * a class. This can be important to safeguard domain objects from being serialized and
 * sent to the client.
 *
 * https://www.youtube.com/watch?v=x0YGZPycMEU&ab_channel=MattLucasandDavidWalliams
 *
 * @param <T>
 */
class ComputerSaysNoSerializer<T> : ValueSerializer<T>() {

    override fun serialize(value: T, gen: JsonGenerator, serializers: SerializationContext) {
        throw IllegalArgumentException(
            "Computer says no to serializing objects of type ${value!!::class.java.name} as it is internal"
        )
    }
}

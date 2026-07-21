package com.easy.easyai.core.validation

import com.github.erosb.jsonsKema.*
import java.util.concurrent.ConcurrentHashMap

/**
 * Base class for JSON Schema validators.
 * Provides schema compilation caching and error collection from ValidationFailure trees.
 */
abstract class AbstractSchemaValidator {

    private val schemaCache = ConcurrentHashMap<String, Schema>()

    /**
     * Validate that [jsonText] conforms to the given JSON Schema.
     * This is the core validation logic shared by all schema validators.
     */
    protected fun validateJson(schema: String, jsonText: String): ValidationResult {
        val compiledSchema = getOrCompileSchema(schema)
        val parsedInstance = JsonParser(jsonText).parse()
        val validator = Validator.forSchema(compiledSchema)
        val failure = validator.validate(parsedInstance)

        return if (failure == null) {
            ValidationResult.Valid
        } else {
            ValidationResult.Invalid(collectErrors(failure))
        }
    }

    private fun getOrCompileSchema(schema: String): Schema {
        return schemaCache.computeIfAbsent(schema) {
            val parsedSchema = JsonParser(schema).parse()
            SchemaLoader(parsedSchema).load()
        }
    }

    private fun collectErrors(failure: ValidationFailure): List<String> {
        val errors = mutableListOf<String>()
        collectErrorsRecursive(failure, errors)
        return if (errors.isEmpty()) listOf(failure.toString()) else errors
    }

    private fun collectErrorsRecursive(failure: ValidationFailure, errors: MutableList<String>) {
        val causes = failure.causes
        if (causes.isNotEmpty()) {
            for (cause in causes) {
                collectErrorsRecursive(cause, errors)
            }
        } else {
            val message = failure.message
            if (message.isNotBlank()) {
                errors.add(message)
            }
        }
    }
}

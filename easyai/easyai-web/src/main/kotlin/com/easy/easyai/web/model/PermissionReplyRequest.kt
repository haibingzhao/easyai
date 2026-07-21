package com.easy.easyai.web.model

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Request model for replying to a permission request.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PermissionReplyRequest(
    /** Whether to remember this decision as a rule. */
    val remember: Boolean = false,
    /** Optional reason for denial. */
    val reason: String? = null,
    /** Permission type echoed back from the original PermissionRequestEvent. */
    val permission: String? = null,
    /** Pattern echoed back from the original PermissionRequestEvent. */
    val pattern: String? = null
)

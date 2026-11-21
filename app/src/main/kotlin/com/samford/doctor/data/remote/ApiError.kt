package com.samford.doctor.data.remote

/** A failure already translated into plain language for doctors. Never carries stack traces. */
data class ApiError(
    val code: String,
    val message: String,
    val retryable: Boolean,
    val httpStatus: Int = 0,
    val retryAfterMs: Long? = null,
) {
    /** Any 401 means "sign in again"; unsent bundles are never discarded. */
    val isAuth: Boolean get() = httpStatus == 401
}

class ApiException(val error: ApiError) : Exception(error.message)

object PlainErrors {
    const val NETWORK = "NETWORK"
    const val NO_SERVER = "NO_SERVER"
    const val BAD_RESPONSE = "BAD_RESPONSE"

    val noServer = ApiError(
        NO_SERVER,
        "No hospital server is connected yet. Your work is saved on this tablet and will upload once a server is connected.",
        retryable = true,
    )

    fun network() = ApiError(
        NETWORK,
        "Can't reach the hospital server right now. Your work is saved on this tablet and will upload automatically.",
        retryable = true,
    )

    fun badResponse(status: Int) = ApiError(
        BAD_RESPONSE,
        "The hospital server sent a reply this app could not read. Your work is safe on this tablet.",
        retryable = status >= 500,
        httpStatus = status,
    )

    /**
     * Uses the server's message only when it reads like a sentence for people; anything that
     * looks like internals (exceptions, SQL, stack frames, very long text) is replaced.
     */
    fun fromServer(status: Int, code: String, message: String, retryable: Boolean, retryAfterMs: Long? = null): ApiError {
        val lower = message.lowercase()
        val looksTechnical = message.length > 240 ||
            listOf("exception", "sqlite", "select ", "insert ", "stack", "errno", "undefined", "null pointer", "\n    at ")
                .any { lower.contains(it) }
        val text = when {
            code == "APP_UPDATE_REQUIRED" || status == 426 -> "Please install the latest Samford Doctor app to keep syncing. Your work is safe on this tablet."
            message.isBlank() || looksTechnical -> fallback(status)
            else -> message
        }
        return ApiError(
            code = code.ifBlank { "HTTP_$status" },
            message = text,
            retryable = retryable || status >= 500 || status == 429,
            httpStatus = status,
            retryAfterMs = retryAfterMs,
        )
    }

    fun fallback(status: Int): String = when {
        status == 401 -> "Please sign in to the hospital server again. Your work is saved on this tablet."
        status == 403 -> "This account isn't allowed to do that. Your work is saved on this tablet."
        status == 404 -> "The hospital server could not find that visit or document."
        status == 409 -> "The hospital already has a different version of this. Your copy is saved on this tablet."
        status == 413 -> "A page is too large for the hospital server to accept."
        status == 422 || status == 400 -> "The hospital server could not accept this record. Your copy is saved on this tablet."
        status == 429 -> "The hospital server is busy. The app will try again shortly."
        status >= 500 -> "The hospital server had a problem. The app will try again automatically."
        else -> "Something went wrong talking to the hospital server. Your work is saved on this tablet."
    }
}

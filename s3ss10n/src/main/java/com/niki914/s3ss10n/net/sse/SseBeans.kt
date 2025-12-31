package com.niki914.s3ss10n.net.sse

import java.io.IOException

internal sealed interface SseEvent {
    data object Start : SseEvent
    data class IOError(val cause: Throwable) : SseEvent
    data class RequestFailedError(
        val code: Int,
        val body: String?
    ) : SseEvent

    data class Data(val content: String) : SseEvent
    data class Complete(
        val isSuccess: Boolean,
        val cause: Throwable?
    ) : SseEvent
}

internal sealed interface SseProtocol {
    data object Done : SseProtocol
    data object Nothing : SseProtocol
    data class Data(val value: String) : SseProtocol
    data class NotSse(val raw: String) : SseProtocol
}

/**
 * Thrown when the SSE stream ends before receiving the [DONE] marker.
 */
class SseNotCompleteException() : IOException("Buffer ended without receiving [DONE]!")

/**
 * Thrown when the server response is not in SSE format.
 */
class NotSseException(val response: String) : IOException("API is not using SSE protocol!")
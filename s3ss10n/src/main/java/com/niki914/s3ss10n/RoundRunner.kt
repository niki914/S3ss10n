package com.niki914.s3ss10n

import com.niki914.s3ss10n.ext.protocol.ChatProtocol
import com.niki914.s3ss10n.ext.protocol.ProtocolEvent
import com.niki914.s3ss10n.net.HttpEngine
import com.niki914.s3ss10n.net.SseLineParser
import com.niki914.s3ss10n.util.HistoryKeeper
import com.niki914.s3ss10n.util.ToolCallWaiter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collect

internal data class RoundInput(
    val snapshot: SessionSnapshot,
    val initialInput: String,
    val onEvent: suspend (SessionEvent) -> Unit
)

internal class RoundRunner(
    private val protocol: ChatProtocol,
    private val engine: HttpEngine,
    private val historyKeeper: HistoryKeeper,
    private val toolCallCoordinator: ToolCallCoordinator,
    private val scope: CoroutineScope
) {
    private val toolCallWaiter = ToolCallWaiter<RoundContext>(scope) { toolCall, ctx ->
        toolCallCoordinator.handle(toolCall, ctx.snapshot) { event ->
            emitCoordinatorEvent(ctx, event)
        }
    }

    internal suspend fun run(input: RoundInput) {
        val ctx = RoundContext(
            snapshot = input.snapshot,
            onEvent = input.onEvent,
            initialInput = input.initialInput
        )
        doRound(ctx, input.initialInput)
    }

    internal suspend fun cancelAndClearTools(join: Boolean = false) {
        toolCallWaiter.cancelAndClear(join)
    }

    private suspend fun doRound(ctx: RoundContext, pendingUserInput: String?) {
        xTrySuspend("RoundRunner.doRound", onError = { t ->
            if (t is RoundEventCallbackException) {
                throw t.original
            }
            unwrapRoundEventCallbackException {
                emitRoundError(
                    ctx,
                    SessionEvent.Error(
                        stage = classifyStage(t),
                        message = t.message ?: "Round failed",
                        cause = t
                    )
                )
                finishRoundIfStarted(ctx)
            }
        }) {
            ensureConfigValid(ctx.snapshot)

            val fullText = StringBuilder()
            val reasoningContent = StringBuilder()
            var reasoningSignature: String? = null
            val toolCalls = mutableListOf<ToolCallSpec>()
            val req = protocol.buildRequest(
                snapshot = ctx.snapshot,
                history = historyKeeper.snapshot(),
                pendingUserInput = pendingUserInput
            )

            val authHeaders = protocol.useApiKey(ctx.snapshot.apiKey)
            val mergedAuthAndCustom = mergeHeadersWithCustomOverride(
                authHeaders,
                ctx.snapshot.headers
            )
            val effectiveReq = req.copy(headers = req.headers + mergedAuthAndCustom)

            val rawFlow = engine.stream(effectiveReq)
            val sseData = SseLineParser.parse(rawFlow)
            protocol.parseStream(sseData).collect { event ->
                startRoundIfNeeded(ctx)
                when (event) {
                    is ProtocolEvent.TextDelta -> {
                        fullText.append(event.text)
                        ctx.textAccumulator.append(event.text)
                        emitRoundEvent(
                            ctx,
                            SessionEvent.TextDelta(
                                delta = event.text,
                                fullText = ctx.textAccumulator.toString()
                            )
                        )
                    }

                    is ProtocolEvent.ReasoningDelta -> {
                        reasoningContent.append(event.text)
                    }

                    is ProtocolEvent.ReasoningSignature -> {
                        reasoningSignature = event.signature
                    }

                    is ProtocolEvent.ToolCallReady -> {
                        val toolCall = ToolCallSpec(
                            callId = event.callId,
                            toolName = event.toolName,
                            argumentsJson = event.argumentsJson
                        )
                        toolCalls += toolCall
                        toolCallWaiter.enqueue(toolCall, ctx)
                    }

                    is ProtocolEvent.Error -> {
                        emitRoundError(
                            ctx,
                            SessionEvent.Error(
                                stage = event.stage,
                                message = event.cause.message ?: "Protocol error",
                                cause = event.cause
                            )
                        )
                    }

                    ProtocolEvent.Completed -> Unit
                }
            }

            val committed = commitRoundTurnsIfValid(
                ctx = ctx,
                userInput = pendingUserInput,
                assistantContent = fullText.toString(),
                toolCalls = toolCalls.toList(),
                reasoningContent = reasoningContent.toString().ifEmpty { null },
                reasoningSignature = reasoningSignature
            )
            if (!committed) {
                finishRoundIfStarted(ctx)
                return@xTrySuspend
            }

            if (!toolCallWaiter.isEmpty()) {
                responseToolCalls(ctx)
            } else {
                finishRoundIfStarted(ctx)
            }
        }
    }

    private suspend fun startRoundIfNeeded(ctx: RoundContext) {
        if (ctx.hasStarted) return
        emitRoundEvent(ctx, SessionEvent.RoundStarted(input = ctx.initialInput))
        ctx.hasStarted = true
    }

    private suspend fun finishRoundIfStarted(ctx: RoundContext) {
        if (!ctx.hasStarted || ctx.hasCompleted) return
        emitRoundEvent(
            ctx,
            SessionEvent.RoundCompleted(
                fullText = ctx.textAccumulator.toString()
            )
        )
        ctx.hasCompleted = true
    }

    private suspend fun emitRoundError(ctx: RoundContext, event: SessionEvent.Error) {
        ctx.hasError = true
        emitRoundEvent(ctx, event)
    }

    private suspend fun emitCoordinatorEvent(ctx: RoundContext, event: SessionEvent) {
        if (event is SessionEvent.Error) {
            emitRoundError(ctx, event)
        } else {
            emitRoundEvent(ctx, event)
        }
    }

    private suspend fun emitRoundEvent(ctx: RoundContext, event: SessionEvent) {
        try {
            ctx.onEvent(event)
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            throw RoundEventCallbackException(t)
        }
    }

    private suspend fun unwrapRoundEventCallbackException(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: RoundEventCallbackException) {
            throw e.original
        }
    }

    private suspend fun commitRoundTurnsIfValid(
        ctx: RoundContext,
        userInput: String?,
        assistantContent: String,
        toolCalls: List<ToolCallSpec>,
        reasoningContent: String?,
        reasoningSignature: String?
    ): Boolean {
        val hasAssistantPayload = assistantContent.isNotBlank() || toolCalls.isNotEmpty()
        if (!hasAssistantPayload) {
            if (!ctx.hasError) {
                emitRoundError(
                    ctx,
                    SessionEvent.Error(
                        stage = SessionEvent.Stage.Parse,
                        message = "Empty assistant response"
                    )
                )
            }
            return false
        }

        if (userInput != null) {
            historyKeeper.add(ChatTurn.User(userInput))
        }
        historyKeeper.add(
            ChatTurn.Assistant(
                content = assistantContent,
                toolCalls = toolCalls,
                reasoningContent = reasoningContent,
                reasoningSignature = reasoningSignature
            )
        )
        return true
    }

    private suspend fun responseToolCalls(ctx: RoundContext) {
        val results = toolCallWaiter.awaitAll()
        results.forEach { (toolCall, msg) ->
            historyKeeper.add(
                protocol.encodeToolResult(
                    callId = toolCall.callId,
                    toolName = toolCall.toolName,
                    resultJson = msg.contentJson
                )
            )
        }
        doRound(ctx, null)
    }

    private fun ensureConfigValid(snapshot: SessionSnapshot) {
        if (!snapshot.endpoint.startsWith("http://") && !snapshot.endpoint.startsWith("https://")) {
            throw ConfigInvalidException()
        }
        if (snapshot.model.isBlank()) {
            throw ConfigInvalidException()
        }
    }

    private fun mergeHeadersWithCustomOverride(
        authHeaders: Map<String, String>,
        customHeaders: Map<String, String>
    ): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        val customKeysLower = customHeaders.keys.mapTo(HashSet()) { it.lowercase() }
        for ((key, value) in authHeaders) {
            if (key.lowercase() !in customKeysLower) {
                result[key] = value
            }
        }
        result.putAll(customHeaders)
        return result
    }

    private fun classifyStage(t: Throwable): SessionEvent.Stage {
        return when (t) {
            is ConfigInvalidException -> SessionEvent.Stage.Session
            is IllegalArgumentException -> SessionEvent.Stage.Session
            is IllegalStateException -> SessionEvent.Stage.Parse
            else -> SessionEvent.Stage.Transport
        }
    }
}

private class RoundContext(
    val snapshot: SessionSnapshot,
    val onEvent: suspend (SessionEvent) -> Unit,
    val initialInput: String,
    val textAccumulator: StringBuilder = StringBuilder(),
    var hasStarted: Boolean = false,
    var hasCompleted: Boolean = false,
    var hasError: Boolean = false
)

private class RoundEventCallbackException(
    val original: Throwable
) : RuntimeException(original)

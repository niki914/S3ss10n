package com.niki914.s3ss10n.net.sse

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import okhttp3.Call
import okhttp3.Response
import java.io.IOException

/**
 * SSE Protocol wrapper
 */
internal class SseClient {

    /**
     * easily attach #execute into other flows
     */
    suspend inline fun <reified T> execute(
        call: Call,
        collector: FlowCollector<T>,
        crossinline onEvent: (SseEvent) -> List<T>
    ) {
        execute(call)
            .catch { t ->
                val tList = onEvent(SseEvent.IOError(t))
                tList.forEach { t ->
                    collector.emit(t)
                }
            }
            .collect { event ->
                val tList = onEvent(event)
                tList.forEach { t ->
                    collector.emit(t)
                }
            }
    }

//    /**
//     * easily attach #execute into other flows
//     */
//    suspend inline fun <reified T> execute(
//        call: Call,
//        collector: FlowCollector<T>,
//        crossinline onEvent: (SseEvent) -> T?
//    ) {
//        execute(call).collect { event ->
//            val t = onEvent(event) ?: return@collect
//            collector.emit(t)
//        }
//    }

    fun execute(call: Call): Flow<SseEvent> = flow {
        emit(SseEvent.Start)

        var hasFailed = false
        var throwable: Throwable? = null

        try {
            val response = call.execute()

            response.use { r ->
                if (!r.isSuccessful) {
                    emit(
                        SseEvent.RequestFailedError(
                            code = r.code,
                            body = r.body?.string()
                        )
                    )
                    hasFailed = true
                } else {
                    handleResponse(r)
                }
            }
        } catch (t: Throwable) {
            throwable = t
            emit(SseEvent.IOError(t))
            hasFailed = true
            call.cancel()
        } finally {
            val isSuccess = !hasFailed
            emit(
                SseEvent.Complete(
                    isSuccess = isSuccess,
                    cause = throwable
                )
            )
        }
    }

    private suspend fun FlowCollector<SseEvent>.handleResponse(response: Response) {
        val reader = response.body?.byteStream()?.bufferedReader()
            ?: throw IOException("Response body is null!")

        var isDone = false;

        reader.useLines { lines ->
            lines.forEach { line ->
                when (val p = SseParser.parseLine(line)) {
                    is SseProtocol.Data ->
                        emit(SseEvent.Data(p.value))

                    SseProtocol.Done -> {
                        isDone = true
                        return@useLines
                    }

                    is SseProtocol.NotSse -> {
                        throw NotSseException(p.raw)
                    }

                    SseProtocol.Nothing -> {}
                }
            }
        }

        if (!isDone) throw SseNotCompleteException()
    }

    companion object {
        private object SseParser {

            fun parseLine(line: String): SseProtocol {
                when {
                    line.isEmpty() || line.startsWith(":") -> return SseProtocol.Nothing

                    line.startsWith("data: [DONE]") -> return SseProtocol.Done

                    line.startsWith("data:") -> {
                        val content = line.substring(5).trim()
                        return SseProtocol.Data(content)
                    }

                    else -> return SseProtocol.NotSse(line)
                }
            }
        }
    }
}
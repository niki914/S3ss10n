package com.niki914.s3ss10n.net

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.takeWhile

object SseLineParser {
    fun parse(rawLines: Flow<String>): Flow<String> = rawLines
        .filter { it.startsWith("data:") }
        .map { it.substring(5).trim() }
        .takeWhile { it != "[DONE]" }
}

package com.niki914.s3ss10n.protocol

import com.niki914.s3ss10n.ChatTurn
import com.niki914.s3ss10n.SessionConfig
import kotlinx.coroutines.flow.Flow

interface ChatProtocol {
    /**
     * tool call delta 的拼接由协议实现自行负责。
     * TODO(T6): HttpEngine 落地后，这里的返回值可能从 String 改成 HttpRequest 值对象。
     */
    fun buildRequestBody(
        snapshot: SessionConfig,
        history: List<ChatTurn>,
        pendingUserInput: String?
    ): String

    fun parseStream(rawSseLines: Flow<String>): Flow<ProtocolEvent>

    fun encodeToolResult(
        callId: String,
        toolName: String,
        resultJson: String
    ): ChatTurn.ToolResult
}

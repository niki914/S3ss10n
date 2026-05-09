package com.niki914.s3ss10n.protocol

import com.niki914.s3ss10n.ChatTurn
import com.niki914.s3ss10n.SessionConfig
import com.niki914.s3ss10n.json.JsonCodec
import com.niki914.s3ss10n.net.HttpRequest
import kotlinx.coroutines.flow.Flow

interface ChatProtocol {
    /**
     * 允许注入自定义的 JSON 编解码器。
     * 协议可以自行决定是否接受该注入，默认实现为 no-op 返回自身。
     */
    fun withCodec(codec: JsonCodec): ChatProtocol = this

    /**
     * tool call delta 的拼接由协议实现自行负责。
     */
    fun buildRequest(
        snapshot: SessionConfig,
        history: List<ChatTurn>,
        pendingUserInput: String?
    ): HttpRequest

    fun parseStream(rawSseLines: Flow<String>): Flow<ProtocolEvent>

    fun encodeToolResult(
        callId: String,
        toolName: String,
        resultJson: String
    ): ChatTurn.ToolResult
}

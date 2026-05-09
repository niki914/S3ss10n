package com.niki914.s3ss10n.protocol

import com.niki914.s3ss10n.ChatTurn
import com.niki914.s3ss10n.SessionConfig
import com.niki914.s3ss10n.json.JsonCodec
import kotlinx.coroutines.flow.Flow

interface ChatProtocol {
    /**
     * 允许注入自定义的 JSON 编解码器。
     * 协议可以自行决定是否接受该注入，默认实现为 no-op 返回自身。
     */
    fun withCodec(codec: JsonCodec): ChatProtocol = this

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

package com.niki914.s3ss10n.chat.protocol

import com.google.gson.annotations.SerializedName

/**
 * 简化的流式聊天补全响应体
 */
internal data class ChatApiResponseFrame(
    @SerializedName("choices") val choices: List<Choice?>?
)
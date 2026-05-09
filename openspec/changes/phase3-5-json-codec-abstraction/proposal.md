## Why

T4 完成后，所有协议形态已经收敛在 OpenAIProtocol（以及未来的其他 Protocol 实现）。但 OpenAIProtocol 内部仍然直接 `import com.google.gson.Gson`，等于把 Gson 钉死在 :s3ss10n 模块里。这与"未来抽离 net 和 json 两个抽象层"的目标冲突。

当前 :s3ss10n 模块对 Gson 的直接依赖点（T4 完成后）：
1. OpenAIProtocol 内部（buildRequestBody / parseStream / encodeToolResult 涉及的 JSON 编解码）
2. LocalToolRegistry（schema JSON 与参数 JSON 解析）
3. 任何老的 `JsonUtil` / `Gson()` 散点

T5 引入 JsonCodec 抽象，让 :s3ss10n 模块对 Gson 只剩一个 binding 文件（`GsonJsonCodec`），未来要换 kotlinx.serialization / Moshi 只换一个文件。

## What Changes

- **新增**：`s3ss10n/json/JsonCodec.kt` 接口，定义最小职责：`encode(value: Any?): String` / `decode(json: String, type: Class<T>): T?` / `decodeMap(json: String): Map<String, Any?>?`
- **新增**：`s3ss10n/json/GsonJsonCodec.kt` 默认实现，是 :s3ss10n 内部唯一直接依赖 Gson 的文件
- **修改**：`OpenAIProtocol`：构造参数 `jsonCodec: JsonCodec = GsonJsonCodec()`，所有 Gson 调用改为走 jsonCodec
- **修改**：`LocalToolRegistry`：如果内部有 JSON 编解码，改为通过依赖注入或单例获取 `JsonCodec`
- **修改**：`SessionConfig.Builder`：可选支持 `jsonCodec: JsonCodec? = null` 自定义入口（如果用户想替换 codec）
- **删除**：所有除 `GsonJsonCodec` 外的 `import com.google.gson.*`
- **删除**：现有的 `s3ss10n/util/JsonUtil.kt`（如果存在），逻辑并入 GsonJsonCodec

## Capabilities

### New Capabilities

- `json-codec-abstraction`: JsonCodec 接口 + GsonJsonCodec 默认绑定，模块对 Gson 的依赖收口到一个文件

### Modified Capabilities

- `protocol-abstraction`: ChatProtocol 实现可选地接收 JsonCodec 注入

## Impact

- 新增：`s3ss10n/json/JsonCodec.kt`、`s3ss10n/json/GsonJsonCodec.kt`
- 修改：`s3ss10n/protocol/openai/OpenAIProtocol.kt`（构造注入 JsonCodec）
- 修改：`s3ss10n/LocalToolRegistry.kt`（注入 JsonCodec）
- 修改：`s3ss10n/SessionConfig.kt`（可选 jsonCodec 字段；缺省 null = 用 GsonJsonCodec）
- 删除：`s3ss10n/util/JsonUtil.kt`（如存在）
- 全局清理：除 `GsonJsonCodec.kt` 外，禁止 `import com.google.gson.*`

## Non-Goals

- 拿掉 Gson Gradle 依赖（仍然 implementation 在 :s3ss10n 中，用户也可以通过自定义 JsonCodec 不用 Gson）
- HTTP 抽象（T6）
- xTry / xLog 落地（T7）
- 引入 kotlinx.serialization / Moshi 实现

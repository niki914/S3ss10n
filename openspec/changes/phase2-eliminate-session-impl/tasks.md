## 1. 重构 ChatSession 实现 Session 接口

- [x] 1.1 ChatSession 添加 `: Session` 接口声明，添加 Session 方法签名（send/getHistory/resetConversation/close）
- [x] 1.2 将 SessionImpl 的字段（config, userOnEvent, currentInput, textAccumulator, toolManager）迁移到 ChatSession
- [x] 1.3 将 SessionImpl 的 Callback 实现逻辑内联到 ChatSession 的 ChatEvent 处理中（onStarted → emit RoundStarted, onContent → emit TextDelta, onToolCall → hooks 调度并 emit ToolRunning/Succeeded/Failed, onCompleted → emit RoundCompleted）
- [x] 1.4 修改 ToolCallWaiter 的 tool call handler：从 callback?.onToolCall() 改为直接调用 ChatSession 的内部 suspend 方法
- [x] 1.5 删除 ChatSession.Callback 接口和 callback 属性
- [x] 1.6 删除 ChatSession 的 updateConfig() 公开方法，改为内部 applyConfig()

## 2. 修复 fullText 跨轮累积

- [x] 2.1 将 textAccumulator.clear() 从当前 onStarted() 位置移到 send() 方法入口（public send，非内部 sendMessage(null)）
- [x] 2.2 确保递归 sendMessage(null) 不触发 accumulator 清空

## 3. 更新 Session 入口点

- [x] 3.1 修改 Session.Companion.open 直接构造 ChatSession(config)，移除 SessionImpl 引用
- [x] 3.2 添加 ChatSession(config: SessionConfig) 构造函数，从 SessionConfig 提取参数初始化 ChatClient

## 4. 删除 SessionImpl

- [x] 4.1 删除 SessionImpl.kt
- [x] 4.2 确认无其他文件引用 SessionImpl

## 5. 更新测试

- [x] 5.1 更新 SessionImplTest.kt → 更新描述文本，测试通过 Session.open{} 透明工作
- [x] 5.2 新增 fullText 跨轮累积烟测（FullTextAccumulationTest.kt）
- [x] 5.3 IntegrationTest.kt 无需修改（使用 Session.open{}，透明兼容）

## 6. 编译验证

- [x] 6.1 `:s3ss10n:compileDebugKotlin` — 通过（用户确认）
- [x] 6.2 `:app:compileDebugKotlin` — 通过（用户确认）
- [ ] 6.3 运行所有烟测 main() 函数验证行为正确

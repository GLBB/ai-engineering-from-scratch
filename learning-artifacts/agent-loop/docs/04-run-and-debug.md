# 4. 动手运行和调试

[返回目录](README.md) · 上一篇：[协议、流式输出和轨迹](03-protocols-and-streaming.md) · 下一篇：[工具调用上限、终止条件和对话记录](05-tool-calls-and-transcripts.md)

## 启动前准备

需要 Java 17 或更高版本、Maven 和 DeepSeek API key。`DEEPSEEK_API_KEY` 从启动 Java 进程的环境读取；搜索工具使用 `EXA_AI_KEY`，也接受 `EXA_API_KEY`。没有 Exa key 时，其余功能仍可使用。

在本项目目录运行：

```bash
mvn test
mvn spring-boot:run
```

打开 `http://127.0.0.1:8080`。端口可通过 `AGENT_PORT` 指定。完成后在运行服务的终端按 `Ctrl+C`。

如果从 IDEA 启动，运行 [WebMain](../src/main/java/dev/learning/agent/app/WebMain.java) 的 `main`，并确保该运行配置能读取环境变量。终端能读取变量，不代表 IDEA 启动的进程也能读取；具体配置可看 [README 的 IDEA 章节](../README.md#在-intellij-idea-中)。

## 实验一：观察一次工具调用

1. 在页面输入“请使用计算器计算 `(120+18)/3`”。模型可能自行决定是否调用工具。
2. 切到“轨迹”，先看用户输入和模型设置，再查看模型请求与工具行。
3. 展开 `calculator` 工具行，查看参数和结果；再观察后续模型请求及最终回答。
4. 对照 [AgentLoop.run](../src/main/java/dev/learning/agent/core/ReActRunner.java) 中的 `client.stream`、`tools.dispatch` 和 `messages.add`。

如果没有工具行，说明本轮没有观察到工具调用；可换一个问题重试。轨迹不是预先写好的演示。

## 实验二：观察协议设置

1. 点击页面左下角“设置”，记录当前协议。
2. 选择 Responses 或 Anthropic Messages，保存。页面显示设置待重启生效。
3. 停止并重新启动应用，再打开设置确认当前协议已变化。
4. 发送一条消息，查看轨迹中的模型设置。协议配置保存在 `config/settings.json`；这个文件被 Git 忽略。

重启会清空内存会话。更换协议后，模型和推理等级仍在输入框选择；`Off` 代表关闭 thinking。

## 适合设置断点的位置

| 想看什么 | 断点位置 | 观察内容 |
| --- | --- | --- |
| 浏览器提交了什么 | [ChatController.chat](../src/main/java/dev/learning/agent/app/chat/ChatController.java) | `body`、`settings`、会话是否存在。 |
| 上下文和循环 | [AgentLoop.run](../src/main/java/dev/learning/agent/core/ReActRunner.java) 的 `client.stream` 调用前 | `messages`、当前轮次、工具定义。 |
| 模型要求执行什么 | [AgentLoop.run](../src/main/java/dev/learning/agent/core/ReActRunner.java) 的 `tools.dispatch` 调用前 | 工具名称、参数和调用 ID。 |
| 工具如何处理参数 | [ToolRegistry.dispatch](../src/main/java/dev/learning/agent/tool/ToolRegistry.java) | 工具查找及返回结果。 |
| 页面为什么收到确认请求 | [BrowserApproval](../src/main/java/dev/learning/agent/app/chat/BrowserApproval.java) 的 `request` | 确认 ID、类型，以及等待用户决定的过程。 |
| 协议设置何时生效 | [SettingsStore](../src/main/java/dev/learning/agent/app/settings/SettingsStore.java) 和 [AgentSessions](../src/main/java/dev/learning/agent/app/chat/AgentSessions.java) 构造方法 | 已保存协议与启动时选择的客户端。 |

调试涉及 Bash 命令时，页面会分别询问是否执行命令、是否将输出发送给模型。

## 测试和日志

```bash
mvn test
```

测试不要求调用真实模型。可从 [ReActRunnerTraceTest](../src/test/java/dev/learning/agent/core/ReActRunnerTraceTest.java) 看循环和轨迹，从 [ProtocolAdaptersTest](../src/test/java/dev/learning/agent/deepseek/ProtocolAdaptersTest.java) 看协议事件与工具结果回传，从 [SettingsStoreTest](../src/test/java/dev/learning/agent/app/settings/SettingsStoreTest.java) 看设置保存和重启生效。

运行日志写入项目的 `logs/agent.log`：

```bash
tail -f logs/agent.log
```

日志记录模型轮次、HTTP 状态、工具名称和耗时；对话详情与轨迹在页面查看。接口报错时，先看页面的错误信息，再结合日志定位是模型请求、工具调用还是本地启动问题。

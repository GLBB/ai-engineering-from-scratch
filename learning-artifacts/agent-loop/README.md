# Java Agent Loop

这是 Phase 14 第 01 课后的个人 Maven 练习。项目使用 DeepSeek `deepseek-flash` 的流式 Chat Completions 接口，保留多轮消息历史，让模型调用 `calculator`、`bash` 和 `search` 工具。应用层使用明确的 Java 请求、响应、消息和工具参数类型；Jackson 仅负责 JSON 编解码。

## 在 WSL 终端运行

项目使用 Java 17 语法。当前 WSL 的 `~/soft` 中已配置 Temurin JDK 25 和 Maven 3.10.0；新终端会加载 `~/.bashrc`。从本目录运行：

```bash
mvn test
mvn -q compile exec:java
```

看到 `you>` 后输入问题，答案会随着 DeepSeek 的 SSE 文本片段逐步显示。输入 `/exit` 或发送 EOF 退出。`DEEPSEEK_API_KEY` 必须存在；`DEEPSEEK_MODEL` 默认为 `deepseek-flash`，`DEEPSEEK_BASE_URL` 默认为 `https://api.deepseek.com`。

`search` 使用 Exa 的 `POST https://api.exa.ai/search`，需要环境变量 `EXA_API_KEY`。没有 Exa key 时，其余功能正常运行；调用 `search` 会向模型返回“缺少 key”的错误观察结果。不要把 key 写进代码或提交到 Git。

`bash` 在当前项目目录执行命令，单次最长 15 秒，返回内容最多约 12 KB。每次会先询问是否执行，再展示结果并询问是否发送给 DeepSeek。Bash 子进程不会继承 DeepSeek 和 Exa 的 API key。请检查命令和输出再批准，因为 Bash 命令本身仍能访问你有权限读取的本地文件。

## 在 IntelliJ IDEA 中

打开本目录的 `pom.xml`，选择 **Open as Project**，使用 WSL JDK 作为 Project SDK。若已经打开整个仓库，可从 Maven 工具窗口选择 **Link Maven Projects** 并选中这个 `pom.xml`。同步后，`src/main/java/dev/learning/agent/Main.java` 的 `main` 方法左侧会出现运行按钮。IDEA 运行配置需要提供 `DEEPSEEK_API_KEY`；Windows 上的 IDEA 不会自动继承 WSL `~/.bashrc` 中的变量。

## 代码位置

- `ChatRequest.java`、`ChatMessage.java`、`ToolSpec.java`、`ChatChunk.java`：Chat 请求、消息、工具 Schema 和 SSE 响应实体。
- `DeepSeekClient.java`、`StreamAssembler.java`：HTTP 请求和流式 SSE 合并。
- `AgentLoop.java`、`ToolRegistry.java`：循环、工具调用与结果回填。
- `CalculatorTool.java`、`BashTool.java`、`SearchTool.java`：三个工具。
- `Main.java`：交互式命令行。
- `AGENTS.md`：本项目的代码规范与安全边界。

接口依据：[DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)、[DeepSeek Tool Calls](https://api-docs.deepseek.com/guides/tool_calls/) 和 [Exa Search](https://exa.ai/docs/reference/search)。

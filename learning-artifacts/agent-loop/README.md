# Java Agent Loop

这是 Phase 14 第 01 课后的个人 Maven 练习。项目使用 DeepSeek `deepseek-flash` 的流式 Chat Completions 接口，保留多轮消息历史，让模型调用 `calculator`、`bash` 和 `search` 工具。应用层使用明确的 Java 请求、响应、消息和工具参数类型；Jackson 仅负责 JSON 编解码。

## 在 WSL 终端运行

项目使用 Java 17 语法。当前 WSL 的 `~/soft` 中已配置 Temurin JDK 25 和 Maven 3.10.0；新终端会加载 `~/.bashrc`。从本目录运行：

```bash
mvn test
mvn -q compile exec:java
```

看到 `you>` 后输入问题，答案会随着 DeepSeek 的 SSE 文本片段逐步显示。输入 `/exit` 或发送 EOF 退出。`DEEPSEEK_API_KEY` 必须存在；`DEEPSEEK_MODEL` 默认为 `deepseek-flash`，`DEEPSEEK_BASE_URL` 默认为 `https://api.deepseek.com`。

`search` 使用 Exa 的 `POST https://api.exa.ai/search`。优先读取你配置的 `EXA_AI_KEY`，也兼容 `EXA_API_KEY`。没有 Exa key 时，其余功能正常运行；调用 `search` 会向模型返回“缺少 key”的错误观察结果。不要把 key 写进代码或提交到 Git。

`bash` 在当前项目目录执行命令，单次最长 15 秒，返回内容最多约 12 KB。每次会先询问是否执行，再展示结果并询问是否发送给 DeepSeek。Bash 子进程不会继承 DeepSeek 和 Exa 的 API key。请检查命令和输出再批准，因为 Bash 命令本身仍能访问你有权限读取的本地文件。

## 日志

使用 SLF4J + Logback，配置在 `src/main/resources/logback.xml`。运行时会自动创建项目下的 `logs/`；`agent.log` 是当前日志，每天归档、单个归档最多 1 MB，保留最近 5 天，归档总量上限 5 MB。终端默认显示 `INFO` 级别的轮次、接口状态、工具执行和耗时；文件还保存 `DEBUG` 级别的 SSE 分片大小等细节。设置 `AGENT_LOG_LEVEL=DEBUG` 可以把细节也显示在终端。日志文件已加入 Git 忽略，不记录 API key 或完整 Bash 输出。

## 在 IntelliJ IDEA 中

打开本目录的 `pom.xml`，选择 **Open as Project**，使用 WSL JDK 作为 Project SDK。若已经打开整个仓库，可从 Maven 工具窗口选择 **Link Maven Projects** 并选中这个 `pom.xml`。同步后，`src/main/java/dev/learning/agent/app/Main.java` 的 `main` 方法左侧会出现运行按钮。IDEA 运行配置需要提供 `DEEPSEEK_API_KEY` 和 `EXA_AI_KEY`；Windows 上的 IDEA 不会自动继承 WSL `~/.bashrc` 中的变量。

## 代码位置

- `app/`：交互式命令行入口。
- `core/`：Agent 循环与消息历史。
- `deepseek/model/`：请求、消息、工具 Schema 和 SSE 响应实体。`ChatRequest.java` 列出了官方 Chat Completions 的顶层请求参数，并注明用途；本 demo 仅发送实际需要的字段。
- `deepseek/`：HTTP 请求和流式 SSE 合并。
- `tool/`：工具注册与具体工具。
- `src/main/resources/logback.xml`：控制台和项目日志配置。
- `AGENTS.md`：本项目的代码规范与安全边界。

接口依据：[DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)、[DeepSeek Tool Calls](https://api-docs.deepseek.com/guides/tool_calls/) 和 [Exa Search](https://exa.ai/docs/reference/search)。

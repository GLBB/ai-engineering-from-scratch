# Agent Loop 项目约定

本目录是 Java 17 / Spring Boot 学习项目。
编码与评审依据见 [Java 项目规范](docs/standards/java-development-standard.md)；
工具用法、IDEA 设置和检查时机见[检查说明](docs/standards/quality-checks.md)。

## 项目边界

- 调整分包或执行流程前阅读[架构说明](docs/01-architecture.md)，保持 `PackageStructureTest` 保护的依赖边界。
- 外部请求、响应及工具参数使用明确 Java 类型；新增依赖先检查标准库和已有依赖，并固定版本。
- 保留应用运行时的命令执行及命令输出外发审批，以及预算、取消、超时和资源关闭规则。
- API key 仅从环境变量读取，不写入源码、日志、测试数据或 Git。

## 临时文件

- 临时脚本、检查输出、缓存和认证二维码统一放本项目 `tmp/`，不提交 Git；用完清理，敏感文件及时删除。
- 清理时保留 `.gitkeep`，不删除其他任务仍在使用的文件；长期成果放正式目录。

## 验证与交付

以下命令在本项目目录执行：

- 完成 Java 逻辑改动：`python3 scripts/java_quality.py apply`，然后 `python3 scripts/java_quality.py check`，审查格式化差异。
- 提交或交付 Java 改动：获取有效基线后运行 `python3 scripts/java_quality.py check --base origin/main` 和 `mvn test`，覆盖分支已提交的改动。
- 修改格式配置、工具版本或范围脚本：运行 `python3 -B -m unittest discover -s scripts -p 'test_java_quality.py' -v` 及全量审计。
- 全量构建检查：`mvn -Pquality verify`。普通 `mvn test` 不检查格式；CI 尚未接入，历史违规需如实报告。
- 交付时报告实际检查结果和未验证部分；离线测试不能当作真实接口验证，不禁用检查来伪造通过。

"""Opt-in real API check for shared context across modes, using each protocol.

Run `mvn package` first and provide DEEPSEEK_API_KEY through the environment.
Captures stay in ignored target/session-mode-switch; user settings are untouched.
"""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time

from execution_matrix import call, wait_ready

MODULE = Path(__file__).resolve().parents[1]
OUTPUT = MODULE / "target/session-mode-switch"
PROTOCOLS = ("CHAT_COMPLETIONS", "RESPONSES", "ANTHROPIC")
QUERIES = (
    ("REACT", "请记住我的练习代号是北斗17，只回复已记住，不要调用工具。"),
    ("REWOO", "将我的练习代号改为青岚29，只回复已更新，不要调用工具。"),
    ("PLAN_EXECUTE", "我的练习代号是什么？根据当前对话只输出当前代号，不要调用工具。"),
    ("REACT", "再说一次我最新的练习代号，只输出当前代号，不要调用工具。"),
)


def run_protocol(protocol, port, directory):
    (directory / "config").mkdir(parents=True)
    (directory / "config/settings.json").write_text(json.dumps({"protocol": protocol}))
    jar = MODULE / "target/deepseek-agent-loop-1.0-SNAPSHOT.jar"
    log_path = OUTPUT / f"{protocol}.log"
    rows = []
    with log_path.open("w") as log:
        process = subprocess.Popen(
            ["java", "-Xmx256m", "-jar", str(jar)], cwd=directory,
            env=dict(os.environ, AGENT_PORT=str(port), AGENT_TRACING_ENABLED="false"),
            stdout=log, stderr=subprocess.STDOUT,
        )
        try:
            wait_ready(process, port)
            session = json.loads(call(port, "/api/session"))["sessionId"]
            for index, (mode, question) in enumerate(QUERIES):
                started = time.monotonic()
                body = {"prompt": question, "mode": mode, "model": "deepseek-flash", "reasoningLevel": "off"}
                events = [json.loads(line) for line in call(port, "/api/chat", body, session).splitlines() if line]
                results = [event["data"] for event in events if event["type"] == "run_result"]
                result = results[-1] if results else None
                accepted = [event["data"] for event in events if event["type"] == "run_started"]
                status = json.loads(call(port, "/api/session/status", session=session))
                answer = result["answer"] if result else ""
                passed = bool(result and accepted) and result["status"] == "COMPLETE"
                passed = passed and accepted[-1]["mode"] == mode and not status["running"]
                passed = passed and result["counters"]["toolAttempts"] == 0
                if index >= 2:
                    passed = passed and "青岚29" in answer and "北斗17" not in answer
                row = {"protocol": protocol, "mode": mode, "turn": index + 1, "passed": passed,
                       "result": result, "durationMs": round((time.monotonic() - started) * 1000)}
                rows.append(row)
                (OUTPUT / f"{protocol}-{index + 1}.json").write_text(
                    json.dumps({"events": events, "status": status, "summary": row}, ensure_ascii=False, indent=2))
                print(json.dumps({k: v for k, v in row.items() if k != "result"}), flush=True)
                if not passed:
                    break
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
    return rows


def main():
    if not os.environ.get("DEEPSEEK_API_KEY"):
        raise SystemExit("DEEPSEEK_API_KEY is required.")
    if not (MODULE / "target/deepseek-agent-loop-1.0-SNAPSHOT.jar").is_file():
        raise SystemExit("Run mvn package first.")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    rows = []
    with tempfile.TemporaryDirectory(prefix="agent-mode-switch-") as temporary:
        for index, protocol in enumerate(PROTOCOLS):
            rows.extend(run_protocol(protocol, 18991 + index, Path(temporary) / protocol))
    (OUTPUT / "summary.json").write_text(json.dumps(rows, ensure_ascii=False, indent=2))
    passed = sum(row["passed"] for row in rows)
    expected = len(PROTOCOLS) * len(QUERIES)
    print(f"MODE SWITCH {passed}/{expected}", flush=True)
    return 0 if passed == expected else 1


if __name__ == "__main__":
    raise SystemExit(main())

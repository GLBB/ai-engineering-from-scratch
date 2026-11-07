"""Opt-in real runs plus a bounded Langfuse CLI read-back of their new traces.

Run mvn package first. Keys stay in the private --env file and process environment.
Captures go to ignored target/trace-verification; no Bash commands are requested.
"""
import argparse
from datetime import datetime, timezone, timedelta
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
from execution_matrix import call, wait_ready

MODULE = Path(__file__).resolve().parents[1]
OUTPUT = MODULE / "target/trace-verification"
PROTOCOLS = ("CHAT_COMPLETIONS", "RESPONSES", "ANTHROPIC")
TASKS = {
    "calculator": "请本轮重新使用 calculator 计算 (120+18)/3，明确给出最终数值。不要使用其他工具。",
    "weather": "请本轮重新调用 weather_forecast 查询北京市未来七天天气，city=北京、country_code=CN、admin1=北京市、days=7，概括结果。不要使用其他工具。",
}


def load_environment(path):
    values = {}
    for line in path.read_text().splitlines():
        if line.strip() and not line.startswith("#"):
            key, value = line.split("=", 1)
            values[key] = value.strip()
    return values


def fetch_trace(env_file, trace_id, start, expected_models):
    command = ["langfuse", "--env", str(env_file), "api", "observations", "list",
               "--trace-id", trace_id, "--from-start-time", start,
               "--to-start-time", (datetime.now(timezone.utc) + timedelta(minutes=1)).isoformat(),
               "--fields", "core,basic,time,model,usage,metrics,metadata,trace_context,io",
               "--expand-metadata", "attributes", "--limit", "200", "--json"]
    for _ in range(20):
        response = subprocess.run(command, text=True, capture_output=True, timeout=20, check=True)
        records = json.loads(response.stdout)["body"]["data"]
        roots = [row for row in records if row["type"] == "AGENT" and not row.get("parentObservationId")]
        generations = [row for row in records if row["type"] == "GENERATION"]
        if len(roots) == 1 and len(generations) == expected_models:
            return records
        time.sleep(1)
    raise RuntimeError("New trace was not fully visible in the bounded read-back window")


def audit(records, result):
    by_id = {row["id"]: row for row in records}
    roots = [row for row in records if not row.get("parentObservationId")]
    generations = [row for row in records if row["type"] == "GENERATION"]
    tools = [row for row in records if row["type"] == "TOOL"]
    assert len(by_id) == len(records), "Duplicate observation IDs"
    assert len(roots) == 1 and roots[0]["type"] == "AGENT", "Expected one agent root"
    assert len(generations) == result["counters"]["modelCalls"], "Model counts disagree"
    assert len(tools) == result["counters"]["toolAttempts"], "Tool attempt counts disagree"
    assert all(row.get("sessionId") == roots[0]["sessionId"] for row in records), "Session context not propagated"
    for row in records:
        parent = row.get("parentObservationId")
        assert not parent or parent in by_id, "Missing parent"
        if row["type"] == "TOOL":
            assert by_id[parent]["type"] != "GENERATION", "Tool incorrectly nested under generation"
    assert all(row.get("model") == "deepseek-flash" for row in generations), "Missing model name"
    assert all(row.get("usageDetails") for row in generations), "Missing actual model usage"
    for row in generations:
        raw = row.get("metadata", {}).get("attributes.agent_loop.model.raw_usage")
        if isinstance(raw, str):
            raw = json.loads(raw)
        if not isinstance(raw, dict) or raw.get("inputTokens") is None or raw.get("outputTokens") is None:
            continue
        total_input = raw["inputTokens"]
        if raw["inputAccounting"] == "EXCLUDES_CACHE":
            total_input += raw["cachedInputTokens"] + raw["cacheWriteInputTokens"]
        details = row["usageDetails"]
        assert details["total"] == total_input + raw["outputTokens"], "Token normalization mismatch"
        assert sum(v for k, v in details.items() if k != "total") == details["total"], "Token subsets double-counted"
    return {"spanCount": len(records), "generations": len(generations), "tools": len(tools),
            "httpRequests": sum(row["name"] in ("GET", "POST") for row in records),
            "sessionId": roots[0]["sessionId"], "root": roots[0]["name"]}


def run_protocol(protocol, port, directory, env_file):
    private = load_environment(env_file)
    (directory / "config").mkdir(parents=True)
    (directory / "config/settings.json").write_text(json.dumps({"protocol": protocol}))
    env = dict(os.environ, **private)
    env.update(AGENT_PORT=str(port), AGENT_TRACING_ENABLED="true", AGENT_TRACE_SAMPLE_RATIO="1.0")
    jar = MODULE / "target/deepseek-agent-loop-1.0-SNAPSHOT.jar"
    rows = []
    with (OUTPUT / f"{protocol}.log").open("w") as log:
        process = subprocess.Popen(["java", "-Xmx256m", "-jar", str(jar)], cwd=directory,
                                   env=env, stdout=log, stderr=subprocess.STDOUT)
        try:
            wait_ready(process, port)
            session = json.loads(call(port, "/api/session"))["sessionId"]
            observation_session = None
            for task, question in TASKS.items():
                for mode in ("REACT", "REWOO", "PLAN_EXECUTE"):
                    start = datetime.now(timezone.utc).isoformat()
                    body = {"prompt": question, "mode": mode, "model": "deepseek-flash", "reasoningLevel": "off"}
                    events = [json.loads(line) for line in call(port, "/api/chat", body, session).splitlines() if line]
                    started = next(event["data"] for event in events if event["type"] == "run_started")
                    result = next(event["data"] for event in events if event["type"] == "run_result")
                    trace_id = started["traceId"]
                    records = fetch_trace(env_file, trace_id, start, result["counters"]["modelCalls"])
                    report = audit(records, result)
                    if observation_session is None:
                        observation_session = report["sessionId"]
                    assert observation_session == report["sessionId"], "Cross-mode traces lost session grouping"
                    assert observation_session != session, "Session access credential leaked"
                    evidence = [event["data"] for event in events if event["type"] == "evidence"]
                    successful = [item for item in evidence if item["result"]["status"] == "SUCCESS"]
                    passed = result["status"] == "COMPLETE" and bool(successful)
                    passed = passed and ("46" in result["answer"] if task == "calculator" else any(item["tool"] == "weather_forecast" for item in successful))
                    row = {"protocol": protocol, "mode": mode, "task": task, "traceId": trace_id,
                           "traceUrl": started.get("traceUrl"), "passed": passed, "status": result["status"], **report}
                    stem = f"{protocol}-{mode}-{task}"
                    (OUTPUT / (stem + ".json")).write_text(json.dumps({"summary": row, "result": result, "observations": records}, ensure_ascii=False, indent=2))
                    rows.append(row)
                    print(json.dumps(row, ensure_ascii=False), flush=True)
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
    return rows


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--env", type=Path, default=Path.home() / ".config/langfuse/agent-loop.env")
    parser.add_argument("--protocol", choices=PROTOCOLS, action="append")
    args = parser.parse_args()
    if not os.environ.get("DEEPSEEK_API_KEY"):
        raise SystemExit("DEEPSEEK_API_KEY is required.")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    rows = []
    with tempfile.TemporaryDirectory(prefix="agent-trace-verification-") as temporary:
        for index, protocol in enumerate(args.protocol or PROTOCOLS):
            rows.extend(run_protocol(protocol, 19011 + index, Path(temporary) / protocol, args.env))
    (OUTPUT / "summary.json").write_text(json.dumps(rows, ensure_ascii=False, indent=2))
    passed = sum(row["passed"] for row in rows)
    print(f"TRACE READ-BACK {passed}/{len(rows)}", flush=True)
    return 0 if passed == len(rows) else 1


if __name__ == "__main__":
    raise SystemExit(main())

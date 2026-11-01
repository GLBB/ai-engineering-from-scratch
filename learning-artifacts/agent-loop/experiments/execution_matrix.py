"""Opt-in real API matrix; run `mvn package` first and provide environment keys.

Uses temporary protocol settings and ports 18981-18983, leaving user settings intact.
Generated captures and original usage stay in ignored target/execution-matrix/.
"""
import concurrent.futures
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import urllib.request

MODULE = Path(__file__).resolve().parents[1]
OUTPUT = MODULE / "target/execution-matrix"
PROTOCOLS = ("CHAT_COMPLETIONS", "RESPONSES", "ANTHROPIC")
MODES = ("REACT", "REWOO", "PLAN_EXECUTE")
QUESTION = "请分两步使用 calculator 计算 (120+18)/3：先求和，再把结果除以3。最终明确给出和与商。"


def call(port, path, body=None, session=None):
    headers = {"Origin": f"http://127.0.0.1:{port}"}
    if session:
        headers["X-Agent-Session"] = session
    if body is not None:
        headers["Content-Type"] = "application/json"
    request = urllib.request.Request(
        f"http://127.0.0.1:{port}{path}",
        data=None if body is None else json.dumps(body).encode(),
        headers=headers,
    )
    with urllib.request.urlopen(request, timeout=240) as response:
        return response.read().decode()


def run_case(case):
    protocol, port, mode, effort = case
    started = time.monotonic()
    result = {"protocol": protocol, "mode": mode, "effort": effort}
    try:
        session = json.loads(call(port, "/api/session"))["sessionId"]
        body = {"prompt": QUESTION, "mode": mode, "model": "deepseek-flash", "reasoningLevel": effort}
        events = [json.loads(line) for line in call(port, "/api/chat", body, session).splitlines() if line]
        result["events"] = events
        runs = [event["data"] for event in events if event["type"] == "run_result"]
        result["result"] = runs[-1] if runs else None
        result["errors"] = [event["data"] for event in events if event["type"] == "error"]
        answer = runs[-1]["answer"] if runs else ""
        result["qualityPass"] = bool(runs) and runs[-1]["status"] == "COMPLETE" and "138" in answer and "46" in answer
        result["usage"] = [event["data"].get("usage") for event in events
                           if event["type"] == "trace" and event["data"].get("kind") == "model"
                           and event["data"].get("status") == "complete"]
        result["sessionStatus"] = json.loads(call(port, "/api/session/status", session=session))
    except Exception as error:
        result["error"] = str(error)
        result["qualityPass"] = False
    result["durationMs"] = round((time.monotonic() - started) * 1000)
    (OUTPUT / f"{protocol}-{mode}-{effort}.json").write_text(json.dumps(result, ensure_ascii=False, indent=2))
    displayed = {key: value for key, value in result.items()
                 if key in ("protocol", "mode", "effort", "qualityPass", "error", "errors", "durationMs")}
    print(json.dumps(displayed), flush=True)
    return result


def wait_ready(process, port):
    for _ in range(120):
        if process.poll() is not None:
            raise RuntimeError("Matrix server exited; see target/execution-matrix/*.log")
        try:
            call(port, "/api/settings")
            return
        except OSError:
            time.sleep(0.25)
    raise RuntimeError("Matrix server did not start")


def main():
    jar = MODULE / "target/deepseek-agent-loop-1.0-SNAPSHOT.jar"
    if not jar.is_file():
        raise SystemExit("Run mvn package first.")
    if not os.environ.get("DEEPSEEK_API_KEY"):
        raise SystemExit("DEEPSEEK_API_KEY is required.")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    servers = []
    cases = []
    try:
        with tempfile.TemporaryDirectory(prefix="agent-matrix-") as temporary:
            for index, protocol in enumerate(PROTOCOLS):
                port = 18981 + index
                directory = Path(temporary) / protocol
                (directory / "config").mkdir(parents=True)
                (directory / "config/settings.json").write_text(json.dumps({"protocol": protocol}))
                log = (OUTPUT / f"{protocol}.log").open("w")
                process = subprocess.Popen(["java", "-Xmx256m", "-jar", str(jar)], cwd=directory,
                                           env=dict(os.environ, AGENT_PORT=str(port)),
                                           stdout=log, stderr=subprocess.STDOUT)
                servers.append((process, log))
                wait_ready(process, port)
                cases.extend((protocol, port, mode, effort) for mode in MODES for effort in ("off", "low"))
            with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
                results = list(pool.map(run_case, cases))
            summary = [{key: value for key, value in row.items() if key != "events"} for row in results]
            (OUTPUT / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2))
            passed = sum(result["qualityPass"] for result in results)
            print(f"MATRIX {passed}/{len(results)}", flush=True)
            return 0 if passed == len(results) else 1
    finally:
        for process, log in servers:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
            log.close()


if __name__ == "__main__":
    raise SystemExit(main())

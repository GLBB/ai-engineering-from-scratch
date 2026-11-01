"""Opt-in real weather + DeepSeek matrix. Run mvn package first.

Uses temporary protocol settings and ports 19081-19083. No Bash or search requested.
Captures contain model output and original observations under ignored target/weather-matrix/.
"""
import argparse
import concurrent.futures
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
from execution_matrix import call, wait_ready

MODULE = Path(__file__).resolve().parents[1]
OUTPUT = MODULE / "target/weather-matrix"
PROTOCOLS = ("CHAT_COMPLETIONS", "RESPONSES", "ANTHROPIC")
MODES = ("REACT", "REWOO", "PLAN_EXECUTE")


def run_case(case):
    protocol, port, mode, kind = case
    started = time.monotonic()
    result = {"protocol": protocol, "mode": mode, "kind": kind}
    question = "仅用天气工具查询北京市（city=北京、country_code=CN、admin1=北京市）的"
    question += "当前天气，说明数据时间、气温、体感、湿度、风速。" if kind == "current" else "未来七天逐日天气，列出具体日期、最低/最高温度和降水概率。"
    try:
        session = json.loads(call(port, "/api/session"))["sessionId"]
        body = {"prompt": question, "mode": mode, "model": "deepseek-flash", "reasoningLevel": "off"}
        events = [json.loads(line) for line in call(port, "/api/chat", body, session).splitlines() if line]
        result["events"] = events
        runs = [event["data"] for event in events if event["type"] == "run_result"]
        result["result"] = runs[-1] if runs else None
        evidence = [event["data"] for event in events if event["type"] == "evidence"]
        result["tools"] = [{"tool": item["tool"], "status": item["result"]["status"], "code": item["result"]["errorCode"]} for item in evidence]
        name = "current_weather" if kind == "current" else "weather_forecast"
        successful = [item for item in evidence if item["tool"] == name and item["result"]["status"] == "SUCCESS"]
        result["pass"] = bool(runs and runs[-1]["status"] == "COMPLETE" and successful
                              and all(len(item["result"]["sources"]) == 2 for item in successful))
        if successful:
            data = json.loads(successful[-1]["result"]["content"])
            result["localDate"] = data["localDate"]
            result["httpRequests"] = data["httpRequests"]
            if kind == "forecast":
                result["pass"] = result["pass"] and len(data["forecast"]["days"]) == 7
    except Exception as error:
        result["error"] = str(error)
        result["pass"] = False
    result["durationMs"] = round((time.monotonic() - started) * 1000)
    (OUTPUT / f"{protocol}-{mode}-{kind}.json").write_text(json.dumps(result, ensure_ascii=False, indent=2))
    print(json.dumps({key: value for key, value in result.items() if key not in ("events", "result")}, ensure_ascii=False), flush=True)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--protocol", choices=PROTOCOLS)
    parser.add_argument("--mode", choices=MODES)
    args = parser.parse_args()
    jar = MODULE / "target/deepseek-agent-loop-1.0-SNAPSHOT.jar"
    if not jar.is_file() or not os.environ.get("DEEPSEEK_API_KEY"):
        raise SystemExit("Run mvn package and provide DEEPSEEK_API_KEY first.")
    OUTPUT.mkdir(parents=True, exist_ok=True)
    servers = []
    cases = []
    try:
        with tempfile.TemporaryDirectory(prefix="weather-live-") as temporary:
            for index, protocol in enumerate(PROTOCOLS):
                if args.protocol and args.protocol != protocol:
                    continue
                port = 19081 + index
                directory = Path(temporary) / protocol
                (directory / "config").mkdir(parents=True)
                (directory / "config/settings.json").write_text(json.dumps({"protocol": protocol}))
                log = (OUTPUT / f"{protocol}.log").open("w")
                environment = dict(os.environ, AGENT_PORT=str(port))
                environment.pop("DEBUG", None)
                process = subprocess.Popen(["java", "-Xmx256m", "-jar", str(jar)], cwd=directory,
                                           env=environment, stdout=log, stderr=subprocess.STDOUT)
                servers.append((process, log))
                wait_ready(process, port)
                cases.extend((protocol, port, mode, kind) for mode in MODES if not args.mode or args.mode == mode
                             for kind in ("current", "forecast"))
            with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
                results = list(pool.map(run_case, cases))
            summary = [{key: value for key, value in row.items() if key != "events"} for row in results]
            (OUTPUT / "summary.json").write_text(json.dumps(summary, ensure_ascii=False, indent=2))
            passed = sum(row["pass"] for row in results)
            print(f"WEATHER MATRIX {passed}/{len(results)}", flush=True)
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

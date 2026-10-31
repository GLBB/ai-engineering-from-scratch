#!/usr/bin/env python3
"""Compare complete, missing, and refused tool results against DeepSeek.

See docs/05-tool-calls-and-transcripts.md for the offline experiment.
Uses only fixed arithmetic inputs and reads the API key from the environment.
Request/response bodies are saved under the ignored target/ directory.
"""

import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


MODEL = "deepseek-flash"
SYSTEM = (
    "You are testing calculator tools. Use a separate calculator call for each "
    "expression and request all three calls in one response. Do not calculate "
    "results yourself. After tools return, report their results or errors."
)
QUESTION = "Use calculator three times in one response: 1+2, 4*5, and 6-1."
ANSWERS = {"1+2": "3", "4*5": "20", "6-1": "5"}
FUNCTION = {
    "name": "calculator",
    "description": "Calculate one arithmetic expression per call.",
    "parameters": {
        "type": "object",
        "properties": {"expression": {"type": "string", "enum": list(ANSWERS)}},
        "required": ["expression"],
        "additionalProperties": False,
    },
}
PATHS = {"chat": "/chat/completions", "responses": "/responses", "anthropic": "/anthropic/v1/messages"}


def seed_request(protocol):
    body = {"model": MODEL, "stream": False}
    if protocol == "chat":
        body.update(messages=[{"role": "system", "content": SYSTEM}, {"role": "user", "content": QUESTION}],
                    tools=[{"type": "function", "function": FUNCTION}], tool_choice="required",
                    thinking={"type": "disabled"}, max_tokens=512)
    elif protocol == "responses":
        body.update(instructions=SYSTEM, input=[{"role": "user", "content": QUESTION}],
                    tools=[{"type": "function", **FUNCTION}], tool_choice="required",
                    reasoning={"effort": "none"}, max_output_tokens=512)
    else:
        body.update(system=SYSTEM, messages=[{"role": "user", "content": QUESTION}],
                    tools=[{"name": FUNCTION["name"], "description": FUNCTION["description"],
                            "input_schema": FUNCTION["parameters"]}],
                    tool_choice={"type": "any"}, thinking={"type": "disabled"}, max_tokens=512)
    return body


def tool_calls(protocol, body):
    if protocol == "chat":
        calls = body["choices"][0]["message"].get("tool_calls", [])
        return [(call["id"], call["function"]["name"], json.loads(call["function"]["arguments"])) for call in calls]
    if protocol == "responses":
        return [(item["call_id"], item["name"], json.loads(item["arguments"]))
                for item in body.get("output", []) if item["type"] == "function_call"]
    return [(block["id"], block["name"], block["input"])
            for block in body.get("content", []) if block["type"] == "tool_use"]


def continuation(protocol, seed, response, calls, scenario):
    body = dict(seed)
    body["tool_choice"] = {"type": "auto"} if protocol == "anthropic" else "auto"
    results = []
    executed = []
    for index, (call_id, _, arguments) in enumerate(calls):
        if index == 2 and scenario == "missing":
            continue
        refused = index == 2 and scenario == "skipped"
        if refused:
            output = "error: not executed; max_tool_calls_per_turn=2"
        else:
            output = ANSWERS[arguments["expression"]]
            executed.append(call_id)
        if protocol == "chat":
            result = {"role": "tool", "tool_call_id": call_id, "content": output}
        elif protocol == "responses":
            result = {"type": "function_call_output", "call_id": call_id, "output": output}
        else:
            result = {"type": "tool_result", "tool_use_id": call_id, "content": output}
            if refused:
                result["is_error"] = True
        results.append(result)
    if protocol == "chat":
        message = response["choices"][0]["message"]
        body["messages"] = seed["messages"] + [message] + results
    elif protocol == "responses":
        body["input"] = seed["input"] + response["output"] + results
    else:
        body["messages"] = seed["messages"] + [
            {"role": "assistant", "content": response["content"]}, {"role": "user", "content": results}]
    return body, executed


def response_summary(protocol, response):
    if protocol == "chat":
        choice = response["choices"][0]
        return {"stop": choice["finish_reason"], "text": choice["message"].get("content"),
                "new_tool_calls": choice["message"].get("tool_calls", [])}
    if protocol == "responses":
        text = [part.get("text", "") for item in response.get("output", [])
                if item["type"] == "message" for part in item.get("content", [])]
        return {"stop": response.get("status"), "text": "".join(text),
                "new_tool_calls": [item for item in response.get("output", []) if item["type"] == "function_call"]}
    return {"stop": response.get("stop_reason"),
            "text": "".join(block.get("text", "") for block in response.get("content", []) if block["type"] == "text"),
            "new_tool_calls": [block for block in response.get("content", []) if block["type"] == "tool_use"]}


def send(protocol, label, body, base_url, key, directory):
    def save(suffix, value):
        text = json.dumps(value, ensure_ascii=False, indent=2).replace(key, "[REDACTED]")
        (directory / f"{protocol}-{label}-{suffix}.json").write_text(text + "\n", encoding="utf-8")

    save("request", body)
    request = Request(base_url + PATHS[protocol], data=json.dumps(body).encode(), method="POST",
                      headers={"Authorization": "Bearer " + key, "Content-Type": "application/json",
                               "anthropic-version": "2023-06-01"})
    started = time.monotonic()
    try:
        with urlopen(request, timeout=45) as response:
            status = response.status
            raw = response.read(1_048_576).decode()
    except HTTPError as error:
        status = error.code
        raw = error.read(1_048_576).decode()
    except (URLError, TimeoutError) as error:
        result = {"http_status": None, "network_error": str(error).replace(key, "[REDACTED]")}
        save("response", result)
        return result, None
    try:
        parsed = json.loads(raw)
    except json.JSONDecodeError:
        parsed = {"raw": raw}
    save("response", parsed)
    result = {"http_status": status, "elapsed_seconds": round(time.monotonic() - started, 3)}
    if status != 200:
        result["error"] = parsed
    return result, parsed


def probe(protocol, base_url, key, directory):
    seed = seed_request(protocol)
    records = []
    calls = []
    response = None
    for attempt in range(1, 4):
        result, response = send(protocol, f"seed-{attempt}", seed, base_url, key, directory)
        records.append({"scenario": f"seed-{attempt}", **result})
        if result["http_status"] != 200:
            return {"protocol": protocol, "records": records}
        calls = tool_calls(protocol, response)
        if (len(calls) == 3 and len({call[0] for call in calls}) == 3
                and all(call[1] == "calculator" for call in calls)
                and {call[2].get("expression") for call in calls} == set(ANSWERS)):
            break
    else:
        return {"protocol": protocol, "records": records, "inconclusive": "model did not return the three required calls"}
    print(f"{protocol}: received three real calls", flush=True)
    for scenario in ["full", "missing", "skipped"]:
        body, executed = continuation(protocol, seed, response, calls, scenario)
        result, answer = send(protocol, scenario, body, base_url, key, directory)
        record = {"scenario": scenario, "executed_ids": executed, **result}
        if result["http_status"] == 200:
            record.update(response_summary(protocol, answer))
        records.append(record)
        print(json.dumps({"protocol": protocol, **record}, ensure_ascii=False).replace(key, "[REDACTED]"), flush=True)
    return {"protocol": protocol, "calls": calls, "records": records}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--protocol", choices=["all", *PATHS], default="all")
    args = parser.parse_args()
    key = os.environ.get("DEEPSEEK_API_KEY", "")
    if not key:
        raise SystemExit("DEEPSEEK_API_KEY is required")
    base_url = os.environ.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com").rstrip("/")
    parsed = urlsplit(base_url)
    if parsed.scheme != "https" or parsed.hostname != "api.deepseek.com" or parsed.username or parsed.password:
        raise SystemExit("This live probe requires the official https://api.deepseek.com endpoint")
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%S%fZ")
    directory = Path(__file__).resolve().parents[1] / "target/deepseek-tool-limit-live" / stamp
    directory.mkdir(parents=True)
    print(f"Evidence: {directory}", flush=True)
    protocols = list(PATHS) if args.protocol == "all" else [args.protocol]
    with ThreadPoolExecutor(max_workers=len(protocols)) as executor:
        futures = [executor.submit(probe, protocol, base_url, key, directory) for protocol in protocols]
        results = [future.result() for future in futures]
    summary = {"started_at_utc": stamp, "model": MODEL, "thinking": "disabled", "stream": False,
               "base_url": base_url, "protocols": results}
    text = json.dumps(summary, ensure_ascii=False, indent=2).replace(key, "[REDACTED]")
    (directory / "summary.json").write_text(text + "\n", encoding="utf-8")
    print(f"Summary: {directory / 'summary.json'}", flush=True)


if __name__ == "__main__":
    main()

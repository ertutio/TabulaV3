#!/usr/bin/env python3
"""在合成样图上跑一次真实的 DeepSeek 文字辅助试点，并记录用量。

为什么单独写一个脚本：
- 请求体**不是这里拼的**——脚本读 `app/build/intent-pilot/payloads.json`，
  那是 Kotlin 实现的 `DeepSeekTextAssist.buildPayload` 真实产出的请求体
  （由 `IntentAssistPayloadExportTest` 导出）。这样量出来的 token 用量与行为
  对得上线上，不会出现「Python 拼串跑得通、App 里不是这个请求」的假验证。
- 脚本本身只做三件事：逐条 POST、记录每条结果、汇总请求次数与 token 用量。
- Key 只从环境变量 `DEEPSEEK_API_KEY` 读；不写文件、不打印。

用法：
    export DEEPSEEK_API_KEY=sk-...
    python tools/intent-samples/run_deepseek_pilot.py \
        --payloads app/build/intent-pilot/payloads.json \
        --out app/build/intent-pilot/deepseek-pilot.json

未设置 Key 时脚本以 0 退出并打印「skipped」，方便在没有 Key 的环境里直接跳过。
"""
import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.request

ENDPOINT = "https://api.deepseek.com/chat/completions"


def post(payload_json: str, api_key: str, timeout: int):
    data = payload_json.encode("utf-8")
    request = urllib.request.Request(
        ENDPOINT,
        data=data,
        method="POST",
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {api_key}",
        },
    )
    started = time.monotonic()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = response.read().decode("utf-8", errors="replace")
            return {
                "ok": True,
                "status": response.status,
                "elapsed_ms": int((time.monotonic() - started) * 1000),
                "body": body,
            }
    except urllib.error.HTTPError as error:
        return {
            "ok": False,
            "status": error.code,
            "elapsed_ms": int((time.monotonic() - started) * 1000),
            "body": error.read().decode("utf-8", errors="replace")[:400],
        }
    except Exception as error:  # noqa: BLE001 - 试点脚本要记录任何失败，不要抛出
        return {
            "ok": False,
            "status": 0,
            "elapsed_ms": int((time.monotonic() - started) * 1000),
            "body": f"{type(error).__name__}: {error}",
        }


def extract_answer(body: str) -> str:
    """从 OpenAI 兼容响应里取 choices[0].message.content。"""
    try:
        parsed = json.loads(body)
        return str(parsed["choices"][0]["message"]["content"]).strip()
    except Exception:  # noqa: BLE001
        return ""


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--payloads", default="app/build/intent-pilot/payloads.json")
    parser.add_argument("--out", default="app/build/intent-pilot/deepseek-pilot.json")
    parser.add_argument("--timeout", type=int, default=30)
    parser.add_argument("--sleep", type=float, default=0.4, help="请求间隔（秒），避免触发限流")
    args = parser.parse_args()

    api_key = os.environ.get("DEEPSEEK_API_KEY", "").strip()
    if not api_key:
        print("[deepseek-pilot] skipped: DEEPSEEK_API_KEY 未设置")
        return 0

    with open(args.payloads, encoding="utf-8") as handle:
        exported = json.load(handle)

    results = []
    prompt_tokens = completion_tokens = total_tokens = 0
    ok_count = failed_count = 0
    started = time.monotonic()

    for item in exported["payloads"]:
        response = post(item["payload"], api_key, args.timeout)
        answer = extract_answer(response["body"]) if response["ok"] else ""
        usage = {}
        if response["ok"]:
            try:
                usage = json.loads(response["body"]).get("usage", {}) or {}
            except Exception:  # noqa: BLE001
                usage = {}
            ok_count += 1
        else:
            failed_count += 1
        prompt_tokens += int(usage.get("prompt_tokens", 0) or 0)
        completion_tokens += int(usage.get("completion_tokens", 0) or 0)
        total_tokens += int(usage.get("total_tokens", 0) or 0)
        results.append(
            {
                "file": item["file"],
                "category": item["category"],
                "ok": response["ok"],
                "status": response["status"],
                "answer": answer,
                "elapsed_ms": response["elapsed_ms"],
                "usage": usage,
                "sanitizedLines": item.get("sanitizedLines", []),
            }
        )
        time.sleep(args.sleep)

    elapsed = int((time.monotonic() - started) * 1000)
    summary = {
        "requests": len(results),
        "ok": ok_count,
        "failed": failed_count,
        "prompt_tokens": prompt_tokens,
        "completion_tokens": completion_tokens,
        "total_tokens": total_tokens,
        "elapsed_ms": elapsed,
        "endpoint": ENDPOINT,
        "model": "deepseek-chat",
    }

    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    with open(args.out, "w", encoding="utf-8") as handle:
        json.dump({"summary": summary, "results": results}, handle, ensure_ascii=False, indent=2)

    print(json.dumps(summary, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())

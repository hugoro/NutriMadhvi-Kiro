#!/usr/bin/env python3
from __future__ import annotations
"""
Compare NVIDIA Llama 3.2 11B Vision vs Gemini Flash for food macro estimation.

Usage:
  python3 scripts/test_nvidia_vs_gemini.py <image_path> [<image_path2> ...]

Reads API keys from Keychain:
  security find-generic-password -s nutrimadhvi/GEMINI_API_KEY -w
  security find-generic-password -s nutrimadhvi/NVIDIA_API_KEY -w

Outputs a side-by-side comparison table + JSON files in /tmp/nvidia_vs_gemini/
"""

import sys, os, base64, json, time, subprocess, urllib.request, urllib.error
from pathlib import Path

# ── API keys from Keychain ────────────────────────────────────────────────────

def keychain_get(service: str, account: str) -> str:
    r = subprocess.run(
        ["security", "find-generic-password", "-s", service, "-a", account, "-w"],
        capture_output=True, text=True
    )
    key = r.stdout.strip()
    if not key:
        sys.exit(f"Key not found in Keychain: service={service} account={account}")
    return key

GEMINI_KEY = keychain_get("nutrimadhvi", "GEMINI_API_KEY")
NVIDIA_KEY = keychain_get("nutrimadhvi", "NVIDIA_API_KEY")

# ── Shared prompt ─────────────────────────────────────────────────────────────

SYSTEM_PROMPT = """You are a precise nutritionist AI. Analyse the food photo and return ONLY valid JSON, no prose.
Required fields: name, calories (kcal int), protein (g float), carbs (g float), fat (g float),
freeSugarsG (g float), saturatedFatG (g float), confidence ("low"|"medium"|"high"),
analysisSummary (1 sentence).
If you cannot estimate, still return JSON with nulls and confidence "low"."""

USER_PROMPT = "Analyse this meal photo and return the JSON macro estimate."

# ── Gemini Flash (streaming disabled for benchmark clarity) ───────────────────

GEMINI_MODEL = "gemini-3.1-flash-lite"
GEMINI_MODEL_PRO = "gemini-3.1-pro-preview"
GEMINI_URL = (
    f"https://generativelanguage.googleapis.com/v1beta/models/"
    f"{GEMINI_MODEL}:generateContent?key={GEMINI_KEY}"
)
GEMINI_PRO_URL = (
    f"https://generativelanguage.googleapis.com/v1beta/models/"
    f"{GEMINI_MODEL_PRO}:generateContent?key={GEMINI_KEY}"
)

def _call_gemini(url: str, image_b64: str, mime: str) -> tuple[dict | None, float, str]:
    payload = {
        "contents": [{
            "role": "user",
            "parts": [
                {"text": USER_PROMPT},
                {"inlineData": {"mimeType": mime, "data": image_b64}}
            ]
        }],
        "systemInstruction": {"parts": [{"text": SYSTEM_PROMPT}]},
        "generationConfig": {
            "temperature": 0.2,
            "responseMimeType": "application/json",
            "thinkingConfig": {"thinkingBudget": 0}
        }
    }
    body = json.dumps(payload).encode()
    req = urllib.request.Request(
        url, data=body,
        headers={"Content-Type": "application/json"},
        method="POST"
    )
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            latency = time.time() - t0
            data = json.loads(resp.read())
            text = data["candidates"][0]["content"]["parts"][0]["text"]
            parsed = json.loads(text)
            if isinstance(parsed, list) and parsed:
                parsed = parsed[0]
            return parsed, latency, ""
    except urllib.error.HTTPError as e:
        return None, time.time() - t0, f"HTTP {e.code}: {e.read().decode()[:200]}"
    except Exception as ex:
        return None, time.time() - t0, str(ex)

def call_gemini(image_b64: str, mime: str) -> tuple[dict | None, float, str]:
    return _call_gemini(GEMINI_URL, image_b64, mime)

def call_gemini_pro(image_b64: str, mime: str) -> tuple[dict | None, float, str]:
    return _call_gemini(GEMINI_PRO_URL, image_b64, mime)

# ── NVIDIA Llama 3.2 11B Vision ───────────────────────────────────────────────

NVIDIA_MODEL = "meta/llama-3.2-11b-vision-instruct"
NVIDIA_MODEL_2 = "meta/llama-3.2-90b-vision-instruct"
PALIGEMMA_URL = "https://ai.api.nvidia.com/v1/vlm/google/paligemma"
NVIDIA_URL = "https://integrate.api.nvidia.com/v1/chat/completions"

# Concise prompt that llama-vision follows reliably for JSON output
NVIDIA_USER_PROMPT = (
    "Analyse this meal photo. Return ONLY a JSON object (no prose, no fences) with these keys: "
    "name (string), calories (int kcal), protein (float g), carbs (float g), fat (float g), "
    "freeSugarsG (float g of added/free sugars), saturatedFatG (float g), "
    "confidence (\"low\"|\"medium\"|\"high\"), analysisSummary (1 sentence)."
)

def _call_nvidia_model(model: str, payload: dict) -> tuple[dict | None, float, str]:
    body = json.dumps(payload).encode()
    req = urllib.request.Request(
        NVIDIA_URL,
        data=body,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {NVIDIA_KEY}"
        },
        method="POST"
    )
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=90) as resp:
            latency = time.time() - t0
            data = json.loads(resp.read())
            text = data["choices"][0]["message"]["content"]
            text = text.strip()
            if text.startswith("```"):
                parts = text.split("```")
                text = parts[1]
                if text.startswith("json"):
                    text = text[4:]
            start = text.find("{")
            end = text.rfind("}") + 1
            if start >= 0 and end > start:
                text = text[start:end]
            parsed = json.loads(text.strip())
            if isinstance(parsed, list) and parsed:
                parsed = parsed[0]
            return parsed, latency, ""
    except urllib.error.HTTPError as e:
        return None, time.time() - t0, f"HTTP {e.code}: {e.read().decode()[:300]}"
    except Exception as ex:
        return None, time.time() - t0, str(ex)


def call_nvidia(image_b64: str, mime: str) -> tuple[dict | None, float, str]:
    data_uri = f"data:{mime};base64,{image_b64}"
    payload = {
        "model": NVIDIA_MODEL,
        "messages": [
            {"role": "system", "content": "You are a nutrition analysis AI. Always respond with valid JSON only."},
            {"role": "user", "content": [
                {"type": "text", "text": NVIDIA_USER_PROMPT},
                {"type": "image_url", "image_url": {"url": data_uri}}
            ]}
        ],
        "max_tokens": 512,
        "temperature": 0.2,
        "stream": False
    }
    return _call_nvidia_model(NVIDIA_MODEL, payload)


def call_llama90b(image_b64: str, mime: str) -> tuple[dict | None, float, str]:
    """meta/llama-3.2-90b-vision-instruct — same image_url format as 11B."""
    data_uri = f"data:{mime};base64,{image_b64}"
    payload = {
        "model": NVIDIA_MODEL_2,
        "messages": [
            {"role": "system", "content": "You are a nutrition analysis AI. Always respond with valid JSON only."},
            {"role": "user", "content": [
                {"type": "text", "text": NVIDIA_USER_PROMPT},
                {"type": "image_url", "image_url": {"url": data_uri}}
            ]}
        ],
        "max_tokens": 512,
        "temperature": 0.2,
        "top_p": 0.95,
        "stream": False
    }
    return _call_nvidia_model(NVIDIA_MODEL_2, payload)


def call_paligemma(image_b64: str, mime: str) -> tuple[dict | None, float, str]:
    """google/paligemma — different REST endpoint, image_b64 directly in payload."""
    prompt = (
        "Analyse this meal. Return ONLY a JSON object with: "
        "name, calories (int kcal), protein (float g), carbs (float g), fat (float g), "
        "freeSugarsG (float g), saturatedFatG (float g), confidence (low/medium/high), "
        "analysisSummary (1 sentence)."
    )
    payload = {
        "image_b64": image_b64,
        "query": prompt,
        "max_tokens": 512,
        "stream": False
    }
    body = json.dumps(payload).encode()
    req = urllib.request.Request(
        PALIGEMMA_URL,
        data=body,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {NVIDIA_KEY}"
        },
        method="POST"
    )
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=90) as resp:
            latency = time.time() - t0
            data = json.loads(resp.read())
            # PaliGemma returns {"text": "..."} or similar
            text = (data.get("text") or data.get("output") or
                    data.get("choices", [{}])[0].get("message", {}).get("content") or "")
            text = text.strip()
            if text.startswith("```"):
                parts = text.split("```")
                text = parts[1]
                if text.startswith("json"):
                    text = text[4:]
            start = text.find("{")
            end = text.rfind("}") + 1
            if start >= 0 and end > start:
                text = text[start:end]
            parsed = json.loads(text.strip())
            if isinstance(parsed, list) and parsed:
                parsed = parsed[0]
            return parsed, latency, ""
    except urllib.error.HTTPError as e:
        return None, time.time() - t0, f"HTTP {e.code}: {e.read().decode()[:300]}"
    except Exception as ex:
        return None, time.time() - t0, str(ex)
    body = json.dumps(payload).encode()
    req = urllib.request.Request(
        NVIDIA_URL,
        data=body,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {NVIDIA_KEY}"
        },
        method="POST"
    )
    t0 = time.time()
    try:
        with urllib.request.urlopen(req, timeout=60) as resp:
            latency = time.time() - t0
            data = json.loads(resp.read())
            text = data["choices"][0]["message"]["content"]
            # Strip markdown fences if present
            text = text.strip()
            if text.startswith("```"):
                parts = text.split("```")
                # parts[1] is the content after opening fence
                text = parts[1]
                if text.startswith("json"):
                    text = text[4:]
            # Find first { ... } block
            start = text.find("{")
            end = text.rfind("}") + 1
            if start >= 0 and end > start:
                text = text[start:end]
            return json.loads(text.strip()), latency, ""
    except urllib.error.HTTPError as e:
        return None, time.time() - t0, f"HTTP {e.code}: {e.read().decode()[:300]}"
    except Exception as ex:
        return None, time.time() - t0, str(ex)

# ── Comparison helpers ────────────────────────────────────────────────────────

MACRO_FIELDS = ["calories", "protein", "carbs", "fat", "freeSugarsG", "saturatedFatG"]

def diff_pct(a, b):
    if a is None or b is None:
        return "n/a"
    if a == 0 and b == 0:
        return "0%"
    denom = max(abs(a), abs(b), 1)
    return f"{abs(a - b) / denom * 100:.0f}%"

def fmt(v):
    if v is None: return "  —  "
    if isinstance(v, float): return f"{v:.1f}"
    return str(v)

def print_comparison(img_path, results):
    """results: list of (label, result_dict_or_None, latency, error)"""
    name = Path(img_path).name
    print(f"\n{'═'*90}")
    print(f"  {name}")
    print(f"{'═'*90}")

    for label, res, lat, err in results:
        if err:
            print(f"  {label:<12} ERROR: {err[:80]}")

    labels = [r[0] for r in results]
    dicts  = [r[1] or {} for r in results]
    lats   = [r[2] for r in results]

    # Header
    col = 11
    print(f"\n  {'Field':<18}" + "".join(f"{l:>{col}}" for l in labels))
    print(f"  {'-'*18}" + "".join(f"{'-'*col}" for _ in labels))
    for field in MACRO_FIELDS:
        vals = [d.get(field) for d in dicts]
        row = f"  {field:<18}" + "".join(f"{fmt(v):>{col}}" for v in vals)
        print(row)

    print()
    names = [str(d.get("name",""))[:14] for d in dicts]
    confs = [d.get("confidence","—") for d in dicts]
    print(f"  {'name':<18}" + "".join(f"{n:<{col}}" for n in names))
    print(f"  {'confidence':<18}" + "".join(f"{c:<{col}}" for c in confs))
    print(f"  {'latency':<18}" + "".join(f"{lat:.1f}s"[:col-1].ljust(col) for lat in lats))

    print()
    for label, res, lat, err in results:
        if res and res.get("analysisSummary"):
            print(f"  {label:<10} {res['analysisSummary'][:80]}")

# ── Main ──────────────────────────────────────────────────────────────────────

def run(image_paths: list[str]):
    out_dir = Path("/tmp/nvidia_vs_gemini")
    out_dir.mkdir(exist_ok=True)

    MODEL_LABELS = ["FlashLite", "Flash", "Pro3.1", "Llama11B"]
    totals: dict[str, float] = {l: 0.0 for l in MODEL_LABELS}
    ok:     dict[str, int]   = {l: 0    for l in MODEL_LABELS}
    n_imgs = 0

    for img_path in image_paths:
        p = Path(img_path)
        if not p.exists():
            print(f"  SKIP (not found): {img_path}")
            continue

        mime = "image/jpeg" if p.suffix.lower() in (".jpg", ".jpeg") else "image/png"
        with open(p, "rb") as f:
            b64 = base64.b64encode(f.read()).decode()

        print(f"\nTesting {p.name}...", end=" ", flush=True)

        fl_res, fl_lat, fl_err = call_gemini(b64, mime);       print(f"FlashLite {fl_lat:.1f}s", end=" ", flush=True)

        # gemini-3.5-flash (primary model in app)
        flash_url = (f"https://generativelanguage.googleapis.com/v1beta/models/"
                     f"gemini-3.5-flash:generateContent?key={GEMINI_KEY}")
        f_res,  f_lat,  f_err  = _call_gemini(flash_url, b64, mime); print(f"Flash {f_lat:.1f}s", end=" ", flush=True)

        time.sleep(4)  # avoid 429 on Pro (20 RPM free tier)

        pr_res, pr_lat, pr_err = call_gemini_pro(b64, mime);   print(f"Pro {pr_lat:.1f}s", end=" ", flush=True)
        n_res,  n_lat,  n_err  = call_nvidia(b64, mime);       print(f"Llama11 {n_lat:.1f}s")

        results = [
            ("FlashLite", fl_res, fl_lat, fl_err),
            ("Flash",     f_res,  f_lat,  f_err),
            ("Pro3.1",    pr_res, pr_lat, pr_err),
            ("Llama11B",  n_res,  n_lat,  n_err),
        ]
        print_comparison(img_path, results)

        stem = p.stem
        for label, res, _, _ in results:
            if res:
                (out_dir / f"{stem}_{label.lower()}.json").write_text(
                    json.dumps(res, indent=2, ensure_ascii=False))

        n_imgs += 1
        for label, res, lat, _ in results:
            totals[label] += lat
            if res: ok[label] += 1

    if n_imgs > 1:
        print(f"\n{'═'*90}")
        print(f"  SUMMARY  ({n_imgs} images)")
        print(f"  {'':18}" + "".join(f"{l:>12}" for l in MODEL_LABELS))
        print(f"  {'Success':<18}" + "".join(f"{ok[l]}/{n_imgs}".rjust(12) for l in MODEL_LABELS))
        print(f"  {'Avg latency':<18}" + "".join(f"{totals[l]/n_imgs:.1f}s".rjust(12) for l in MODEL_LABELS))
        print(f"  Results saved to {out_dir}/")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        # Default: use first 5 images from /tmp/nm_images if available
        default_dir = Path("/tmp/nm_images")
        if default_dir.exists():
            images = sorted(default_dir.glob("*.jpg"))[:5]
            if images:
                print(f"No image specified — using {len(images)} images from {default_dir}")
                run([str(p) for p in images])
            else:
                print("Usage: python3 scripts/test_nvidia_vs_gemini.py <image.jpg> [...]")
        else:
            print("Usage: python3 scripts/test_nvidia_vs_gemini.py <image.jpg> [...]")
    else:
        run(sys.argv[1:])

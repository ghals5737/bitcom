#!/usr/bin/env python3
"""
Background Check API 실측 스크립트 (표준 라이브러리만 사용).

측정 항목 (InterviewTasks.txt [제출물 2]):
  1. contract     명세(swagger.yaml) 대조용 프로브 — 다양한 입력에 대한 원본 응답/헤더 기록
  2. duplicate    같은 employeeId 로 POST 반복
  3. lifecycle    pending → 최종 상태까지 걸리는 시간 (폴링)
  4. latency      GET /background-checks/{checkId} 응답 지연 p50/p95/p99/max + 상태코드 분포
  5. concurrency  동시 요청 수를 늘렸을 때의 지연/상태코드 변화

이 스크립트는 수치와 원본 응답만 기록한다. 해석/판단은 넣지 않는다.

출력 (measure/results/<run-id>/):
  requests.jsonl   모든 HTTP 요청 1건 = 1줄 (phase, 요청, 상태, 지연, 헤더, 본문)
  lifecycle.jsonl  체크 1건 = 1줄 (생성 → 최종까지의 폴링 기록)
  summary.json     집계 결과
  summary.md       집계 결과 표 (MEASUREMENTS.md 작성 시 근거 자료)

사용:
  python3 measure/bg_measure.py                 # 전체 실행
  python3 measure/bg_measure.py --quick         # 표본 수를 줄여 빠르게
  python3 measure/bg_measure.py --phases latency,concurrency
  python3 measure/bg_measure.py --help
"""
import argparse
import json
import os
import statistics
import sys
import threading
import time
import urllib.error
import urllib.request
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone

BASE_URL_DEFAULT = "https://54capvm12g.execute-api.ap-northeast-2.amazonaws.com"

# 과제 시드 데이터. 이름은 측정용으로만 로마자 표기(복성 분리 등의 판단은 앱 쪽 문제).
SEED = [
    ("EMP-001", "Minjun", "Kim", "1990-03-15"),
    ("EMP-002", "Minjun", "Kim", "1994-11-02"),
    ("EMP-003", "Seojun", "Namgung", "1988-07-21"),
    ("EMP-004", "Raon", "Hwangbo", "1995-02-09"),
    ("EMP-005", "Sol", "Kim", "1992-12-30"),
    ("EMP-006", "Jin", "Seonwoo", "1991-05-05"),
    ("EMP-007", "Seoyeon", "Lee", None),  # 생년월일 확인되지 않음
    ("EMP-008", "Minjun", "Park", "1993-08-17"),
    ("EMP-009", "Jiwoo", "Choi", "1996-04-03"),
    ("EMP-010", "Hayun", "Jung", "1989-10-11"),
]

# swagger.yaml 에 선언된 필드/상태코드 (대조용)
SPEC = {
    "post_201_fields": ["checkId", "employeeId", "status", "createdAt", "estimatedCompletionSeconds", "message"],
    "get_200_fields": ["checkId", "employeeId", "firstName", "lastName", "dateOfBirth", "status",
                       "criminalRecord", "educationVerified", "employmentVerified", "creditScore",
                       "createdAt", "completedAt"],
    "list_200_fields": ["employeeId", "checks", "totalCount"],
    "list_item_fields": ["checkId", "status", "createdAt", "completedAt"],
    "error_fields": ["error", "message", "statusCode"],
    "unavailable_fields": ["error", "message", "retryAfter", "statusCode"],
    "status_enum": ["pending", "clear", "flagged"],
    "credit_enum": ["excellent", "good", "fair", "poor"],
    "codes": {"POST /background-checks": [201, 400],
              "GET /background-checks": [200, 400, 500, 503],
              "GET /background-checks/{checkId}": [200, 404, 500, 503]},
    "headers": ["Retry-After"],
}

FINAL = {"clear", "flagged"}
_lock = threading.Lock()


# ──────────────────────────────────────────────────────────────────────
# HTTP
# ──────────────────────────────────────────────────────────────────────
class Client:
    def __init__(self, base_url, out_dir, timeout):
        self.base = base_url.rstrip("/")
        self.timeout = timeout
        self.log = open(os.path.join(out_dir, "requests.jsonl"), "a", encoding="utf-8")
        self.seq = 0

    def call(self, phase, method, path, body=None, tag=None, headers=None, raw_body=None):
        url = self.base + path
        data = None
        hdrs = {"Accept": "application/json"}
        if headers:
            hdrs.update(headers)
        if raw_body is not None:
            data = raw_body.encode("utf-8")
            hdrs.setdefault("Content-Type", "application/json")
        elif body is not None:
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            hdrs.setdefault("Content-Type", "application/json")
        req = urllib.request.Request(url, data=data, method=method, headers=hdrs)

        rec = {"phase": phase, "tag": tag, "method": method, "path": path, "req_body": body if body is not None else raw_body,
               "ts": datetime.now(timezone.utc).isoformat()}
        t0 = time.perf_counter()
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as r:
                text = r.read().decode("utf-8", "replace")
                rec["status"] = r.status
                rec["headers"] = dict(r.headers.items())
        except urllib.error.HTTPError as e:
            text = e.read().decode("utf-8", "replace")
            rec["status"] = e.code
            rec["headers"] = dict(e.headers.items())
        except Exception as e:  # timeout, connection error 등
            text = ""
            rec["status"] = None
            rec["error"] = f"{type(e).__name__}: {e}"
            rec["headers"] = {}
        rec["latency_ms"] = round((time.perf_counter() - t0) * 1000, 1)
        try:
            rec["body"] = json.loads(text) if text else None
        except json.JSONDecodeError:
            rec["body"] = text
        rec["body_is_json"] = isinstance(rec["body"], (dict, list))
        with _lock:
            self.seq += 1
            rec["seq"] = self.seq
            self.log.write(json.dumps(rec, ensure_ascii=False) + "\n")
            self.log.flush()
        return rec

    def post(self, phase, emp, first, last, dob, tag=None, extra=None):
        body = {"employeeId": emp, "firstName": first, "lastName": last, "dateOfBirth": dob}
        if extra:
            body.update(extra)
        return self.call(phase, "POST", "/background-checks", body=body, tag=tag)

    def get(self, phase, check_id, tag=None):
        return self.call(phase, "GET", f"/background-checks/{check_id}", tag=tag)

    def list(self, phase, emp, tag=None):
        q = f"?employeeId={emp}" if emp is not None else ""
        return self.call(phase, "GET", f"/background-checks{q}", tag=tag)


# ──────────────────────────────────────────────────────────────────────
# 통계 유틸
# ──────────────────────────────────────────────────────────────────────
def pct(values, p):
    """nearest-rank 백분위."""
    if not values:
        return None
    s = sorted(values)
    k = max(1, int(round(p / 100 * len(s) + 0.5)))
    return s[min(k, len(s)) - 1]


def lat_stats(recs):
    v = [r["latency_ms"] for r in recs if r.get("latency_ms") is not None]
    if not v:
        return {"n": 0}
    return {"n": len(v), "min": min(v), "p50": pct(v, 50), "p90": pct(v, 90), "p95": pct(v, 95),
            "p99": pct(v, 99), "max": max(v), "mean": round(statistics.mean(v), 1)}


def code_dist(recs):
    c = Counter(str(r.get("status")) for r in recs)
    n = sum(c.values())
    return {"n": n, "counts": dict(sorted(c.items())),
            "ratio": {k: round(v / n, 4) for k, v in sorted(c.items())}}


def field_diff(body, spec_fields):
    if not isinstance(body, dict):
        return {"missing_from_response": spec_fields, "not_in_spec": [], "body_type": type(body).__name__}
    keys = list(body.keys())
    return {"missing_from_response": [f for f in spec_fields if f not in keys],
            "not_in_spec": [k for k in keys if k not in spec_fields]}


def now_s():
    return time.strftime("%H:%M:%S")


def say(msg):
    print(f"[{now_s()}] {msg}", flush=True)


# ──────────────────────────────────────────────────────────────────────
# Phase 1: contract (명세 대조 프로브)
# ──────────────────────────────────────────────────────────────────────
def phase_contract(c, ns):
    say("phase contract: 명세 대조 프로브")
    results = []
    emp = f"{ns}-CT-001"

    def probe(tag, fn, spec_fields=None, spec_codes=None):
        r = fn()
        entry = {"tag": tag, "method": r["method"], "path": r["path"], "req_body": r["req_body"],
                 "status": r["status"], "latency_ms": r["latency_ms"], "body": r["body"],
                 "headers": r["headers"], "error": r.get("error")}
        if spec_codes is not None:
            entry["status_in_spec"] = r["status"] in spec_codes
        if spec_fields is not None:
            entry["field_diff"] = field_diff(r["body"], spec_fields)
        results.append(entry)
        say(f"  {tag:38s} -> {r['status']} {r['latency_ms']}ms")
        return r

    # POST 정상
    ok = probe("POST valid", lambda: c.post("contract", emp, "Minjun", "Kim", "1990-03-15", tag="POST valid"),
               SPEC["post_201_fields"], SPEC["codes"]["POST /background-checks"])
    check_id = (ok["body"] or {}).get("checkId") if isinstance(ok["body"], dict) else None

    # POST 필수 필드 누락 (각각)
    for missing in ("employeeId", "firstName", "lastName", "dateOfBirth"):
        body = {"employeeId": emp, "firstName": "Minjun", "lastName": "Kim", "dateOfBirth": "1990-03-15"}
        body.pop(missing)
        probe(f"POST missing {missing}", lambda b=body: c.call("contract", "POST", "/background-checks", body=b, tag="missing"),
              SPEC["error_fields"], SPEC["codes"]["POST /background-checks"])

    # POST 시드 EMP-007 형태 (생년월일 없음: null / 빈 문자열)
    probe("POST dob=null", lambda: c.post("contract", f"{ns}-EMP-007", "Seoyeon", "Lee", None, tag="dob null"),
          SPEC["error_fields"], SPEC["codes"]["POST /background-checks"])
    probe("POST dob=''", lambda: c.post("contract", f"{ns}-EMP-007", "Seoyeon", "Lee", "", tag="dob empty"),
          SPEC["error_fields"], SPEC["codes"]["POST /background-checks"])

    # POST 이름 형식 변형
    probe("POST korean names", lambda: c.post("contract", f"{ns}-CT-KO", "민준", "김", "1990-03-15", tag="korean"),
          SPEC["post_201_fields"], SPEC["codes"]["POST /background-checks"])
    probe("POST korean fullname in lastName", lambda: c.post("contract", f"{ns}-CT-KO2", "", "김민준", "1990-03-15", tag="korean2"),
          None, SPEC["codes"]["POST /background-checks"])
    probe("POST empty strings", lambda: c.post("contract", f"{ns}-CT-EMPTY", "", "", "", tag="empty"),
          SPEC["error_fields"], SPEC["codes"]["POST /background-checks"])
    probe("POST compound surname", lambda: c.post("contract", f"{ns}-CT-NG", "Seojun", "Namgung", "1988-07-21", tag="compound"),
          SPEC["post_201_fields"], SPEC["codes"]["POST /background-checks"])

    # POST 날짜 형식 변형
    for tag, dob in (("dob slash format", "1990/03/15"), ("dob future", "2099-01-01"),
                     ("dob invalid day", "1990-02-30"), ("dob not a date", "abc"),
                     ("dob datetime", "1990-03-15T00:00:00Z")):
        probe(f"POST {tag}", lambda d=dob, t=tag: c.post("contract", f"{ns}-CT-DOB", "Minjun", "Kim", d, tag=t),
              None, SPEC["codes"]["POST /background-checks"])

    # POST 타입/형식 변형
    probe("POST extra fields", lambda: c.post("contract", f"{ns}-CT-EXTRA", "Minjun", "Kim", "1990-03-15",
                                              tag="extra", extra={"foo": "bar", "status": "clear"}),
          SPEC["post_201_fields"], SPEC["codes"]["POST /background-checks"])
    probe("POST employeeId numeric", lambda: c.call("contract", "POST", "/background-checks",
                                                    body={"employeeId": 12345, "firstName": "A", "lastName": "B", "dateOfBirth": "1990-03-15"}, tag="numeric id"),
          None, SPEC["codes"]["POST /background-checks"])
    probe("POST malformed json", lambda: c.call("contract", "POST", "/background-checks", raw_body="{not json", tag="malformed"),
          None, SPEC["codes"]["POST /background-checks"])
    probe("POST empty body", lambda: c.call("contract", "POST", "/background-checks", raw_body="", tag="empty body"),
          None, SPEC["codes"]["POST /background-checks"])
    probe("POST text/plain content-type", lambda: c.call("contract", "POST", "/background-checks",
                                                          raw_body=json.dumps({"employeeId": f"{ns}-CT-CT", "firstName": "A", "lastName": "B", "dateOfBirth": "1990-03-15"}),
                                                          headers={"Content-Type": "text/plain"}, tag="ctype"),
          None, SPEC["codes"]["POST /background-checks"])
    probe("POST very long employeeId", lambda: c.post("contract", ns + "-" + "X" * 500, "A", "B", "1990-03-15", tag="long id"),
          None, SPEC["codes"]["POST /background-checks"])

    # GET 단건
    if check_id:
        probe("GET valid checkId", lambda: c.get("contract", check_id, tag="get valid"),
              SPEC["get_200_fields"], SPEC["codes"]["GET /background-checks/{checkId}"])
    probe("GET nonexistent well-formed checkId", lambda: c.get("contract", "CHK-00000000-0000-0000-0000-000000000000", tag="get 404"),
          SPEC["error_fields"], SPEC["codes"]["GET /background-checks/{checkId}"])
    probe("GET malformed checkId", lambda: c.get("contract", "not-a-check-id", tag="get malformed"),
          SPEC["error_fields"], SPEC["codes"]["GET /background-checks/{checkId}"])
    probe("GET empty checkId (trailing slash)", lambda: c.call("contract", "GET", "/background-checks/", tag="get trailing"),
          None, None)

    # GET 목록
    probe("GET list valid", lambda: c.list("contract", emp, tag="list valid"),
          SPEC["list_200_fields"], SPEC["codes"]["GET /background-checks"])
    probe("GET list unknown employeeId", lambda: c.list("contract", f"{ns}-NOBODY", tag="list unknown"),
          SPEC["list_200_fields"], SPEC["codes"]["GET /background-checks"])
    probe("GET list without employeeId", lambda: c.list("contract", None, tag="list no param"),
          SPEC["error_fields"], SPEC["codes"]["GET /background-checks"])
    probe("GET list empty employeeId", lambda: c.list("contract", "", tag="list empty"),
          SPEC["error_fields"], SPEC["codes"]["GET /background-checks"])

    # 명세에 없는 메서드/경로
    for m in ("PUT", "DELETE", "PATCH", "OPTIONS", "HEAD"):
        probe(f"{m} /background-checks", lambda mm=m: c.call("contract", mm, "/background-checks", tag="method"), None, None)
    if check_id:
        probe("DELETE /background-checks/{id}", lambda: c.call("contract", "DELETE", f"/background-checks/{check_id}", tag="method"), None, None)
    probe("GET /", lambda: c.call("contract", "GET", "/", tag="root"), None, None)
    probe("GET /health", lambda: c.call("contract", "GET", "/health", tag="health"), None, None)

    # 헤더 관찰: 모든 프로브에서 나타난 응답 헤더 이름과 Retry-After 유무
    header_names = Counter()
    retry_after_seen = []
    for e in results:
        for h in e["headers"]:
            header_names[h.lower()] += 1
        for h, v in e["headers"].items():
            if h.lower() == "retry-after":
                retry_after_seen.append({"tag": e["tag"], "status": e["status"], "value": v})
    # 본문 retryAfter 필드
    body_retry_after = [{"tag": e["tag"], "status": e["status"], "retryAfter": e["body"].get("retryAfter")}
                        for e in results if isinstance(e["body"], dict) and "retryAfter" in e["body"]]

    # list 항목 필드 대조
    list_item_diffs = []
    for e in results:
        if e["tag"].startswith("GET list") and isinstance(e["body"], dict) and isinstance(e["body"].get("checks"), list):
            for item in e["body"]["checks"][:3]:
                list_item_diffs.append({"tag": e["tag"], "diff": field_diff(item, SPEC["list_item_fields"])})

    return {"probes": results, "n": len(results),
            "observed_header_names": dict(header_names),
            "retry_after_header_seen": retry_after_seen,
            "retry_after_body_seen": body_retry_after,
            "list_item_field_diffs": list_item_diffs,
            "check_id": check_id}


# ──────────────────────────────────────────────────────────────────────
# Phase 2: duplicate POST
# ──────────────────────────────────────────────────────────────────────
def phase_duplicate(c, ns, n, gap):
    say(f"phase duplicate: 같은 employeeId 로 POST {n}회 (간격 {gap}s)")
    emp = f"{ns}-DUP-001"
    before = c.list("duplicate", emp, tag="list before")
    recs = []
    for i in range(n):
        r = c.post("duplicate", emp, "Minjun", "Kim", "1990-03-15", tag=f"dup {i + 1}")
        recs.append(r)
        say(f"  #{i + 1:02d} -> {r['status']} checkId={(r['body'] or {}).get('checkId') if isinstance(r['body'], dict) else None} status={(r['body'] or {}).get('status') if isinstance(r['body'], dict) else None}")
        if gap:
            time.sleep(gap)
    after = c.list("duplicate", emp, tag="list after")

    # 같은 employeeId + 다른 이름/생년월일로 POST
    diff_name = c.post("duplicate", emp, "Other", "Name", "1980-01-01", tag="dup different name")
    after2 = c.list("duplicate", emp, tag="list after different name")

    ids = [r["body"].get("checkId") for r in recs if isinstance(r["body"], dict) and r["body"].get("checkId")]
    statuses = [r["body"].get("status") for r in recs if isinstance(r["body"], dict)]

    def total(rec):
        if isinstance(rec["body"], dict) and "totalCount" in rec["body"]:
            return rec["body"]["totalCount"]
        return f"(HTTP {rec['status']})"

    return {"employeeId": emp, "n": n, "gap_s": gap,
            "status_codes": code_dist(recs), "latency": lat_stats(recs),
            "check_ids": ids, "unique_check_ids": len(set(ids)),
            "initial_statuses": dict(Counter(statuses)),
            "list_totalCount_before": total(before), "list_totalCount_after": total(after),
            "list_after_body": after["body"],
            "post_different_name_same_id": {"status": diff_name["status"], "body": diff_name["body"]},
            "list_totalCount_after_different_name": total(after2)}


# ──────────────────────────────────────────────────────────────────────
# Phase 3: lifecycle (pending → final)
# ──────────────────────────────────────────────────────────────────────
def phase_lifecycle(c, ns, out_dir, n, interval, timeout):
    say(f"phase lifecycle: 체크 {n}건 생성 후 {interval}s 간격 폴링 (최대 {timeout}s)")
    lf = open(os.path.join(out_dir, "lifecycle.jsonl"), "a", encoding="utf-8")
    items = []
    for i in range(n):
        emp_id, first, last, dob = SEED[i % len(SEED)]
        if dob is None:
            dob = "1990-01-01"  # 측정용 대체값 (EMP-007 실제 처리는 앱의 판단 사항)
        emp = f"{ns}-LC-{emp_id}"
        r = c.post("lifecycle", emp, first, last, dob, tag=f"create {i + 1}")
        b = r["body"] if isinstance(r["body"], dict) else {}
        items.append({"i": i + 1, "employeeId": emp, "post_status": r["status"], "post_latency_ms": r["latency_ms"],
                      "checkId": b.get("checkId"), "initial_status": b.get("status"),
                      "estimatedCompletionSeconds": b.get("estimatedCompletionSeconds"),
                      "createdAt": b.get("createdAt"), "t_created": time.time(),
                      "polls": [], "final_status": b.get("status") if b.get("status") in FINAL else None,
                      "t_final": time.time() if b.get("status") in FINAL else None,
                      "final_body": b if b.get("status") in FINAL else None,
                      "transitions": [b.get("status")] if b.get("status") else []})
        say(f"  create #{i + 1:02d} -> {r['status']} {b.get('checkId')} initial={b.get('status')} est={b.get('estimatedCompletionSeconds')}")

    active = [it for it in items if it["checkId"] and it["final_status"] is None]
    t_start = time.time()
    while active and time.time() - t_start < timeout:
        time.sleep(interval)
        for it in list(active):
            r = c.get("lifecycle", it["checkId"], tag=f"poll {it['i']}")
            b = r["body"] if isinstance(r["body"], dict) else {}
            st = b.get("status")
            it["polls"].append({"t": round(time.time() - it["t_created"], 2), "http": r["status"],
                                "status": st, "latency_ms": r["latency_ms"], "completedAt": b.get("completedAt")})
            if st and (not it["transitions"] or it["transitions"][-1] != st):
                it["transitions"].append(st)
            if st in FINAL:
                it["final_status"] = st
                it["t_final"] = time.time()
                it["final_body"] = b
                active.remove(it)
                say(f"  #{it['i']:02d} final={st} after {it['t_final'] - it['t_created']:.1f}s ({len(it['polls'])} polls)")
    for it in active:
        say(f"  #{it['i']:02d} 미완료 (timeout {timeout}s)")

    # 생성 시점에 이미 최종 상태였던 건은 POST 응답만 있으므로, GET 200 본문을 따로 확보(최대 8회 시도)
    for it in items:
        if it["checkId"] and it["final_status"] and not it["polls"]:
            it["post_body"] = it["final_body"]
            it["final_body"] = None
            for k in range(8):
                r = c.get("lifecycle", it["checkId"], tag=f"fetch-final {it['i']}")
                it["polls"].append({"t": round(time.time() - it["t_created"], 2), "http": r["status"],
                                    "status": (r["body"] or {}).get("status") if isinstance(r["body"], dict) else None,
                                    "latency_ms": r["latency_ms"], "kind": "fetch-final"})
                if r["status"] == 200 and isinstance(r["body"], dict):
                    it["final_body"] = r["body"]
                    break
                time.sleep(interval)
            say(f"  #{it['i']:02d} 즉시완료 건 GET 본문 확보: {'성공' if it['final_body'] else '실패'}")

    # 집계
    for it in items:
        it["time_to_final_s"] = round(it["t_final"] - it["t_created"], 2) if it["t_final"] else None
        it["poll_count"] = len(it["polls"])
        it["poll_http_codes"] = dict(Counter(str(p["http"]) for p in it["polls"]))
        # 서버 타임스탬프 기준 소요 시간
        fb = it.get("final_body") or {}
        try:
            if fb.get("createdAt") and fb.get("completedAt"):
                ca = datetime.fromisoformat(fb["createdAt"].replace("Z", "+00:00"))
                cp = datetime.fromisoformat(fb["completedAt"].replace("Z", "+00:00"))
                it["server_side_duration_s"] = round((cp - ca).total_seconds(), 3)
        except Exception:
            pass
        lf.write(json.dumps(it, ensure_ascii=False) + "\n")
    lf.close()

    ttf = [it["time_to_final_s"] for it in items if it["time_to_final_s"] is not None]
    pending_only = [it["time_to_final_s"] for it in items if it["time_to_final_s"] is not None and it["initial_status"] == "pending"]
    ssd = [it["server_side_duration_s"] for it in items if it.get("server_side_duration_s") is not None]
    est = [it["estimatedCompletionSeconds"] for it in items if it.get("estimatedCompletionSeconds") is not None]
    final_fields = [field_diff(it["final_body"], SPEC["get_200_fields"]) for it in items if it.get("final_body")]
    final_values = defaultdict(Counter)
    for it in items:
        fb = it.get("final_body") or {}
        for k in ("status", "criminalRecord", "educationVerified", "employmentVerified", "creditScore"):
            if k in fb:
                final_values[k][str(fb[k])] += 1

    def dist(v):
        return {"n": len(v), "min": min(v) if v else None, "p50": pct(v, 50), "p95": pct(v, 95), "max": max(v) if v else None,
                "mean": round(statistics.mean(v), 2) if v else None}

    return {"n_created": n, "poll_interval_s": interval, "timeout_s": timeout,
            "post_status_codes": code_dist([{"status": it["post_status"]} for it in items]),
            "initial_status_dist": dict(Counter(str(it["initial_status"]) for it in items)),
            "final_status_dist": dict(Counter(str(it["final_status"]) for it in items)),
            "n_reached_final": len(ttf), "n_timed_out": len([it for it in items if it["checkId"] and it["final_status"] is None]),
            "time_to_final_s_all": dist(ttf), "time_to_final_s_pending_only": dist(pending_only),
            "server_side_duration_s": dist(ssd),
            "estimatedCompletionSeconds_values": est,
            "transitions_seen": dict(Counter(" -> ".join(it["transitions"]) for it in items)),
            "poll_http_codes_total": dict(sum((Counter(it["poll_http_codes"]) for it in items), Counter())),
            "poll_count_per_check": [it["poll_count"] for it in items],
            "final_body_field_diffs": dict(Counter(json.dumps(d, sort_keys=True) for d in final_fields)),
            "final_value_dist": {k: dict(v) for k, v in final_values.items()},
            "check_ids": [it["checkId"] for it in items if it["checkId"]]}


# ──────────────────────────────────────────────────────────────────────
# Phase 4: GET latency (순차)
# ──────────────────────────────────────────────────────────────────────
def phase_latency(c, check_ids, n, gap):
    say(f"phase latency: GET {n}회 순차 (간격 {gap}s, checkId 풀 {len(check_ids)}개)")
    recs = []
    for i in range(n):
        cid = check_ids[i % len(check_ids)]
        r = c.get("latency", cid, tag=f"get {i + 1}")
        recs.append(r)
        if (i + 1) % 25 == 0:
            say(f"  {i + 1}/{n} 진행, 최근 상태 {r['status']} {r['latency_ms']}ms")
        if gap:
            time.sleep(gap)
    by_code = defaultdict(list)
    for r in recs:
        by_code[str(r["status"])].append(r)
    non200_bodies = Counter(json.dumps(r["body"], ensure_ascii=False, sort_keys=True)[:200]
                            for r in recs if r["status"] != 200)
    retry_after = [{"seq": r["seq"], "status": r["status"], "value": v} for r in recs
                   for h, v in r["headers"].items() if h.lower() == "retry-after"]
    # 시간 순서에 따른 변화(초반/후반 절반 비교)
    half = len(recs) // 2
    responded = [r for r in recs if r["status"] is not None]
    return {"n": n, "gap_s": gap, "latency_all": lat_stats(responded), "latency_incl_timeouts": lat_stats(recs),
            "n_timeouts": len(recs) - len(responded), "status_codes": code_dist(recs),
            "latency_by_status": {k: lat_stats(v) for k, v in sorted(by_code.items())},
            "latency_first_half": lat_stats(recs[:half]), "latency_second_half": lat_stats(recs[half:]),
            "non_200_bodies": dict(non200_bodies), "retry_after_headers": retry_after,
            "errors": dict(Counter(r.get("error") for r in recs if r.get("error"))),
            "latencies_ms": [r["latency_ms"] for r in recs]}


# ──────────────────────────────────────────────────────────────────────
# Phase 5: concurrency
# ──────────────────────────────────────────────────────────────────────
def phase_concurrency(c, ns, check_ids, levels, per_level, include_post, cooldown):
    say(f"phase concurrency: 동시 {levels} × 각 {per_level}건 (GET{' + POST' if include_post else ''})")
    out = {"levels": [], "per_level": per_level}
    for lvl in levels:
        for kind in (["GET", "POST"] if include_post else ["GET"]):
            def job(i, kind=kind, lvl=lvl):
                if kind == "GET":
                    return c.get("concurrency", check_ids[i % len(check_ids)], tag=f"c{lvl} get {i}")
                return c.post("concurrency", f"{ns}-CC{lvl}-{i:03d}", "Minjun", "Kim", "1990-03-15", tag=f"c{lvl} post {i}")

            t0 = time.perf_counter()
            with ThreadPoolExecutor(max_workers=lvl) as ex:
                recs = list(ex.map(job, range(per_level)))
            wall = time.perf_counter() - t0
            retry_after = [v for r in recs for h, v in r["headers"].items() if h.lower() == "retry-after"]
            entry = {"concurrency": lvl, "kind": kind, "n": per_level, "wall_s": round(wall, 2),
                     "throughput_rps": round(per_level / wall, 2), "latency": lat_stats(recs),
                     "status_codes": code_dist(recs), "errors": dict(Counter(r.get("error") for r in recs if r.get("error"))),
                     "retry_after_values": dict(Counter(retry_after)),
                     "non_200_bodies": dict(Counter(json.dumps(r["body"], ensure_ascii=False, sort_keys=True)[:200]
                                                    for r in recs if r["status"] not in (200, 201)))}
            out["levels"].append(entry)
            say(f"  c={lvl:3d} {kind:4s} n={per_level} wall={wall:.1f}s p50={entry['latency'].get('p50')} p95={entry['latency'].get('p95')} max={entry['latency'].get('max')} codes={entry['status_codes']['counts']}")
            if cooldown:
                time.sleep(cooldown)
    return out


# ──────────────────────────────────────────────────────────────────────
# summary.md
# ──────────────────────────────────────────────────────────────────────
def md_table(headers, rows):
    out = ["| " + " | ".join(headers) + " |", "|" + "---|" * len(headers)]
    for r in rows:
        out.append("| " + " | ".join("" if v is None else str(v) for v in r) + " |")
    return "\n".join(out)


def lat_row(label, s):
    return [label, s.get("n"), s.get("min"), s.get("p50"), s.get("p90"), s.get("p95"), s.get("p99"), s.get("max"), s.get("mean")]


LAT_HDR = ["구분", "표본 n", "min(ms)", "p50", "p90", "p95", "p99", "max", "mean"]


def write_summary(summary, out_dir, args):
    L = ["# Background Check API 실측 결과 (원자료)", "",
         f"- run-id: `{args.run_id}`  / base-url: `{args.base_url}`",
         f"- 실행 시각: {summary['started_at']} ~ {summary['finished_at']} (UTC)",
         f"- 클라이언트 타임아웃: {args.timeout}s / employeeId 접두어: `{args.run_id}`",
         f"- 총 HTTP 요청 수: {summary['total_requests']} (그중 클라이언트 타임아웃/연결오류 = status `None`: {summary['status_by_phase_none_total']}건)",
         f"- 지연 통계(p50/p95/p99/max)는 응답을 받은 요청만 대상. 타임아웃 건은 `{args.timeout}s` 초과로 별도 집계.",
         "", "> 이 문서는 측정값만 담는다. 해석과 결정(타임아웃/재시도/폴링)은 MEASUREMENTS.md 에 별도 작성.", ""]

    if "latency" in summary:
        s = summary["latency"]
        L += ["## 1. GET /background-checks/{checkId} 응답 지연 (순차)", "",
              md_table(LAT_HDR, [lat_row("응답 받은 요청 전체 (타임아웃 제외)", s["latency_all"]),
                                 lat_row(f"타임아웃 {s['n_timeouts']}건 포함 (타임아웃={args.timeout}s 로 계산)", s["latency_incl_timeouts"])] +
                       [lat_row(f"HTTP {k}", v) for k, v in s["latency_by_status"].items()] +
                       [lat_row("전반부", s["latency_first_half"]), lat_row("후반부", s["latency_second_half"])]), "",
              "### 상태코드 분포", "",
              md_table(["HTTP", "건수", "비율"], [[k, v, f"{s['status_codes']['ratio'][k] * 100:.1f}%"] for k, v in s["status_codes"]["counts"].items()]),
              f"\n표본 n = {s['status_codes']['n']}", ""]
        if s["non_200_bodies"]:
            L += ["### 200 이외 응답 본문", "", md_table(["본문(앞 200자)", "건수"], [[f"`{k}`", v] for k, v in s["non_200_bodies"].items()]), ""]
        L += [f"Retry-After 헤더 관측: {len(s['retry_after_headers'])}건 " + (f"값={dict(Counter(x['value'] for x in s['retry_after_headers']))}" if s["retry_after_headers"] else ""), ""]
        if s["errors"]:
            L += ["클라이언트 측 오류(타임아웃 등): " + json.dumps(s["errors"], ensure_ascii=False), ""]

    if "duplicate" in summary:
        d = summary["duplicate"]
        L += ["## 2. 같은 employeeId 로 POST 반복", "",
              md_table(["항목", "값"], [
                  ["employeeId", f"`{d['employeeId']}`"], ["반복 횟수 n", d["n"]], ["요청 간격", f"{d['gap_s']}s"],
                  ["상태코드 분포", json.dumps(d["status_codes"]["counts"])],
                  ["응답 checkId 개수 / 고유 개수", f"{len(d['check_ids'])} / {d['unique_check_ids']}"],
                  ["초기 status 분포", json.dumps(d["initial_statuses"])],
                  ["목록 totalCount (반복 전 → 후)", f"{d['list_totalCount_before']} → {d['list_totalCount_after']}"],
                  ["같은 id, 다른 이름/생년월일 POST", f"HTTP {d['post_different_name_same_id']['status']}"],
                  ["그 후 목록 totalCount", d["list_totalCount_after_different_name"]],
                  ["POST 지연", f"p50={d['latency'].get('p50')} p95={d['latency'].get('p95')} max={d['latency'].get('max')} (n={d['latency'].get('n')})"],
              ]), ""]

    if "lifecycle" in summary:
        lc = summary["lifecycle"]
        L += ["## 3. pending → 최종 상태까지 걸리는 시간", "",
              md_table(["항목", "값"], [
                  ["생성 건수 n", lc["n_created"]], ["폴링 간격 / 타임아웃", f"{lc['poll_interval_s']}s / {lc['timeout_s']}s"],
                  ["POST 상태코드", json.dumps(lc["post_status_codes"]["counts"])],
                  ["초기 status 분포", json.dumps(lc["initial_status_dist"])],
                  ["최종 status 분포", json.dumps(lc["final_status_dist"])],
                  ["최종 도달 / 타임아웃", f"{lc['n_reached_final']} / {lc['n_timed_out']}"],
                  ["상태 전이 패턴", json.dumps(lc["transitions_seen"], ensure_ascii=False)],
                  ["estimatedCompletionSeconds 값", json.dumps(lc["estimatedCompletionSeconds_values"])],
                  ["폴링 HTTP 코드 합계", json.dumps(lc["poll_http_codes_total"])],
                  ["체크당 폴링 횟수", json.dumps(lc["poll_count_per_check"])],
              ]), "",
              md_table(["소요 시간(초)", "n", "min", "p50", "p95", "max", "mean"], [
                  ["클라이언트 기준, 전체", *[lc["time_to_final_s_all"][k] for k in ("n", "min", "p50", "p95", "max", "mean")]],
                  ["클라이언트 기준, 초기 pending 만", *[lc["time_to_final_s_pending_only"][k] for k in ("n", "min", "p50", "p95", "max", "mean")]],
                  ["서버 createdAt→completedAt", *[lc["server_side_duration_s"][k] for k in ("n", "min", "p50", "p95", "max", "mean")]],
              ]), "",
              "### 최종 응답 본문 필드 대조 (명세 GET 200 스키마 기준)", "",
              md_table(["필드 차이", "건수"], [[f"`{k}`", v] for k, v in lc["final_body_field_diffs"].items()]), "",
              "### 최종 응답 값 분포", "",
              md_table(["필드", "값 분포"], [[k, json.dumps(v)] for k, v in lc["final_value_dist"].items()]), ""]

    if "concurrency" in summary:
        cc = summary["concurrency"]
        L += ["## 4. 동시 요청 수 변화", "",
              md_table(["동시 수", "종류", "n", "wall(s)", "rps", "p50", "p95", "p99", "max", "상태코드", "Retry-After 값", "오류"],
                       [[e["concurrency"], e["kind"], e["n"], e["wall_s"], e["throughput_rps"], e["latency"].get("p50"),
                         e["latency"].get("p95"), e["latency"].get("p99"), e["latency"].get("max"),
                         json.dumps(e["status_codes"]["counts"]), json.dumps(e["retry_after_values"]) if e["retry_after_values"] else "-",
                         json.dumps(e["errors"]) if e["errors"] else "-"] for e in cc["levels"]]), ""]
        bodies = {}
        for e in cc["levels"]:
            for k, v in e["non_200_bodies"].items():
                bodies[k] = bodies.get(k, 0) + v
        if bodies:
            L += ["### 200/201 이외 응답 본문 (전 레벨 합계)", "", md_table(["본문(앞 200자)", "건수"], [[f"`{k}`", v] for k, v in bodies.items()]), ""]

    if "contract" in summary:
        ct = summary["contract"]
        L += ["## 5. 명세(swagger.yaml) 대조 프로브", "", f"프로브 수 n = {ct['n']}", "",
              md_table(["프로브", "요청", "HTTP", "명세에 있는 코드?", "지연(ms)", "응답 필드 차이 (누락 / 명세 외)", "본문(앞 160자)"],
                       [[p["tag"], f"`{p['method']} {p['path']}`", p["status"],
                         "" if "status_in_spec" not in p else ("Y" if p["status_in_spec"] else "N"), p["latency_ms"],
                         "" if "field_diff" not in p else f"누락={p['field_diff']['missing_from_response']} / 명세외={p['field_diff']['not_in_spec']}",
                         f"`{json.dumps(p['body'], ensure_ascii=False)[:160]}`" if p["body"] is not None else (p.get("error") or "")] for p in ct["probes"]]), "",
              "### 헤더 관측", "",
              f"- 응답 헤더 이름 출현 횟수: `{json.dumps(ct['observed_header_names'])}`",
              f"- `Retry-After` 헤더 관측: {json.dumps(ct['retry_after_header_seen'])}",
              f"- 본문 `retryAfter` 필드 관측: {json.dumps(ct['retry_after_body_seen'])}", "",
              "### 목록 항목 필드 대조", "",
              md_table(["프로브", "차이"], [[d["tag"], json.dumps(d["diff"])] for d in ct["list_item_field_diffs"]]) if ct["list_item_field_diffs"] else "(목록 항목 없음)", ""]

    # 전체 상태코드 (모든 phase 합산)
    L += ["## 6. 전체 요청 상태코드 (모든 phase 합산)", "",
          md_table(["phase", "HTTP", "건수"], [[ph, code, n] for ph, codes in summary["status_by_phase"].items() for code, n in codes.items()]), ""]

    with open(os.path.join(out_dir, "summary.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(L))


# ──────────────────────────────────────────────────────────────────────
def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base-url", default=BASE_URL_DEFAULT)
    ap.add_argument("--run-id", default=None, help="employeeId 접두어 겸 결과 폴더명 (기본: HBRC-<UTC시각>)")
    ap.add_argument("--phases", default="contract,duplicate,lifecycle,latency,concurrency")
    ap.add_argument("--timeout", type=float, default=60.0,
                    help="클라이언트 HTTP 타임아웃(초). 이 값을 넘긴 응답은 status=None(타임아웃)으로 기록되어 최댓값 통계에서 빠진다")
    ap.add_argument("--quick", action="store_true", help="표본 수를 줄여 빠르게 실행")
    ap.add_argument("--dup-n", type=int, default=10)
    ap.add_argument("--dup-gap", type=float, default=0.5)
    ap.add_argument("--lc-n", type=int, default=10)
    ap.add_argument("--lc-interval", type=float, default=2.0)
    ap.add_argument("--lc-timeout", type=float, default=180.0)
    ap.add_argument("--lat-n", type=int, default=200)
    ap.add_argument("--lat-gap", type=float, default=0.2)
    ap.add_argument("--cc-levels", default="1,5,10,20,50")
    ap.add_argument("--cc-per-level", type=int, default=50)
    ap.add_argument("--cc-post", action="store_true", help="동시성 단계에서 POST 도 측정")
    ap.add_argument("--cc-cooldown", type=float, default=5.0)
    args = ap.parse_args()

    if args.quick:
        args.dup_n, args.lc_n, args.lat_n, args.cc_per_level = 5, 4, 40, 10
        args.cc_levels, args.lc_timeout = "1,5,10", 90

    args.run_id = args.run_id or "HBRC-" + datetime.now(timezone.utc).strftime("%m%d%H%M%S")
    out_dir = os.path.join(os.path.dirname(os.path.abspath(__file__)), "results", args.run_id)
    os.makedirs(out_dir, exist_ok=True)
    phases = [p.strip() for p in args.phases.split(",") if p.strip()]
    say(f"run-id={args.run_id} out={out_dir} phases={phases}")

    c = Client(args.base_url, out_dir, args.timeout)
    summary = {"run_id": args.run_id, "args": vars(args), "started_at": datetime.now(timezone.utc).isoformat()}
    check_ids = []

    if "contract" in phases:
        summary["contract"] = phase_contract(c, args.run_id)
        if summary["contract"]["check_id"]:
            check_ids.append(summary["contract"]["check_id"])
    if "duplicate" in phases:
        summary["duplicate"] = phase_duplicate(c, args.run_id, args.dup_n, args.dup_gap)
        check_ids += summary["duplicate"]["check_ids"]
    if "lifecycle" in phases:
        summary["lifecycle"] = phase_lifecycle(c, args.run_id, out_dir, args.lc_n, args.lc_interval, args.lc_timeout)
        check_ids += summary["lifecycle"]["check_ids"]
    if ("latency" in phases or "concurrency" in phases) and not check_ids:
        say("checkId 풀이 비어 있어 측정용 체크 5건을 먼저 생성")
        for i in range(5):
            r = c.post("setup", f"{args.run_id}-SETUP-{i}", "Minjun", "Kim", "1990-03-15", tag="setup")
            if isinstance(r["body"], dict) and r["body"].get("checkId"):
                check_ids.append(r["body"]["checkId"])
    if "latency" in phases:
        summary["latency"] = phase_latency(c, check_ids, args.lat_n, args.lat_gap)
    if "concurrency" in phases:
        levels = [int(x) for x in args.cc_levels.split(",")]
        summary["concurrency"] = phase_concurrency(c, args.run_id, check_ids, levels, args.cc_per_level, args.cc_post, args.cc_cooldown)

    summary["finished_at"] = datetime.now(timezone.utc).isoformat()
    c.log.close()

    # phase별 상태코드 합산
    by_phase = defaultdict(Counter)
    total = 0
    with open(os.path.join(out_dir, "requests.jsonl"), encoding="utf-8") as f:
        for line in f:
            r = json.loads(line)
            by_phase[r["phase"]][str(r["status"])] += 1
            total += 1
    summary["status_by_phase"] = {k: dict(v) for k, v in by_phase.items()}
    summary["total_requests"] = total
    summary["status_by_phase_none_total"] = sum(v.get("None", 0) for v in by_phase.values())

    with open(os.path.join(out_dir, "summary.json"), "w", encoding="utf-8") as f:
        json.dump(summary, f, ensure_ascii=False, indent=2)
    write_summary(summary, out_dir, args)
    say(f"완료. 총 요청 {total}건 -> {out_dir}/summary.md")


if __name__ == "__main__":
    main()

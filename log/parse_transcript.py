#!/usr/bin/env python3
"""
Claude Code 대화 로그 파서.

~/.claude/projects/<프로젝트>/ 아래의 세션 JSONL(원본 transcript)을 읽어
"사용자 질문 1개 + 그에 대한 어시스턴트 응답 전체"를 한 턴(turn)으로 묶고,
턴별 시작/종료 시각과 소요 시간을 계산해 log/ 폴더에 저장한다.

출력:
  log/conversation.md     사람이 읽는 전체 대화 (AI_LOG.md 첨부용)
  log/conversation.jsonl  턴 단위 정제 데이터 (나중에 grep/분석용)
  log/raw/<session>.jsonl 원본 transcript 복사본 (증빙)

실행:
  python3 log/parse_transcript.py            # 프로젝트 전체 세션 파싱
  Stop 훅에서 자동 실행 (.claude/settings.json 참조)
"""
import glob
import json
import os
import re
import shutil
import sys
from datetime import datetime, timezone, timedelta

KST = timezone(timedelta(hours=9))
TOOL_RESULT_MAX_MD = 300      # md에 넣는 도구 결과 최대 길이
TOOL_RESULT_MAX_JSONL = 2000  # jsonl에 넣는 도구 결과 최대 길이
SYSTEM_REMINDER_RE = re.compile(r"<system-reminder>.*?</system-reminder>", re.S)


def project_dir_for(cwd: str) -> str:
    # Claude Code는 cwd의 '/'를 '-'로 바꿔 프로젝트 디렉터리 이름으로 쓴다.
    return os.path.expanduser("~/.claude/projects/" + cwd.replace("/", "-"))


def parse_ts(s):
    if not s:
        return None
    return datetime.fromisoformat(s.replace("Z", "+00:00"))


def fmt_ts(dt):
    return dt.astimezone(KST).strftime("%Y-%m-%d %H:%M:%S") if dt else "-"


def fmt_dur(sec):
    if sec is None:
        return "-"
    m, s = divmod(int(sec), 60)
    return f"{m}m {s:02d}s" if m else f"{s}s"


def clean_prompt(text: str) -> str:
    text = SYSTEM_REMINDER_RE.sub("", text)
    return text.strip()


def summarize_tool_use(block) -> str:
    name = block.get("name", "?")
    inp = block.get("input") or {}
    if name == "Bash":
        d = inp.get("description") or ""
        cmd = (inp.get("command") or "").strip().splitlines()[0][:120] if inp.get("command") else ""
        return f"{name}: {d or cmd}"
    for key in ("file_path", "path", "pattern", "query", "url", "skill", "description", "prompt"):
        if key in inp:
            return f"{name}: {str(inp[key])[:120]}"
    return name


def tool_result_text(block) -> str:
    c = block.get("content")
    if isinstance(c, str):
        return c
    if isinstance(c, list):
        return "\n".join(b.get("text", "") for b in c if isinstance(b, dict) and b.get("type") == "text")
    return ""


def load_records(path):
    out = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                out.append(json.loads(line))
            except json.JSONDecodeError:
                continue
    return out


def parse_session(path):
    session_id = os.path.splitext(os.path.basename(path))[0]
    turns = []
    cur = None

    def close():
        nonlocal cur
        if cur:
            if cur["end"] and cur["start"]:
                cur["duration_sec"] = (cur["end"] - cur["start"]).total_seconds()
            turns.append(cur)
            cur = None

    for rec in load_records(path):
        rtype = rec.get("type")
        if rtype not in ("user", "assistant"):
            continue
        if rec.get("isMeta") or rec.get("isSidechain"):
            continue
        msg = rec.get("message") or {}
        content = msg.get("content")
        ts = parse_ts(rec.get("timestamp"))

        if rtype == "user":
            # 문자열 content = 사용자가 직접 친 프롬프트
            if isinstance(content, str):
                text = clean_prompt(content)
                if not text:
                    continue
                close()
                cur = {"session": session_id, "start": ts, "end": ts, "prompt": text,
                       "assistant_text": [], "tools": [], "duration_sec": None}
                continue
            if isinstance(content, list):
                texts = [b.get("text", "") for b in content if b.get("type") == "text"]
                results = [b for b in content if b.get("type") == "tool_result"]
                prompt = clean_prompt("\n".join(texts))
                if prompt and not results:
                    close()
                    cur = {"session": session_id, "start": ts, "end": ts, "prompt": prompt,
                           "assistant_text": [], "tools": [], "duration_sec": None}
                    continue
                if cur and results:
                    for r in results:
                        if cur["tools"]:
                            cur["tools"][-1]["result"] = tool_result_text(r)[:TOOL_RESULT_MAX_JSONL]
                            cur["tools"][-1]["is_error"] = bool(r.get("is_error"))
                    cur["end"] = ts or cur["end"]
            continue

        # assistant
        if cur is None:
            continue
        cur["end"] = ts or cur["end"]
        if isinstance(content, list):
            for b in content:
                bt = b.get("type")
                if bt == "text" and b.get("text"):
                    cur["assistant_text"].append(b["text"])
                elif bt == "tool_use":
                    cur["tools"].append({"name": b.get("name"), "summary": summarize_tool_use(b),
                                         "ts": rec.get("timestamp"), "result": "", "is_error": False})
    close()
    return turns


def write_outputs(turns, out_dir, cwd):
    turns.sort(key=lambda t: t["start"] or datetime.min.replace(tzinfo=timezone.utc))
    md_path = os.path.join(out_dir, "conversation.md")
    jl_path = os.path.join(out_dir, "conversation.jsonl")

    with open(jl_path, "w", encoding="utf-8") as jf:
        for i, t in enumerate(turns, 1):
            jf.write(json.dumps({
                "turn": i, "session": t["session"],
                "start": t["start"].isoformat() if t["start"] else None,
                "end": t["end"].isoformat() if t["end"] else None,
                "duration_sec": t["duration_sec"],
                "prompt": t["prompt"],
                "assistant": "\n\n".join(t["assistant_text"]),
                "tools": t["tools"],
            }, ensure_ascii=False) + "\n")

    total = sum(t["duration_sec"] or 0 for t in turns)
    sessions = sorted({t["session"] for t in turns})
    lines = ["# AI 협업 대화 로그", "",
             f"- 프로젝트: `{cwd}`",
             f"- 생성 시각: {fmt_ts(datetime.now(timezone.utc))} (KST)",
             f"- 세션 수: {len(sessions)} / 턴 수: {len(turns)} / 응답 소요 합계: {fmt_dur(total)}",
             "", "## 턴 요약", "",
             "| # | 시작(KST) | 소요 | 도구 | 질문 |", "|---|---|---|---|---|"]
    for i, t in enumerate(turns, 1):
        q = t["prompt"].replace("\n", " ").replace("|", "\\|")[:60]
        lines.append(f"| {i} | {fmt_ts(t['start'])} | {fmt_dur(t['duration_sec'])} | {len(t['tools'])} | {q} |")
    lines += ["", "---", ""]

    for i, t in enumerate(turns, 1):
        lines += [f"## 턴 {i}", "",
                  f"- 시작: {fmt_ts(t['start'])} / 종료: {fmt_ts(t['end'])} / 소요: {fmt_dur(t['duration_sec'])}",
                  f"- 세션: `{t['session'][:8]}`", "",
                  "### 👤 사용자", "", t["prompt"], "", "### 🤖 Claude", ""]
        if t["tools"]:
            lines.append("<details><summary>도구 호출 {}건</summary>\n".format(len(t["tools"])))
            for tool in t["tools"]:
                flag = " ❌" if tool.get("is_error") else ""
                lines.append(f"- `{tool['summary']}`{flag}")
                res = (tool.get("result") or "").strip()
                if res:
                    snippet = res[:TOOL_RESULT_MAX_MD].replace("```", "'''")
                    more = " …" if len(res) > TOOL_RESULT_MAX_MD else ""
                    body = "\n".join("  " + ln for ln in (snippet + more).splitlines())
                    lines.append(f"  ```\n{body}\n  ```")
            lines.append("\n</details>\n")
        lines.append("\n\n".join(t["assistant_text"]) or "_(텍스트 응답 없음)_")
        lines += ["", "---", ""]

    with open(md_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines))
    return md_path, jl_path


def main():
    cwd = os.environ.get("CLAUDE_PROJECT_DIR") or os.getcwd()
    out_dir = os.path.join(cwd, "log")
    os.makedirs(os.path.join(out_dir, "raw"), exist_ok=True)

    # Stop 훅은 stdin으로 {"transcript_path": ...} 등을 넘겨준다 (없어도 동작).
    transcript_dirs = {project_dir_for(cwd)}
    if not sys.stdin.isatty():
        try:
            payload = json.loads(sys.stdin.read() or "{}")
            tp = payload.get("transcript_path")
            if tp:
                transcript_dirs.add(os.path.dirname(tp))
        except Exception:
            pass

    files = []
    for d in transcript_dirs:
        files += glob.glob(os.path.join(d, "*.jsonl"))
    files = sorted(set(files))

    turns = []
    for f in files:
        shutil.copy2(f, os.path.join(out_dir, "raw", os.path.basename(f)))
        turns += parse_session(f)

    md, jl = write_outputs(turns, out_dir, cwd)
    if sys.stdout.isatty():
        print(f"sessions={len(files)} turns={len(turns)} -> {md}, {jl}")


if __name__ == "__main__":
    main()

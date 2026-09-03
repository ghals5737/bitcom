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


ANSWER_RE = re.compile(r'"((?:[^"\\]|\\.)*?)"="((?:[^"\\]|\\.)*?)"(?=, "|\.\s|$)', re.S)
FENCE = "`" * 3


def parse_answers(text: str):
    """AskUserQuestion 결과 문자열 → [(질문, 답변)]. 형식이 안 맞으면 빈 목록."""
    if not text:
        return []
    body = re.sub(r"^(The user answered:|Your questions have been answered:)\s*", "", text.strip())
    body = re.split(r"\. (Read the answers carefully|You can now continue)", body)[0]
    return [(q.strip(), a.strip()) for q, a in ANSWER_RE.findall(body)]


def parse_session(path):
    session_id = os.path.splitext(os.path.basename(path))[0]
    turns = []
    cur = None

    def new_turn(ts, prompt):
        return {"session": session_id, "start": ts, "end": ts, "prompt": prompt,
                "events": [], "assistant_text": [], "tools": [], "duration_sec": None}

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
            if isinstance(content, str):
                text = clean_prompt(content)
                if not text:
                    continue
                close()
                cur = new_turn(ts, text)
                continue
            if isinstance(content, list):
                texts = [b.get("text", "") for b in content if b.get("type") == "text"]
                results = [b for b in content if b.get("type") == "tool_result"]
                prompt = clean_prompt("\n".join(texts))
                if prompt and not results:
                    close()
                    cur = new_turn(ts, prompt)
                    continue
                if cur and results:
                    for r in results:
                        # 결과를 해당 tool_use 이벤트에 붙인다 (tool_use_id 매칭, 없으면 마지막 도구)
                        target = None
                        for ev in reversed(cur["events"]):
                            if ev["kind"] == "tool" and ev.get("id") == r.get("tool_use_id"):
                                target = ev
                                break
                        if target is None:
                            tools = [ev for ev in cur["events"] if ev["kind"] == "tool"]
                            target = tools[-1] if tools else None
                        if target is None:
                            continue
                        full = tool_result_text(r)
                        target["is_error"] = bool(r.get("is_error"))
                        if target["name"] == "AskUserQuestion":
                            target["result"] = full            # 사용자 답변은 자르지 않는다
                            target["answers"] = parse_answers(full)
                        else:
                            target["result"] = full[:TOOL_RESULT_MAX_JSONL]
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
                    cur["events"].append({"kind": "text", "text": b["text"]})
                elif bt == "tool_use":
                    ev = {"kind": "tool", "id": b.get("id"), "name": b.get("name"), "summary": summarize_tool_use(b),
                          "ts": rec.get("timestamp"), "result": "", "is_error": False}
                    if b.get("name") == "AskUserQuestion":
                        ev["questions"] = (b.get("input") or {}).get("questions") or []
                        ev["summary"] = "AskUserQuestion: 선택지 제시 %d개" % len(ev["questions"])
                    cur["events"].append(ev)
    close()
    for t in turns:  # jsonl 호환 필드
        t["assistant_text"] = [e["text"] for e in t["events"] if e["kind"] == "text"]
        t["tools"] = [{k: v for k, v in e.items() if k != "kind"} for e in t["events"] if e["kind"] == "tool"]
    return turns


def render_ask(ev, lines):
    """AskUserQuestion 을 본문에 펼친다: 질문·선택지(추천 표시) → 사용자 답변(잘림 없음)."""
    lines.append("> **🤖 결정 요청** — Claude 가 선택지를 제시하고 사용자가 답함")
    lines.append(">")
    for n, q in enumerate(ev.get("questions") or [], 1):
        hdr = f" _({q.get('header')})_" if q.get("header") else ""
        lines.append(f"> **Q{n}. {q.get('question', '')}**{hdr}")
        for o in q.get("options") or []:
            label = o.get("label", "")
            desc = o.get("description") or ""
            lines.append(f"> - {label}" + (f" — {desc}" if desc else ""))
        lines.append(">")
    answers = ev.get("answers") or []
    lines.append("> **👤 사용자 답변**")
    if answers:
        for q, a in answers:
            lines.append(f"> - {q} → **{a}**")
    else:
        raw = (ev.get("result") or "").strip().replace("\n", " ")
        lines.append(f"> {raw or '_(답변 없음)_'}")
    lines.append("")


def render_tools(tools, lines):
    if not tools:
        return
    lines.append("<details><summary>도구 호출 {}건</summary>\n".format(len(tools)))
    for tool in tools:
        flag = " ❌" if tool.get("is_error") else ""
        lines.append(f"- `{tool['summary']}`{flag}")
        res = (tool.get("result") or "").strip()
        if res:
            snippet = res[:TOOL_RESULT_MAX_MD].replace(FENCE, "'''")
            more = " …" if len(res) > TOOL_RESULT_MAX_MD else ""
            body = "\n".join("  " + ln for ln in (snippet + more).splitlines())
            lines.append(f"  {FENCE}\n{body}\n  {FENCE}")
    lines.append("\n</details>\n")


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
                "events": t["events"],
            }, ensure_ascii=False) + "\n")

    total = sum(t["duration_sec"] or 0 for t in turns)
    sessions = sorted({t["session"] for t in turns})
    n_ask = sum(1 for t in turns for e in t["events"] if e["kind"] == "tool" and e["name"] == "AskUserQuestion")
    lines = ["# AI 협업 대화 로그", "",
             f"- 프로젝트: `{cwd}`",
             f"- 생성 시각: {fmt_ts(datetime.now(timezone.utc))} (KST)",
             f"- 세션 수: {len(sessions)} / 턴 수: {len(turns)} / 응답 소요 합계: {fmt_dur(total)} / 선택지 결정 요청: {n_ask}회",
             "- 표기: **👤 사용자** = 직접 입력한 프롬프트, **🤖 결정 요청** 블록 = Claude 가 선택지를 제시하고 사용자가 고른 지점(질문·선택지·답변 전문), 접힌 `도구 호출` = 파일 읽기·명령 실행 등",
             "", "## 턴 요약", "",
             "| # | 시작(KST) | 소요 | 도구 | 결정 요청 | 질문 |", "|---|---|---|---|---|---|"]
    for i, t in enumerate(turns, 1):
        q = t["prompt"].replace("\n", " ").replace("|", "\\|")[:60]
        asks = sum(1 for e in t["events"] if e["kind"] == "tool" and e["name"] == "AskUserQuestion")
        lines.append(f"| {i} | {fmt_ts(t['start'])} | {fmt_dur(t['duration_sec'])} | {len(t['tools'])} | {asks or ''} | {q} |")
    lines += ["", "---", ""]

    for i, t in enumerate(turns, 1):
        lines += [f"## 턴 {i}", "",
                  f"- 시작: {fmt_ts(t['start'])} / 종료: {fmt_ts(t['end'])} / 소요: {fmt_dur(t['duration_sec'])}",
                  f"- 세션: `{t['session'][:8]}`", "",
                  "### 👤 사용자", "", t["prompt"], "", "### 🤖 Claude", ""]
        pending_tools = []
        for ev in t["events"]:
            if ev["kind"] == "tool" and ev["name"] != "AskUserQuestion":
                pending_tools.append(ev)
                continue
            render_tools(pending_tools, lines)
            pending_tools = []
            if ev["kind"] == "text":
                lines += [ev["text"], ""]
            else:
                render_ask(ev, lines)
        render_tools(pending_tools, lines)
        if not any(e["kind"] == "text" for e in t["events"]):
            lines.append("_(텍스트 응답 없음)_")
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

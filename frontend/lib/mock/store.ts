// 인메모리 목업 저장소. Route Handler(app/api/*)만 이 모듈을 import 한다.
// 실제 백엔드(Spring Boot)가 붙으면 app/api/* 는 Cloudflare Pages Function 프록시로 대체되고 이 파일은 사라진다.
// 개발 서버 HMR 사이에도 상태가 유지되도록 globalThis 에 보관한다.

import type {
  AuditAction,
  AuditLog,
  BackgroundCheck,
  BgcDetail,
  BgcStatus,
  BgcSummary,
  CreateEmployeeInput,
  CreateEmployeeResult,
  Employee,
  EmployeeChangeLog,
  EmployeeDetail,
  EmployeeListItem,
  HistoryItem,
  MeProfile,
  MeSummary,
  Session,
} from "@/lib/types";

// ---------- 설정 (N1/N2/N3) ----------
export const SESSION_IDLE_MINUTES = 30;
export const SESSION_ABSOLUTE_HOURS = 8;
export const LOCK_THRESHOLD = 5;
export const PASSWORD_RULE = /^(?=.*\d)(?=.*[^A-Za-z0-9]).{8,}$/; // 8자 이상, 숫자+특수문자

export class StoreError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message);
  }
}

interface Db {
  employees: Map<string, Employee>;
  sessions: Map<string, Session>;
  changeLogs: EmployeeChangeLog[];
  backgroundChecks: BackgroundCheck[];
  auditLogs: AuditLog[];
  seq: { change: number; bgc: number; audit: number };
}

const now = () => new Date().toISOString();
const addMinutes = (iso: string, m: number) => new Date(new Date(iso).getTime() + m * 60_000).toISOString();

/** 성명 파싱 규칙 (F4): 첫 글자 = 성, 나머지 = 이름. 복성 사전 없음. */
export function parseName(name: string): { lastName: string; firstName: string } {
  const t = name.trim();
  if (t.length < 2) return { lastName: t, firstName: "" };
  return { lastName: t[0], firstName: t.slice(1) };
}

function randomId(bytes = 16) {
  const a = new Uint8Array(bytes);
  crypto.getRandomValues(a);
  return Array.from(a, (b) => b.toString(16).padStart(2, "0")).join("");
}

/** 임시 비밀번호: 12자, 숫자/특수문자 포함이 보장되도록 구성 */
export function generateTempPassword() {
  const letters = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz";
  const digits = "23456789";
  const specials = "!@#$%*";
  const pick = (s: string) => s[Math.floor(Math.random() * s.length)];
  const body = Array.from({ length: 9 }, () => pick(letters)).join("");
  return `${body}${pick(digits)}${pick(specials)}${pick(digits)}`;
}

// ---------- 시드 (F0) ----------
function seedEmployee(
  id: string,
  name: string,
  birthDate: string | null,
  extra: Partial<Employee>,
): Employee {
  const { lastName, firstName } = parseName(name);
  const t = "2026-09-01T00:00:00.000Z";
  return {
    employeeId: id,
    name,
    lastName,
    firstName,
    birthDate,
    phone: null,
    address: null,
    department: null,
    position: null,
    hireDate: null,
    role: "EMPLOYEE",
    status: "ACTIVE",
    resignedAt: null,
    passwordHash: `Temp-${id}!1`,
    mustChangePassword: true,
    failedLoginCount: 0,
    locked: false,
    createdAt: t,
    updatedAt: t,
    ...extra,
  };
}

function buildSeed(): Db {
  const rows: Employee[] = [
    seedEmployee("ADMIN-001", "관리자", "1985-01-01", {
      role: "ADMIN", department: "인사팀", position: "팀장", hireDate: "2015-03-02",
      phone: "010-1000-0001", address: "서울특별시 서초구", passwordHash: "admin1234!", mustChangePassword: false,
    }),
    seedEmployee("EMP-001", "김민준", "1990-03-15", {
      department: "개발1팀", position: "선임", hireDate: "2018-04-02", phone: "010-2000-0001", address: "서울특별시 강남구",
      passwordHash: "emp1234!", mustChangePassword: false,
    }),
    seedEmployee("EMP-002", "김민준", "1994-11-02", { department: "개발2팀", position: "주임", hireDate: "2021-07-01", phone: "010-2000-0002", address: "경기도 성남시" }),
    seedEmployee("EMP-003", "남궁서준", "1988-07-21", { department: "개발1팀", position: "책임", hireDate: "2014-01-06", phone: "010-2000-0003", address: "서울특별시 송파구" }),
    seedEmployee("EMP-004", "황보라온", "1995-02-09", { department: "디자인팀", position: "주임", hireDate: "2022-03-14", phone: "010-2000-0004", address: "서울특별시 마포구" }),
    seedEmployee("EMP-005", "김솔", "1992-12-30", { department: "QA팀", position: "선임", hireDate: "2019-09-02", phone: "010-2000-0005", address: "인천광역시 연수구" }),
    seedEmployee("EMP-006", "선우진", "1991-05-05", { department: "개발2팀", position: "선임", hireDate: "2017-11-13", phone: "010-2000-0006", address: "경기도 고양시" }),
    seedEmployee("EMP-007", "이서연", null, { department: "경영지원팀", position: "사원", hireDate: "2024-02-01", phone: "010-2000-0007", address: "서울특별시 영등포구" }),
    seedEmployee("EMP-008", "박민준", "1993-08-17", { department: "개발1팀", position: "주임", hireDate: "2020-05-18", phone: "010-2000-0008", address: "경기도 수원시" }),
    seedEmployee("EMP-009", "최지우", "1996-04-03", { department: "디자인팀", position: "사원", hireDate: "2023-08-21", phone: "010-2000-0009", address: "서울특별시 관악구" }),
    seedEmployee("EMP-010", "정하윤", "1989-10-11", { department: "QA팀", position: "책임", hireDate: "2013-06-03", phone: "010-2000-0010", address: "서울특별시 동작구" }),
  ];
  const employees = new Map(rows.map((e) => [e.employeeId, e]));

  // 화면 확인용 Background Check 이력: 완료 2건, TIMEOUT 1건
  const backgroundChecks: BackgroundCheck[] = [
    {
      id: 1, employeeId: "EMP-003", checkId: "CHK-2f6f7d8a-6e94-457d-8f1a-462584025d86", status: "CLEAR",
      criminalRecord: false, educationVerified: true, employmentVerified: true, creditScore: "good",
      requestedBy: "ADMIN-001", requestedAt: "2026-08-25T01:10:00.000Z", completedAt: "2026-08-25T01:10:24.000Z",
      lastPolledAt: "2026-08-25T01:10:24.000Z", pollCount: 3, failureReason: null,
      requestPayload: { firstName: "궁서준", lastName: "남", dateOfBirth: "1988-07-21" },
    },
    {
      id: 2, employeeId: "EMP-005", checkId: "CHK-e05fd34b-90fe-4ff5-896d-9b51c87b1e17", status: "FLAGGED",
      criminalRecord: true, educationVerified: true, employmentVerified: false, creditScore: "fair",
      requestedBy: "ADMIN-001", requestedAt: "2026-08-26T05:00:00.000Z", completedAt: "2026-08-26T05:00:33.000Z",
      lastPolledAt: "2026-08-26T05:00:33.000Z", pollCount: 5, failureReason: null,
      requestPayload: { firstName: "솔", lastName: "김", dateOfBirth: "1992-12-30" },
    },
    {
      id: 3, employeeId: "EMP-008", checkId: "CHK-11089000-a715-4b9b-ada6-3ede15e971f6", status: "TIMEOUT",
      criminalRecord: null, educationVerified: null, employmentVerified: null, creditScore: null,
      requestedBy: "ADMIN-001", requestedAt: "2026-08-28T08:30:00.000Z", completedAt: null,
      lastPolledAt: "2026-08-28T08:33:00.000Z", pollCount: 30, failureReason: "폴링 상한(180s) 초과, 마지막 응답 HTTP 500",
      requestPayload: { firstName: "민준", lastName: "박", dateOfBirth: "1993-08-17" },
    },
  ];

  const auditLogs: AuditLog[] = [
    { id: 1, actorId: "ADMIN-001", action: "BGCHECK_REQUESTED", targetEmployeeId: "EMP-003", detail: { bgcId: 1 }, createdAt: "2026-08-25T01:10:00.000Z" },
    { id: 2, actorId: "ADMIN-001", action: "BGCHECK_REQUESTED", targetEmployeeId: "EMP-005", detail: { bgcId: 2 }, createdAt: "2026-08-26T05:00:00.000Z" },
    { id: 3, actorId: "ADMIN-001", action: "BGCHECK_VIEWED", targetEmployeeId: "EMP-005", detail: { bgcId: 2, checkId: "CHK-e05fd34b-90fe-4ff5-896d-9b51c87b1e17" }, createdAt: "2026-08-26T05:05:00.000Z" },
    { id: 4, actorId: "ADMIN-001", action: "BGCHECK_REQUESTED", targetEmployeeId: "EMP-008", detail: { bgcId: 3 }, createdAt: "2026-08-28T08:30:00.000Z" },
  ];
  const changeLogs: EmployeeChangeLog[] = [
    { id: 1, employeeId: "EMP-001", changedBy: "EMP-001", field: "phone", oldValue: "010-2000-0000", newValue: "010-2000-0001", changedAt: "2026-08-27T02:00:00.000Z" },
  ];

  return { employees, sessions: new Map(), changeLogs, backgroundChecks, auditLogs, seq: { change: 1, bgc: 3, audit: 4 } };
}

const g = globalThis as unknown as { __portalMockDb?: Db };
export const db: Db = g.__portalMockDb ?? (g.__portalMockDb = buildSeed());

// ---------- 공통 ----------
function audit(actorId: string, action: AuditAction, targetEmployeeId: string | null, detail: Record<string, unknown> = {}) {
  db.auditLogs.push({ id: ++db.seq.audit, actorId, action, targetEmployeeId, detail, createdAt: now() });
}

function mustGet(id: string): Employee {
  const e = db.employees.get(id);
  if (!e) throw new StoreError(404, "NOT_FOUND", `직원을 찾을 수 없습니다: ${id}`);
  return e;
}

function deleteSessionsOf(employeeId: string) {
  for (const [sid, s] of db.sessions) if (s.employeeId === employeeId) db.sessions.delete(sid);
}

// ---------- 인증 (N1/N2) ----------
export function login(employeeId: string, password: string): Session {
  const e = db.employees.get(employeeId.trim().toUpperCase());
  if (!e) throw new StoreError(401, "INVALID_CREDENTIALS", "사번 또는 비밀번호가 올바르지 않습니다.");
  if (e.status === "RESIGNED") throw new StoreError(403, "RESIGNED", "퇴사 처리된 계정입니다. 시스템에 접근할 수 없습니다.");
  if (e.locked) throw new StoreError(423, "LOCKED", "로그인 실패 횟수를 초과해 잠긴 계정입니다. 관리자에게 임시 비밀번호 재발급을 요청하세요.");
  if (e.passwordHash !== password) {
    e.failedLoginCount += 1;
    if (e.failedLoginCount >= LOCK_THRESHOLD) {
      e.locked = true;
      deleteSessionsOf(e.employeeId);
      audit(e.employeeId, "ACCOUNT_LOCKED", e.employeeId, { failedLoginCount: e.failedLoginCount });
      throw new StoreError(423, "LOCKED", "로그인 실패 횟수를 초과해 계정이 잠겼습니다. 관리자에게 임시 비밀번호 재발급을 요청하세요.");
    }
    throw new StoreError(401, "INVALID_CREDENTIALS", `사번 또는 비밀번호가 올바르지 않습니다. (${e.failedLoginCount}/${LOCK_THRESHOLD})`);
  }
  e.failedLoginCount = 0;
  const t = now();
  const s: Session = {
    sessionId: randomId(32),
    employeeId: e.employeeId,
    createdAt: t,
    lastAccessedAt: t,
    expiresAt: addMinutes(t, SESSION_IDLE_MINUTES),
  };
  db.sessions.set(s.sessionId, s);
  return s;
}

export function logout(sessionId: string) {
  db.sessions.delete(sessionId);
}

/** 세션 검증 + 슬라이딩 연장. 퇴사자/잠금은 여기서 걸러 401. */
export function resolveSession(sessionId: string | undefined): { session: Session; employee: Employee } | null {
  if (!sessionId) return null;
  const s = db.sessions.get(sessionId);
  if (!s) return null;
  const t = now();
  const absoluteLimit = addMinutes(s.createdAt, SESSION_ABSOLUTE_HOURS * 60);
  if (t > s.expiresAt || t > absoluteLimit) {
    db.sessions.delete(sessionId);
    return null;
  }
  const e = db.employees.get(s.employeeId);
  if (!e || e.status !== "ACTIVE" || e.locked) {
    db.sessions.delete(sessionId);
    return null;
  }
  s.lastAccessedAt = t;
  s.expiresAt = addMinutes(t, SESSION_IDLE_MINUTES);
  return { session: s, employee: e };
}

export function toMeSummary(e: Employee): MeSummary {
  return { employeeId: e.employeeId, name: e.name, role: e.role, mustChangePassword: e.mustChangePassword };
}

export function changePassword(e: Employee, currentPassword: string, newPassword: string) {
  if (e.passwordHash !== currentPassword) throw new StoreError(400, "INVALID_CURRENT_PASSWORD", "현재 비밀번호가 올바르지 않습니다.");
  if (!PASSWORD_RULE.test(newPassword)) throw new StoreError(400, "WEAK_PASSWORD", "비밀번호는 8자 이상이며 숫자와 특수문자를 포함해야 합니다.");
  if (newPassword === currentPassword) throw new StoreError(400, "SAME_AS_TEMP", "임시(현재) 비밀번호와 다른 비밀번호를 사용해야 합니다.");
  e.passwordHash = newPassword;
  e.mustChangePassword = false;
  e.updatedAt = now();
}

// ---------- 직원 본인 (F2/F3) ----------
export function toMeProfile(e: Employee): MeProfile {
  const { employeeId, name, birthDate, phone, address, department, position, hireDate } = e;
  return { employeeId, name, birthDate, phone, address, department, position, hireDate };
}

const SELF_EDITABLE = ["phone", "address"] as const;
const ADMIN_EDITABLE = ["name", "birthDate", "phone", "address", "department", "position", "hireDate", "role"] as const;

function applyChanges(e: Employee, patch: Record<string, unknown>, allowed: readonly string[], actor: string) {
  const changed: string[] = [];
  for (const field of allowed) {
    if (!(field in patch)) continue;
    const raw = patch[field];
    const next = raw === "" ? null : (raw as string | null);
    const prev = (e as unknown as Record<string, string | null>)[field];
    if (prev === next) continue;
    if (field === "role" && next !== "ADMIN" && next !== "EMPLOYEE") throw new StoreError(400, "BAD_ROLE", "role은 ADMIN 또는 EMPLOYEE여야 합니다.");
    if (field === "name") {
      if (!next || next.trim().length < 2) throw new StoreError(400, "BAD_NAME", "성명은 2자 이상이어야 합니다.");
      const p = parseName(next);
      e.lastName = p.lastName;
      e.firstName = p.firstName;
    }
    (e as unknown as Record<string, string | null>)[field] = next;
    db.changeLogs.push({ id: ++db.seq.change, employeeId: e.employeeId, changedBy: actor, field, oldValue: prev ?? null, newValue: next, changedAt: now() });
    changed.push(field);
  }
  if (changed.length) {
    e.updatedAt = now();
    audit(actor, "EMPLOYEE_UPDATED", e.employeeId, { fields: changed });
  }
  return changed;
}

export function updateMe(e: Employee, patch: Record<string, unknown>) {
  const disallowed = Object.keys(patch).filter((k) => !(SELF_EDITABLE as readonly string[]).includes(k));
  if (disallowed.length) throw new StoreError(403, "FORBIDDEN_FIELD", `직원 본인은 수정할 수 없는 항목입니다: ${disallowed.join(", ")}`);
  applyChanges(e, patch, SELF_EDITABLE, e.employeeId);
  return toMeProfile(e);
}

// ---------- 관리자 (F4/F5/F6) ----------
function latestBgc(employeeId: string): BackgroundCheck | undefined {
  return db.backgroundChecks.filter((b) => b.employeeId === employeeId).sort((a, b) => b.requestedAt.localeCompare(a.requestedAt))[0];
}

export function listEmployees(status?: string): EmployeeListItem[] {
  return [...db.employees.values()]
    .filter((e) => !status || status === "ALL" || e.status === status)
    .sort((a, b) => a.employeeId.localeCompare(b.employeeId))
    .map((e) => ({
      employeeId: e.employeeId, name: e.name, department: e.department, position: e.position, hireDate: e.hireDate,
      role: e.role, status: e.status, latestBgcStatus: latestBgc(e.employeeId)?.status ?? null,
    }));
}

export function toDetail(e: Employee): EmployeeDetail {
  const checks = db.backgroundChecks.filter((b) => b.employeeId === e.employeeId);
  const { passwordHash: _omit, ...rest } = e;
  void _omit;
  return {
    ...rest,
    backgroundCheckSummary: {
      total: checks.length,
      latestStatus: latestBgc(e.employeeId)?.status ?? null,
      hasPending: checks.some((b) => b.status === "PENDING"),
    },
  };
}

export function getEmployeeDetail(id: string): EmployeeDetail {
  return toDetail(mustGet(id));
}

/** 사번 자동 채번: EMP- 접두어 안에서 마지막 + 1 */
export function nextEmployeeId(): string {
  let max = 0;
  for (const id of db.employees.keys()) {
    const m = /^EMP-(\d+)$/.exec(id);
    if (m) max = Math.max(max, parseInt(m[1], 10));
  }
  return `EMP-${String(max + 1).padStart(3, "0")}`;
}

export function createEmployee(input: CreateEmployeeInput, actor: string): CreateEmployeeResult {
  const name = (input.name ?? "").trim();
  if (name.length < 2) throw new StoreError(400, "BAD_NAME", "성명은 2자 이상이어야 합니다.");
  if (input.birthDate && !/^\d{4}-\d{2}-\d{2}$/.test(input.birthDate)) throw new StoreError(400, "BAD_DATE", "생년월일은 YYYY-MM-DD 형식이어야 합니다.");
  if (input.role !== "ADMIN" && input.role !== "EMPLOYEE") throw new StoreError(400, "BAD_ROLE", "role은 ADMIN 또는 EMPLOYEE여야 합니다.");
  const id = nextEmployeeId();
  const temporaryPassword = generateTempPassword();
  const { lastName, firstName } = parseName(name);
  const t = now();
  const e: Employee = {
    employeeId: id, name, lastName, firstName,
    birthDate: input.birthDate || null, phone: input.phone || null, address: input.address || null,
    department: input.department || null, position: input.position || null, hireDate: input.hireDate || null,
    role: input.role, status: "ACTIVE", resignedAt: null,
    passwordHash: temporaryPassword, mustChangePassword: true, failedLoginCount: 0, locked: false,
    createdAt: t, updatedAt: t,
  };
  db.employees.set(id, e);
  audit(actor, "EMPLOYEE_CREATED", id, { role: e.role, hasBirthDate: !!e.birthDate });
  return { employee: toDetail(e), temporaryPassword };
}

export function adminUpdateEmployee(id: string, patch: Record<string, unknown>, actor: string): EmployeeDetail {
  const e = mustGet(id);
  const disallowed = Object.keys(patch).filter((k) => !(ADMIN_EDITABLE as readonly string[]).includes(k));
  if (disallowed.length) throw new StoreError(400, "FORBIDDEN_FIELD", `수정할 수 없는 항목입니다: ${disallowed.join(", ")}`);
  applyChanges(e, patch, ADMIN_EDITABLE, actor);
  return toDetail(e);
}

/** 퇴사 처리 (F6/F8): 상태 변경 + 세션 삭제 + Background Check 결과 삭제 + 감사 로그 */
export function resign(id: string, resignedAt: string | null, actor: string): EmployeeDetail {
  const e = mustGet(id);
  if (e.employeeId === actor) throw new StoreError(400, "SELF_RESIGN", "본인 계정은 퇴사 처리할 수 없습니다.");
  if (e.status === "RESIGNED") throw new StoreError(409, "ALREADY_RESIGNED", "이미 퇴사 처리된 직원입니다.");
  e.status = "RESIGNED";
  e.resignedAt = resignedAt || now().slice(0, 10);
  e.updatedAt = now();
  deleteSessionsOf(e.employeeId);
  const before = db.backgroundChecks.length;
  const deleted = db.backgroundChecks.filter((b) => b.employeeId === e.employeeId).map((b) => b.id);
  db.backgroundChecks = db.backgroundChecks.filter((b) => b.employeeId !== e.employeeId);
  audit(actor, "EMPLOYEE_RESIGNED", e.employeeId, { resignedAt: e.resignedAt, sessionsRevoked: true });
  if (before !== db.backgroundChecks.length) audit(actor, "BGCHECK_DELETED", e.employeeId, { deletedCount: deleted.length, bgcIds: deleted });
  return toDetail(e);
}

/** 임시 비밀번호 재발급 (N2): 잠금 해제 + 변경 강제 + 세션 삭제 */
export function resetPassword(id: string, actor: string): { temporaryPassword: string; employee: EmployeeDetail } {
  const e = mustGet(id);
  if (e.status === "RESIGNED") throw new StoreError(409, "RESIGNED", "퇴사 처리된 직원의 비밀번호는 재발급할 수 없습니다.");
  const temporaryPassword = generateTempPassword();
  e.passwordHash = temporaryPassword;
  e.mustChangePassword = true;
  e.locked = false;
  e.failedLoginCount = 0;
  e.updatedAt = now();
  deleteSessionsOf(e.employeeId);
  audit(actor, "PASSWORD_RESET", e.employeeId, { unlocked: true });
  return { temporaryPassword, employee: toDetail(e) };
}

// ---------- Background Check (F7/F8/N3) ----------
// 목업은 외부 API의 실측 거동(즉시 완료 / pending 후 완료 / 5xx 실패 / 폴링 상한 초과)을 확률적으로 흉내낸다.

function toBgcSummary(b: BackgroundCheck): BgcSummary {
  const { id, checkId, status, requestedBy, requestedAt, completedAt, failureReason, pollCount } = b;
  return { id, checkId, status, requestedBy, requestedAt, completedAt, failureReason, pollCount };
}

function toBgcDetail(b: BackgroundCheck): BgcDetail {
  return {
    ...toBgcSummary(b), employeeId: b.employeeId,
    criminalRecord: b.criminalRecord, educationVerified: b.educationVerified, employmentVerified: b.employmentVerified,
    creditScore: b.creditScore, requestPayload: b.requestPayload, lastPolledAt: b.lastPolledAt,
  };
}

function randomFinal(b: BackgroundCheck) {
  const flagged = Math.random() < 0.35;
  b.status = flagged ? "FLAGGED" : "CLEAR";
  b.criminalRecord = flagged && Math.random() < 0.6;
  b.educationVerified = Math.random() < 0.85;
  b.employmentVerified = !flagged || Math.random() < 0.5;
  b.creditScore = (["excellent", "good", "fair", "poor"] as const)[Math.floor(Math.random() * 4)];
  b.completedAt = now();
  b.lastPolledAt = b.completedAt;
}

export function listBgc(employeeId: string): BgcSummary[] {
  mustGet(employeeId);
  return db.backgroundChecks.filter((b) => b.employeeId === employeeId).sort((a, b) => b.requestedAt.localeCompare(a.requestedAt)).map(toBgcSummary);
}

export function requestBgc(employeeId: string, actor: string): BgcSummary {
  const e = mustGet(employeeId);
  if (e.status === "RESIGNED") throw new StoreError(409, "RESIGNED", "퇴사 처리된 직원은 조회할 수 없습니다.");
  if (!e.birthDate) throw new StoreError(400, "NO_BIRTH_DATE", "생년월일이 확인되지 않은 직원은 Background Check를 요청할 수 없습니다. (외부 API 필수 항목)");
  if (db.backgroundChecks.some((b) => b.employeeId === employeeId && b.status === "PENDING")) {
    throw new StoreError(409, "ALREADY_PENDING", "진행 중인 Background Check가 있습니다. 완료된 뒤 다시 요청할 수 있습니다.");
  }
  const t = now();
  const b: BackgroundCheck = {
    id: ++db.seq.bgc, employeeId, checkId: null, status: "PENDING",
    criminalRecord: null, educationVerified: null, employmentVerified: null, creditScore: null,
    requestedBy: actor, requestedAt: t, completedAt: null, lastPolledAt: null, pollCount: 0, failureReason: null,
    requestPayload: { firstName: e.firstName, lastName: e.lastName, dateOfBirth: e.birthDate },
  };
  db.backgroundChecks.push(b);
  audit(actor, "BGCHECK_REQUESTED", employeeId, { bgcId: b.id });

  // 외부 POST 시뮬레이션
  const roll = Math.random();
  if (roll < 0.12) {
    b.status = "FAILED";
    b.failureReason = "POST 재시도 소진: HTTP 503 (Service Unavailable) x3";
  } else {
    b.checkId = `CHK-${randomId(4)}-${randomId(2)}-${randomId(2)}-${randomId(2)}-${randomId(6)}`;
    if (roll < 0.35) {
      randomFinal(b); // 즉시 완료
    } else if (roll < 0.45) {
      // 폴링 상한 초과 시뮬레이션: 25초 뒤 TIMEOUT
      setTimeout(() => {
        if (b.status === "PENDING") {
          b.status = "TIMEOUT";
          b.pollCount = 12;
          b.lastPolledAt = now();
          b.failureReason = "폴링 상한 초과, 마지막 응답 HTTP 500";
        }
      }, 25_000);
    } else {
      // pending → 15~40초 뒤 완료 (실측 17~39s 반영)
      const delay = 15_000 + Math.random() * 25_000;
      const tick = setInterval(() => {
        if (b.status !== "PENDING") return clearInterval(tick);
        b.pollCount += 1;
        b.lastPolledAt = now();
      }, 3_000);
      setTimeout(() => {
        clearInterval(tick);
        if (b.status === "PENDING") randomFinal(b);
      }, delay);
    }
  }
  return toBgcSummary(b);
}

export function getBgcDetail(bgcId: number, actor: string): BgcDetail {
  const b = db.backgroundChecks.find((x) => x.id === bgcId);
  if (!b) throw new StoreError(404, "NOT_FOUND", "Background Check 결과를 찾을 수 없습니다.");
  audit(actor, "BGCHECK_VIEWED", b.employeeId, { bgcId: b.id, checkId: b.checkId });
  return toBgcDetail(b);
}

/** TIMEOUT 건 GET 재확인 (POST 없음) */
export function refreshBgc(bgcId: number, _actor: string): BgcSummary {
  const b = db.backgroundChecks.find((x) => x.id === bgcId);
  if (!b) throw new StoreError(404, "NOT_FOUND", "Background Check 결과를 찾을 수 없습니다.");
  if (b.status !== "TIMEOUT") throw new StoreError(409, "NOT_TIMEOUT", "TIMEOUT 상태인 건만 재확인할 수 있습니다.");
  b.pollCount += 1;
  b.lastPolledAt = now();
  if (Math.random() < 0.7) {
    randomFinal(b);
    b.failureReason = null;
  } else {
    b.failureReason = "재확인 GET 응답 HTTP 503 (retryAfter 30)";
  }
  return toBgcSummary(b);
}

// ---------- 이력 탭 (N4) ----------
export function history(employeeId: string): HistoryItem[] {
  mustGet(employeeId);
  const changes: HistoryItem[] = db.changeLogs
    .filter((c) => c.employeeId === employeeId)
    .map((c) => ({ kind: "change", id: c.id, at: c.changedAt, actor: c.changedBy, field: c.field, oldValue: c.oldValue, newValue: c.newValue }));
  const audits: HistoryItem[] = db.auditLogs
    .filter((a) => a.targetEmployeeId === employeeId)
    .map((a) => ({ kind: "audit", id: a.id, at: a.createdAt, actor: a.actorId, action: a.action, detail: a.detail }));
  return [...changes, ...audits].sort((a, b) => b.at.localeCompare(a.at));
}

export type { BgcStatus };

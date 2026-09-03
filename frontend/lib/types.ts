// DB 스키마(docs/implementation-plan.md 3절)와 1:1로 맞춘 타입.
// 목업 단계에서는 이 타입이 인메모리 저장소의 행이고, 실제 백엔드 연동 후에는 DTO 타입으로 남는다.

export type Role = "ADMIN" | "EMPLOYEE";
export type EmployeeStatus = "ACTIVE" | "RESIGNED";
export type BgcStatus = "PENDING" | "CLEAR" | "FLAGGED" | "FAILED" | "TIMEOUT";
export type CreditScore = "excellent" | "good" | "fair" | "poor";

export interface Employee {
  employeeId: string; // EMP-001, ADMIN-001
  name: string; // 성명 통째
  lastName: string; // 첫 글자
  firstName: string; // 나머지
  birthDate: string | null; // YYYY-MM-DD, EMP-007은 null
  phone: string | null;
  address: string | null;
  department: string | null;
  position: string | null;
  hireDate: string | null;
  role: Role;
  status: EmployeeStatus;
  resignedAt: string | null;
  passwordHash: string; // 목업에서는 평문
  mustChangePassword: boolean;
  failedLoginCount: number;
  locked: boolean;
  createdAt: string;
  updatedAt: string;
}

export interface Session {
  sessionId: string;
  employeeId: string;
  createdAt: string;
  lastAccessedAt: string;
  expiresAt: string;
}

export interface EmployeeChangeLog {
  id: number;
  employeeId: string;
  changedBy: string;
  field: string;
  oldValue: string | null;
  newValue: string | null;
  changedAt: string;
}

export interface BackgroundCheck {
  id: number;
  employeeId: string;
  checkId: string | null;
  status: BgcStatus;
  criminalRecord: boolean | null;
  educationVerified: boolean | null;
  employmentVerified: boolean | null;
  creditScore: CreditScore | null;
  requestedBy: string;
  requestedAt: string;
  completedAt: string | null;
  lastPolledAt: string | null;
  pollCount: number;
  failureReason: string | null;
  requestPayload: { firstName: string; lastName: string; dateOfBirth: string } | null;
}

export type AuditAction =
  | "EMPLOYEE_CREATED"
  | "EMPLOYEE_UPDATED"
  | "BGCHECK_REQUESTED"
  | "BGCHECK_VIEWED"
  | "BGCHECK_DELETED"
  | "EMPLOYEE_RESIGNED"
  | "PASSWORD_RESET"
  | "ACCOUNT_LOCKED";

export interface AuditLog {
  id: number;
  actorId: string;
  action: AuditAction;
  targetEmployeeId: string | null;
  detail: Record<string, unknown>;
  createdAt: string;
}

// ---------- API 응답 DTO ----------

/** 로그인한 본인 요약 (GET /api/auth/me) */
export interface MeSummary {
  employeeId: string;
  name: string;
  role: Role;
  mustChangePassword: boolean;
}

/** 직원 본인이 볼 수 있는 항목 (GET /api/me) */
export interface MeProfile {
  employeeId: string;
  name: string;
  birthDate: string | null;
  phone: string | null;
  address: string | null;
  department: string | null;
  position: string | null;
  hireDate: string | null;
}

/** 관리자 목록 행 */
export interface EmployeeListItem {
  employeeId: string;
  name: string;
  department: string | null;
  position: string | null;
  hireDate: string | null;
  role: Role;
  status: EmployeeStatus;
  latestBgcStatus: BgcStatus | null;
}

/** 관리자 상세 */
export interface EmployeeDetail {
  employeeId: string;
  name: string;
  lastName: string;
  firstName: string;
  birthDate: string | null;
  phone: string | null;
  address: string | null;
  department: string | null;
  position: string | null;
  hireDate: string | null;
  role: Role;
  status: EmployeeStatus;
  resignedAt: string | null;
  mustChangePassword: boolean;
  locked: boolean;
  failedLoginCount: number;
  createdAt: string;
  updatedAt: string;
  backgroundCheckSummary: { total: number; latestStatus: BgcStatus | null; hasPending: boolean };
}

export interface BgcSummary {
  id: number;
  checkId: string | null;
  status: BgcStatus;
  requestedBy: string;
  requestedAt: string;
  completedAt: string | null;
  failureReason: string | null;
  pollCount: number;
}

export interface BgcDetail extends BgcSummary {
  employeeId: string;
  criminalRecord: boolean | null;
  educationVerified: boolean | null;
  employmentVerified: boolean | null;
  creditScore: CreditScore | null;
  requestPayload: BackgroundCheck["requestPayload"];
  lastPolledAt: string | null;
}

export type HistoryItem =
  | { kind: "change"; id: number; at: string; actor: string; field: string; oldValue: string | null; newValue: string | null }
  | { kind: "audit"; id: number; at: string; actor: string; action: AuditAction; detail: Record<string, unknown> };

export interface CreateEmployeeInput {
  name: string;
  birthDate: string | null;
  phone: string | null;
  address: string | null;
  department: string | null;
  position: string | null;
  hireDate: string | null;
  role: Role;
}

export interface CreateEmployeeResult {
  employee: EmployeeDetail;
  temporaryPassword: string;
}

export interface ApiError {
  error: string;
  message: string;
}

# Background Check API 실측 결과 (원자료)

- run-id: `HBRC-TEST2`  / base-url: `https://54capvm12g.execute-api.ap-northeast-2.amazonaws.com`
- 실행 시각: 2026-09-03T00:56:17.893437+00:00 ~ 2026-09-03T00:58:22.595065+00:00 (UTC)
- 클라이언트 타임아웃: 60.0s / employeeId 접두어: `HBRC-TEST2`
- 총 HTTP 요청 수: 24 (그중 클라이언트 타임아웃/연결오류 = status `None`: 0건)
- 지연 통계(p50/p95/p99/max)는 응답을 받은 요청만 대상. 타임아웃 건은 `60.0s` 초과로 별도 집계.

> 이 문서는 측정값만 담는다. 해석과 결정(타임아웃/재시도/폴링)은 MEASUREMENTS.md 에 별도 작성.

## 1. GET /background-checks/{checkId} 응답 지연 (순차)

| 구분 | 표본 n | min(ms) | p50 | p90 | p95 | p99 | max | mean |
|---|---|---|---|---|---|---|---|---|
| 응답 받은 요청 전체 (타임아웃 제외) | 10 | 56.7 | 249.1 | 30069.0 | 30069.0 | 30069.0 | 30069.0 | 6641.8 |
| 타임아웃 0건 포함 (타임아웃=60.0s 로 계산) | 10 | 56.7 | 249.1 | 30069.0 | 30069.0 | 30069.0 | 30069.0 | 6641.8 |
| HTTP 200 | 3 | 249.1 | 328.2 | 5353.3 | 5353.3 | 5353.3 | 5353.3 | 1976.9 |
| HTTP 500 | 4 | 59.9 | 70.6 | 95.6 | 95.6 | 95.6 | 95.6 | 77.1 |
| HTTP 503 | 3 | 56.7 | 30053.5 | 30069.0 | 30069.0 | 30069.0 | 30069.0 | 20059.7 |
| 전반부 | 5 | 82.4 | 328.2 | 30069.0 | 30069.0 | 30069.0 | 30069.0 | 7216.4 |
| 후반부 | 5 | 56.7 | 70.6 | 30053.5 | 30053.5 | 30053.5 | 30053.5 | 6067.3 |

### 상태코드 분포

| HTTP | 건수 | 비율 |
|---|---|---|
| 200 | 3 | 30.0% |
| 500 | 4 | 40.0% |
| 503 | 3 | 30.0% |

표본 n = 10

### 200 이외 응답 본문

| 본문(앞 200자) | 건수 |
|---|---|
| `{"error": "Internal Server Error", "message": "Service temporarily unavailable. Please try again later.", "statusCode": 500}` | 4 |
| `{"message": "Service Unavailable"}` | 2 |
| `{"error": "Service Unavailable", "message": "The service is currently overloaded. Please retry after the specified time.", "retryAfter": 30, "statusCode": 503}` | 1 |

Retry-After 헤더 관측: 0건 

## 3. pending → 최종 상태까지 걸리는 시간

| 항목 | 값 |
|---|---|
| 생성 건수 n | 3 |
| 폴링 간격 / 타임아웃 | 2.0s / 90.0s |
| POST 상태코드 | {"201": 3} |
| 초기 status 분포 | {"flagged": 1, "clear": 1, "pending": 1} |
| 최종 status 분포 | {"flagged": 1, "clear": 2} |
| 최종 도달 / 타임아웃 | 3 / 0 |
| 상태 전이 패턴 | {"flagged": 1, "clear": 1, "pending -> clear": 1} |
| estimatedCompletionSeconds 값 | [] |
| 폴링 HTTP 코드 합계 | {"503": 4, "200": 3, "500": 4} |
| 체크당 폴링 횟수 | [3, 4, 4] |

| 소요 시간(초) | n | min | p50 | p95 | max | mean |
|---|---|---|---|---|---|---|
| 클라이언트 기준, 전체 | 3 | 0.0 | 0.0 | 38.67 | 38.67 | 12.89 |
| 클라이언트 기준, 초기 pending 만 | 1 | 38.67 | 38.67 | 38.67 | 38.67 | 38.67 |
| 서버 createdAt→completedAt | 3 | 0.0 | 0.0 | 38.613 | 38.613 | 12.87 |

### 최종 응답 본문 필드 대조 (명세 GET 200 스키마 기준)

| 필드 차이 | 건수 |
|---|---|
| `{"missing_from_response": [], "not_in_spec": []}` | 3 |

### 최종 응답 값 분포

| 필드 | 값 분포 |
|---|---|
| status | {"flagged": 1, "clear": 2} |
| criminalRecord | {"False": 3} |
| educationVerified | {"True": 2, "False": 1} |
| employmentVerified | {"True": 2, "False": 1} |
| creditScore | {"good": 2, "poor": 1} |

## 6. 전체 요청 상태코드 (모든 phase 합산)

| phase | HTTP | 건수 |
|---|---|---|
| lifecycle | 201 | 3 |
| lifecycle | 503 | 4 |
| lifecycle | 500 | 4 |
| lifecycle | 200 | 3 |
| latency | 200 | 3 |
| latency | 500 | 4 |
| latency | 503 | 3 |

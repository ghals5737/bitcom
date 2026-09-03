# Background Check API 실측 결과 (원자료)

- run-id: `HBRC-FULL-0903`  / base-url: `https://54capvm12g.execute-api.ap-northeast-2.amazonaws.com`
- 실행 시각: 2026-09-03T01:54:02.042785+00:00 ~ 2026-09-03T02:28:43.458152+00:00 (UTC)
- 클라이언트 타임아웃: 60.0s / employeeId 접두어: `HBRC-FULL-0903`
- 총 HTTP 요청 수: 785 (그중 클라이언트 타임아웃/연결오류 = status `None`: 0건)
- 지연 통계(p50/p95/p99/max)는 응답을 받은 요청만 대상. 타임아웃 건은 `60.0s` 초과로 별도 집계.

> 이 문서는 측정값만 담는다. 해석과 결정(타임아웃/재시도/폴링)은 MEASUREMENTS.md 에 별도 작성.

## 1. GET /background-checks/{checkId} 응답 지연 (순차)

| 구분 | 표본 n | min(ms) | p50 | p90 | p95 | p99 | max | mean |
|---|---|---|---|---|---|---|---|---|
| 응답 받은 요청 전체 (타임아웃 제외) | 200 | 53.2 | 238.4 | 30044.6 | 30120.6 | 31112.7 | 32045.2 | 6223.4 |
| 타임아웃 0건 포함 (타임아웃=60.0s 로 계산) | 200 | 53.2 | 238.4 | 30044.6 | 30120.6 | 31112.7 | 32045.2 | 6223.4 |
| HTTP 200 | 67 | 96.3 | 700.6 | 26231.2 | 27846.0 | 32045.2 | 32045.2 | 8247.9 |
| HTTP 500 | 74 | 53.2 | 77.8 | 259.9 | 1029.0 | 5180.8 | 5180.8 | 256.5 |
| HTTP 503 | 59 | 56.3 | 319.7 | 30191.1 | 30929.9 | 31537.9 | 31537.9 | 11408.3 |
| 전반부 | 100 | 53.2 | 148.5 | 30045.2 | 30062.7 | 31537.9 | 31537.9 | 6332.2 |
| 후반부 | 100 | 56.3 | 339.8 | 29922.4 | 30292.3 | 32045.2 | 32045.2 | 6114.5 |

### 상태코드 분포

| HTTP | 건수 | 비율 |
|---|---|---|
| 200 | 67 | 33.5% |
| 500 | 74 | 37.0% |
| 503 | 59 | 29.5% |

표본 n = 200

### 200 이외 응답 본문

| 본문(앞 200자) | 건수 |
|---|---|
| `{"error": "Internal Server Error", "message": "Service temporarily unavailable. Please try again later.", "statusCode": 500}` | 74 |
| `{"message": "Service Unavailable"}` | 22 |
| `{"error": "Service Unavailable", "message": "The service is currently overloaded. Please retry after the specified time.", "retryAfter": 30, "statusCode": 503}` | 37 |

Retry-After 헤더 관측: 0건 

## 2. 같은 employeeId 로 POST 반복

| 항목 | 값 |
|---|---|
| employeeId | `HBRC-FULL-0903-DUP-001` |
| 반복 횟수 n | 10 |
| 요청 간격 | 0.5s |
| 상태코드 분포 | {"201": 10} |
| 응답 checkId 개수 / 고유 개수 | 10 / 10 |
| 초기 status 분포 | {"pending": 4, "flagged": 6} |
| 목록 totalCount (반복 전 → 후) | (HTTP 500) → (HTTP 500) |
| 같은 id, 다른 이름/생년월일 POST | HTTP 201 |
| 그 후 목록 totalCount | 11 |
| POST 지연 | p50=78.3 p95=153.8 max=153.8 (n=10) |

## 3. pending → 최종 상태까지 걸리는 시간

| 항목 | 값 |
|---|---|
| 생성 건수 n | 10 |
| 폴링 간격 / 타임아웃 | 2.0s / 180.0s |
| POST 상태코드 | {"201": 10} |
| 초기 status 분포 | {"flagged": 1, "clear": 4, "pending": 5} |
| 최종 status 분포 | {"flagged": 2, "clear": 8} |
| 최종 도달 / 타임아웃 | 10 / 0 |
| 상태 전이 패턴 | {"flagged": 1, "clear": 4, "pending -> clear": 4, "pending -> flagged": 1} |
| estimatedCompletionSeconds 값 | [] |
| 폴링 HTTP 코드 합계 | {"200": 10, "500": 7, "503": 6} |
| 체크당 폴링 횟수 | [1, 3, 5, 3, 1, 4, 1, 1, 3, 1] |

| 소요 시간(초) | n | min | p50 | p95 | max | mean |
|---|---|---|---|---|---|---|
| 클라이언트 기준, 전체 | 10 | 0.0 | 83.62 | 168.12 | 168.12 | 63.51 |
| 클라이언트 기준, 초기 pending 만 | 5 | 83.62 | 127.87 | 168.12 | 168.12 | 127.02 |
| 서버 createdAt→completedAt | 10 | 0.0 | 78.094 | 153.164 | 153.164 | 53.51 |

### 최종 응답 본문 필드 대조 (명세 GET 200 스키마 기준)

| 필드 차이 | 건수 |
|---|---|
| `{"missing_from_response": [], "not_in_spec": []}` | 10 |

### 최종 응답 값 분포

| 필드 | 값 분포 |
|---|---|
| status | {"flagged": 2, "clear": 8} |
| criminalRecord | {"False": 9, "True": 1} |
| educationVerified | {"True": 8, "False": 2} |
| employmentVerified | {"True": 8, "False": 2} |
| creditScore | {"good": 4, "poor": 3, "fair": 3} |

## 4. 동시 요청 수 변화

| 동시 수 | 종류 | n | wall(s) | rps | p50 | p95 | p99 | max | 상태코드 | Retry-After 값 | 오류 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | GET | 50 | 342.9 | 0.15 | 253.0 | 30053.0 | 30114.5 | 30114.5 | {"200": 19, "500": 19, "503": 12} | - | - |
| 1 | POST | 50 | 4.25 | 11.77 | 80.5 | 103.1 | 123.4 | 123.4 | {"201": 50} | - | - |
| 5 | GET | 50 | 87.44 | 0.57 | 488.9 | 30101.8 | 30188.5 | 30188.5 | {"200": 25, "500": 14, "503": 11} | - | - |
| 5 | POST | 50 | 1.1 | 45.65 | 94.8 | 132.5 | 531.0 | 531.0 | {"201": 50} | - | - |
| 10 | GET | 50 | 31.84 | 1.57 | 88.9 | 30040.2 | 30068.2 | 30068.2 | {"200": 16, "500": 18, "503": 16} | - | - |
| 10 | POST | 50 | 1.01 | 49.4 | 119.1 | 457.3 | 464.7 | 464.7 | {"201": 50} | - | - |
| 20 | GET | 50 | 31.09 | 1.61 | 521.4 | 30081.1 | 30349.1 | 30349.1 | {"200": 17, "500": 16, "503": 17} | - | - |
| 20 | POST | 50 | 1.57 | 31.81 | 128.2 | 1401.3 | 1468.7 | 1468.7 | {"201": 50} | - | - |
| 50 | GET | 50 | 30.16 | 1.66 | 270.9 | 30070.0 | 30085.4 | 30085.4 | {"200": 21, "500": 15, "503": 14} | - | - |
| 50 | POST | 50 | 1.38 | 36.23 | 133.3 | 1260.3 | 1276.9 | 1276.9 | {"201": 50} | - | - |

### 200/201 이외 응답 본문 (전 레벨 합계)

| 본문(앞 200자) | 건수 |
|---|---|
| `{"error": "Internal Server Error", "message": "Service temporarily unavailable. Please try again later.", "statusCode": 500}` | 82 |
| `{"error": "Service Unavailable", "message": "The service is currently overloaded. Please retry after the specified time.", "retryAfter": 30, "statusCode": 503}` | 42 |
| `{"message": "Service Unavailable"}` | 28 |

## 5. 명세(swagger.yaml) 대조 프로브

프로브 수 n = 38

| 프로브 | 요청 | HTTP | 명세에 있는 코드? | 지연(ms) | 응답 필드 차이 (누락 / 명세 외) | 본문(앞 160자) |
|---|---|---|---|---|---|---|
| POST valid | `POST /background-checks` | 201 | Y | 1438.1 | 누락=['estimatedCompletionSeconds'] / 명세외=[] | `{"checkId": "CHK-e52ae328-b466-4454-b046-f56936ffe99f", "employeeId": "HBRC-FULL-0903-CT-001", "status": "pending", "createdAt": "2026-09-03T01:54:02.644Z", "me` |
| POST missing employeeId | `POST /background-checks` | 400 | Y | 77.5 | 누락=[] / 명세외=[] | `{"error": "Bad Request", "message": "Missing required field: employeeId", "statusCode": 400}` |
| POST missing firstName | `POST /background-checks` | 400 | Y | 65.8 | 누락=[] / 명세외=[] | `{"error": "Bad Request", "message": "Missing required field: firstName", "statusCode": 400}` |
| POST missing lastName | `POST /background-checks` | 400 | Y | 70.0 | 누락=[] / 명세외=[] | `{"error": "Bad Request", "message": "Missing required field: lastName", "statusCode": 400}` |
| POST missing dateOfBirth | `POST /background-checks` | 400 | Y | 60.8 | 누락=[] / 명세외=[] | `{"error": "Bad Request", "message": "Missing required field: dateOfBirth", "statusCode": 400}` |
| POST dob=null | `POST /background-checks` | 400 | Y | 77.6 | 누락=[] / 명세외=[] | `{"error": "Bad Request", "message": "Missing required field: dateOfBirth", "statusCode": 400}` |
| POST dob='' | `POST /background-checks` | 400 | Y | 71.3 | 누락=[] / 명세외=[] | `{"error": "Bad Request", "message": "Missing required field: dateOfBirth", "statusCode": 400}` |
| POST korean names | `POST /background-checks` | 201 | Y | 132.9 | 누락=['estimatedCompletionSeconds'] / 명세외=[] | `{"checkId": "CHK-3530fdf1-371e-4ad9-a33d-e521f414de47", "employeeId": "HBRC-FULL-0903-CT-KO", "status": "pending", "createdAt": "2026-09-03T01:54:03.977Z", "mes` |
| POST korean fullname in lastName | `POST /background-checks` | 400 | Y | 58.4 |  | `{"error": "Bad Request", "message": "Missing required field: firstName", "statusCode": 400}` |
| POST empty strings | `POST /background-checks` | 400 | Y | 58.6 | 누락=[] / 명세외=[] | `{"error": "Bad Request", "message": "Missing required field: firstName", "statusCode": 400}` |
| POST compound surname | `POST /background-checks` | 201 | Y | 101.9 | 누락=['estimatedCompletionSeconds'] / 명세외=[] | `{"checkId": "CHK-3558fafa-d7ef-4e1c-b18c-6b97f80285e6", "employeeId": "HBRC-FULL-0903-CT-NG", "status": "pending", "createdAt": "2026-09-03T01:54:04.235Z", "mes` |
| POST dob slash format | `POST /background-checks` | 201 | Y | 98.7 |  | `{"checkId": "CHK-2c47978d-fdea-4d2d-8146-872d297012a6", "employeeId": "HBRC-FULL-0903-CT-DOB", "status": "clear", "createdAt": "2026-09-03T01:54:04.326Z", "mess` |
| POST dob future | `POST /background-checks` | 201 | Y | 62.9 |  | `{"checkId": "CHK-9e4c68ab-8d54-4515-a759-d36ae7b23a27", "employeeId": "HBRC-FULL-0903-CT-DOB", "status": "clear", "createdAt": "2026-09-03T01:54:04.419Z", "mess` |
| POST dob invalid day | `POST /background-checks` | 201 | Y | 75.7 |  | `{"checkId": "CHK-0fd9f903-25d8-42c0-923b-6429e1802c40", "employeeId": "HBRC-FULL-0903-CT-DOB", "status": "pending", "createdAt": "2026-09-03T01:54:04.490Z", "me` |
| POST dob not a date | `POST /background-checks` | 201 | Y | 101.0 |  | `{"checkId": "CHK-361def78-eac9-408b-b5e1-4d28b71a1e82", "employeeId": "HBRC-FULL-0903-CT-DOB", "status": "pending", "createdAt": "2026-09-03T01:54:04.567Z", "me` |
| POST dob datetime | `POST /background-checks` | 201 | Y | 77.7 |  | `{"checkId": "CHK-05d48e5d-9e2b-4996-9dd0-461e8467ebc7", "employeeId": "HBRC-FULL-0903-CT-DOB", "status": "clear", "createdAt": "2026-09-03T01:54:04.677Z", "mess` |
| POST extra fields | `POST /background-checks` | 201 | Y | 100.7 | 누락=['estimatedCompletionSeconds'] / 명세외=[] | `{"checkId": "CHK-e8a8d6a2-4175-4225-ac42-d48eded69108", "employeeId": "HBRC-FULL-0903-CT-EXTRA", "status": "flagged", "createdAt": "2026-09-03T01:54:04.746Z", "` |
| POST employeeId numeric | `POST /background-checks` | 500 | N | 142.5 |  | `{"message": "Internal Server Error"}` |
| POST malformed json | `POST /background-checks` | 400 | Y | 70.0 |  | `{"error": "Bad Request", "message": "Invalid JSON in request body", "statusCode": 400}` |
| POST empty body | `POST /background-checks` | 400 | Y | 122.7 |  | `{"error": "Bad Request", "message": "Missing required field: employeeId", "statusCode": 400}` |
| POST text/plain content-type | `POST /background-checks` | 201 | Y | 102.6 |  | `{"checkId": "CHK-46d291bf-3282-40ca-a8f3-3d7774a2e041", "employeeId": "HBRC-FULL-0903-CT-CT", "status": "pending", "createdAt": "2026-09-03T01:54:05.187Z", "mes` |
| POST very long employeeId | `POST /background-checks` | 201 | Y | 60.1 |  | `{"checkId": "CHK-d79e361d-9015-4227-9fa0-a091e068ec01", "employeeId": "HBRC-FULL-0903-XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX` |
| GET valid checkId | `GET /background-checks/CHK-e52ae328-b466-4454-b046-f56936ffe99f` | 503 | Y | 57.9 | 누락=['checkId', 'employeeId', 'firstName', 'lastName', 'dateOfBirth', 'status', 'criminalRecord', 'educationVerified', 'employmentVerified', 'creditScore', 'createdAt', 'completedAt'] / 명세외=['error', 'message', 'retryAfter', 'statusCode'] | `{"error": "Service Unavailable", "message": "The service is currently overloaded. Please retry after the specified time.", "retryAfter": 30, "statusCode": 503}` |
| GET nonexistent well-formed checkId | `GET /background-checks/CHK-00000000-0000-0000-0000-000000000000` | 500 | Y | 70.2 | 누락=[] / 명세외=[] | `{"error": "Internal Server Error", "message": "Service temporarily unavailable. Please try again later.", "statusCode": 500}` |
| GET malformed checkId | `GET /background-checks/not-a-check-id` | 404 | Y | 271.5 | 누락=[] / 명세외=[] | `{"error": "Not Found", "message": "Background check not found: not-a-check-id", "statusCode": 404}` |
| GET empty checkId (trailing slash) | `GET /background-checks/` | 404 |  | 60.3 |  | `{"error": "Not Found", "message": "Unknown route: GET /background-checks/", "statusCode": 404}` |
| GET list valid | `GET /background-checks?employeeId=HBRC-FULL-0903-CT-001` | 200 | Y | 561.0 | 누락=[] / 명세외=[] | `{"employeeId": "HBRC-FULL-0903-CT-001", "checks": [{"checkId": "CHK-826d3e80-e673-4080-b7aa-35ae1cb57ad9", "status": "flagged", "createdAt": "2026-09-03T01:53:5` |
| GET list unknown employeeId | `GET /background-checks?employeeId=HBRC-FULL-0903-NOBODY` | 500 | Y | 58.5 | 누락=['employeeId', 'checks', 'totalCount'] / 명세외=['error', 'message', 'statusCode'] | `{"error": "Internal Server Error", "message": "Service temporarily unavailable. Please try again later.", "statusCode": 500}` |
| GET list without employeeId | `GET /background-checks` | 400 | Y | 508.3 | 누락=[] / 명세외=[] | `{"error": "Bad Request", "message": "Missing required query parameter: employeeId", "statusCode": 400}` |
| GET list empty employeeId | `GET /background-checks?employeeId=` | 400 | Y | 315.7 | 누락=[] / 명세외=[] | `{"error": "Bad Request", "message": "Missing required query parameter: employeeId", "statusCode": 400}` |
| PUT /background-checks | `PUT /background-checks` | 404 |  | 49.8 |  | `{"message": "Not Found"}` |
| DELETE /background-checks | `DELETE /background-checks` | 404 |  | 39.0 |  | `{"message": "Not Found"}` |
| PATCH /background-checks | `PATCH /background-checks` | 404 |  | 46.5 |  | `{"message": "Not Found"}` |
| OPTIONS /background-checks | `OPTIONS /background-checks` | 204 |  | 53.0 |  |  |
| HEAD /background-checks | `HEAD /background-checks` | 404 |  | 90.8 |  |  |
| DELETE /background-checks/{id} | `DELETE /background-checks/CHK-e52ae328-b466-4454-b046-f56936ffe99f` | 404 |  | 54.8 |  | `{"message": "Not Found"}` |
| GET / | `GET /` | 404 |  | 37.4 |  | `{"message": "Not Found"}` |
| GET /health | `GET /health` | 404 |  | 55.5 |  | `{"message": "Not Found"}` |

### 헤더 관측

- 응답 헤더 이름 출현 횟수: `{"date": 38, "content-type": 37, "content-length": 37, "connection": 38, "apigw-requestid": 38}`
- `Retry-After` 헤더 관측: []
- 본문 `retryAfter` 필드 관측: [{"tag": "GET valid checkId", "status": 503, "retryAfter": 30}]

### 목록 항목 필드 대조

| 프로브 | 차이 |
|---|---|
| GET list valid | {"missing_from_response": [], "not_in_spec": []} |
| GET list valid | {"missing_from_response": [], "not_in_spec": []} |

## 6. 전체 요청 상태코드 (모든 phase 합산)

| phase | HTTP | 건수 |
|---|---|---|
| contract | 201 | 11 |
| contract | 400 | 12 |
| contract | 500 | 3 |
| contract | 503 | 1 |
| contract | 404 | 9 |
| contract | 200 | 1 |
| contract | 204 | 1 |
| duplicate | 500 | 2 |
| duplicate | 201 | 11 |
| duplicate | 200 | 1 |
| lifecycle | 201 | 10 |
| lifecycle | 500 | 7 |
| lifecycle | 503 | 6 |
| lifecycle | 200 | 10 |
| latency | 200 | 67 |
| latency | 500 | 74 |
| latency | 503 | 59 |
| concurrency | 200 | 98 |
| concurrency | 500 | 82 |
| concurrency | 503 | 70 |
| concurrency | 201 | 250 |

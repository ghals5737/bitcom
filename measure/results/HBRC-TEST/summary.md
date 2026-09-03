# Background Check API 실측 결과 (원자료)

- run-id: `HBRC-TEST`  / base-url: `https://54capvm12g.execute-api.ap-northeast-2.amazonaws.com`
- 실행 시각: 2026-09-03T00:46:49.804442+00:00 ~ 2026-09-03T00:55:25.651516+00:00 (UTC)
- 클라이언트 타임아웃: 30.0s / employeeId 접두어: `HBRC-TEST`
- 총 HTTP 요청 수: 129

> 이 문서는 측정값만 담는다. 해석과 결정(타임아웃/재시도/폴링)은 MEASUREMENTS.md 에 별도 작성.

## 1. GET /background-checks/{checkId} 응답 지연 (순차)

| 구분 | 표본 n | min(ms) | p50 | p90 | p95 | p99 | max | mean |
|---|---|---|---|---|---|---|---|---|
| 전체 | 40 | 66.9 | 132.3 | 30035.3 | 30037.2 | 30064.3 | 30064.3 | 6605.1 |
| HTTP 200 | 16 | 126.9 | 557.2 | 17355.0 | 28734.4 | 28734.4 | 28734.4 | 5157.6 |
| HTTP 500 | 16 | 66.9 | 74.4 | 98.8 | 127.9 | 127.9 | 127.9 | 80.4 |
| HTTP 503 | 2 | 71.6 | 76.5 | 76.5 | 76.5 | 76.5 | 76.5 | 74.0 |
| HTTP None | 6 | 30031.9 | 30037.2 | 30064.3 | 30064.3 | 30064.3 | 30064.3 | 30041.1 |
| 전반부 | 20 | 66.9 | 83.2 | 30031.9 | 30037.2 | 30037.2 | 30037.2 | 6548.2 |
| 후반부 | 20 | 71.6 | 195.7 | 30035.6 | 30064.3 | 30064.3 | 30064.3 | 6661.9 |

### 상태코드 분포

| HTTP | 건수 | 비율 |
|---|---|---|
| 200 | 16 | 40.0% |
| 500 | 16 | 40.0% |
| 503 | 2 | 5.0% |
| None | 6 | 15.0% |

표본 n = 40

### 200 이외 응답 본문

| 본문(앞 200자) | 건수 |
|---|---|
| `null` | 6 |
| `{"error": "Internal Server Error", "message": "Service temporarily unavailable. Please try again later.", "statusCode": 500}` | 16 |
| `{"error": "Service Unavailable", "message": "The service is currently overloaded. Please retry after the specified time.", "retryAfter": 30, "statusCode": 503}` | 2 |

Retry-After 헤더 관측: 0건 

클라이언트 측 오류(타임아웃 등): {"timeout: The read operation timed out": 6}

## 2. 같은 employeeId 로 POST 반복

| 항목 | 값 |
|---|---|
| employeeId | `HBRC-TEST-DUP-001` |
| 반복 횟수 n | 5 |
| 요청 간격 | 0.5s |
| 상태코드 분포 | {"201": 5} |
| 응답 checkId 개수 / 고유 개수 | 5 / 5 |
| 초기 status 분포 | {"flagged": 1, "pending": 4} |
| 목록 totalCount (반복 전 → 후) | None → 5 |
| 같은 id, 다른 이름/생년월일 POST | HTTP 201 |
| 그 후 목록 totalCount |  |
| POST 지연 | p50=116.4 p95=1377.8 max=1377.8 (n=5) |

## 3. pending → 최종 상태까지 걸리는 시간

| 항목 | 값 |
|---|---|
| 생성 건수 n | 4 |
| 폴링 간격 / 타임아웃 | 2.0s / 90s |
| POST 상태코드 | {"201": 4} |
| 초기 status 분포 | {"pending": 2, "clear": 2} |
| 최종 status 분포 | {"flagged": 2, "clear": 2} |
| 최종 도달 / 타임아웃 | 4 / 0 |
| 상태 전이 패턴 | {"pending -> flagged": 2, "clear": 2} |
| estimatedCompletionSeconds 값 | [] |
| 폴링 HTTP 코드 합계 | {"500": 5, "200": 3} |
| 체크당 폴링 횟수 | [3, 0, 0, 5] |

| 소요 시간(초) | n | min | p50 | p95 | max | mean |
|---|---|---|---|---|---|---|
| 클라이언트 기준, 전체 | 4 | 0.0 | 0.0 | 33.1 | 33.1 | 12.66 |
| 클라이언트 기준, 초기 pending 만 | 2 | 17.54 | 33.1 | 33.1 | 33.1 | 25.32 |
| 서버 createdAt→completedAt | 2 | 17.486 | 33.084 | 33.084 | 33.084 | 25.29 |

### 최종 응답 본문 필드 대조 (명세 GET 200 스키마 기준)

| 필드 차이 | 건수 |
|---|---|
| `{"missing_from_response": [], "not_in_spec": []}` | 2 |
| `{"missing_from_response": ["firstName", "lastName", "dateOfBirth", "criminalRecord", "educationVerified", "employmentVerified", "creditScore", "completedAt"], "not_in_spec": ["message"]}` | 2 |

### 최종 응답 값 분포

| 필드 | 값 분포 |
|---|---|
| status | {"flagged": 2, "clear": 2} |
| criminalRecord | {"False": 1, "True": 1} |
| educationVerified | {"True": 1, "False": 1} |
| employmentVerified | {"True": 2} |
| creditScore | {"good": 1, "fair": 1} |

## 4. 동시 요청 수 변화

| 동시 수 | 종류 | n | wall(s) | rps | p50 | p95 | p99 | max | 상태코드 | Retry-After 값 | 오류 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 1 | GET | 10 | 49.01 | 0.2 | 77.8 | 18213.2 | 18213.2 | 18213.2 | {"200": 4, "500": 4, "503": 2} | - | - |
| 5 | GET | 10 | 30.57 | 0.33 | 1533.8 | 30046.8 | 30046.8 | 30046.8 | {"200": 4, "500": 3, "503": 1, "None": 2} | - | {"timeout: The read operation timed out": 2} |
| 10 | GET | 10 | 30.06 | 0.33 | 698.4 | 30056.0 | 30056.0 | 30056.0 | {"200": 3, "500": 5, "None": 2} | - | {"timeout: The read operation timed out": 2} |

### 200/201 이외 응답 본문 (전 레벨 합계)

| 본문(앞 200자) | 건수 |
|---|---|
| `{"error": "Service Unavailable", "message": "The service is currently overloaded. Please retry after the specified time.", "retryAfter": 30, "statusCode": 503}` | 3 |
| `{"error": "Internal Server Error", "message": "Service temporarily unavailable. Please try again later.", "statusCode": 500}` | 12 |
| `null` | 4 |

## 5. 명세(swagger.yaml) 대조 프로브

프로브 수 n = 38

| 프로브 | 요청 | HTTP | 명세에 있는 코드? | 지연(ms) | 응답 필드 차이 (누락 / 명세 외) | 본문(앞 160자) |
|---|---|---|---|---|---|---|
| POST valid | `POST /background-checks` | 201 | Y | 403.4 | 누락=['estimatedCompletionSeconds'] / 명세외=[] | `{"checkId": "CHK-7ae94ff1-4644-499a-a346-9727d9fd836b", "employeeId": "HBRC-TEST-CT-001", "status": "flagged", "createdAt": "2026-09-03T00:46:50.040Z", "message` |
| POST missing employeeId | `POST /background-checks` | 400 | Y | 125.9 | 누락=[] / 명세외=[] | `{"error": "Bad Request", "message": "Missing required field: employeeId", "statusCode": 400}` |
| POST missing firstName | `POST /background-checks` | 400 | Y | 99.0 | 누락=[] / 명세외=[] | `{"error": "Bad Request", "message": "Missing required field: firstName", "statusCode": 400}` |
| POST missing lastName | `POST /background-checks` | 400 | Y | 96.6 | 누락=[] / 명세외=[] | `{"error": "Bad Request", "message": "Missing required field: lastName", "statusCode": 400}` |
| POST missing dateOfBirth | `POST /background-checks` | 400 | Y | 82.7 | 누락=[] / 명세외=[] | `{"error": "Bad Request", "message": "Missing required field: dateOfBirth", "statusCode": 400}` |
| POST dob=null | `POST /background-checks` | 400 | Y | 85.4 | 누락=[] / 명세외=[] | `{"error": "Bad Request", "message": "Missing required field: dateOfBirth", "statusCode": 400}` |
| POST dob='' | `POST /background-checks` | 400 | Y | 84.2 | 누락=[] / 명세외=[] | `{"error": "Bad Request", "message": "Missing required field: dateOfBirth", "statusCode": 400}` |
| POST korean names | `POST /background-checks` | 201 | Y | 122.6 | 누락=['estimatedCompletionSeconds'] / 명세외=[] | `{"checkId": "CHK-ff841716-1b50-4a3d-adea-ce2edaedbb7a", "employeeId": "HBRC-TEST-CT-KO", "status": "clear", "createdAt": "2026-09-03T00:46:50.873Z", "message": ` |
| POST korean fullname in lastName | `POST /background-checks` | 400 | Y | 80.9 |  | `{"error": "Bad Request", "message": "Missing required field: firstName", "statusCode": 400}` |
| POST empty strings | `POST /background-checks` | 400 | Y | 86.2 | 누락=[] / 명세외=[] | `{"error": "Bad Request", "message": "Missing required field: firstName", "statusCode": 400}` |
| POST compound surname | `POST /background-checks` | 201 | Y | 94.1 | 누락=['estimatedCompletionSeconds'] / 명세외=[] | `{"checkId": "CHK-af0dab43-04e9-491a-b909-bd1f77f63713", "employeeId": "HBRC-TEST-CT-NG", "status": "pending", "createdAt": "2026-09-03T00:46:51.150Z", "message"` |
| POST dob slash format | `POST /background-checks` | 201 | Y | 120.2 |  | `{"checkId": "CHK-77db273e-4066-45fb-a32d-a9375c084fe8", "employeeId": "HBRC-TEST-CT-DOB", "status": "clear", "createdAt": "2026-09-03T00:46:51.257Z", "message":` |
| POST dob future | `POST /background-checks` | 201 | Y | 150.3 |  | `{"checkId": "CHK-655626c6-7f71-419c-8424-013368e65c27", "employeeId": "HBRC-TEST-CT-DOB", "status": "pending", "createdAt": "2026-09-03T00:46:51.396Z", "message` |
| POST dob invalid day | `POST /background-checks` | 201 | Y | 124.5 |  | `{"checkId": "CHK-c77b9a77-5073-4f2c-8c6c-682f197e0c52", "employeeId": "HBRC-TEST-CT-DOB", "status": "pending", "createdAt": "2026-09-03T00:46:51.520Z", "message` |
| POST dob not a date | `POST /background-checks` | 201 | Y | 102.1 |  | `{"checkId": "CHK-554cb2ab-e1c1-4fa8-8008-e641848add06", "employeeId": "HBRC-TEST-CT-DOB", "status": "clear", "createdAt": "2026-09-03T00:46:51.656Z", "message":` |
| POST dob datetime | `POST /background-checks` | 201 | Y | 117.7 |  | `{"checkId": "CHK-86f5942d-3d66-4b0b-a167-534230e4f26d", "employeeId": "HBRC-TEST-CT-DOB", "status": "pending", "createdAt": "2026-09-03T00:46:51.753Z", "message` |
| POST extra fields | `POST /background-checks` | 201 | Y | 101.8 | 누락=['estimatedCompletionSeconds'] / 명세외=[] | `{"checkId": "CHK-e81cd285-03a8-46bc-8eae-9f5c08793df4", "employeeId": "HBRC-TEST-CT-EXTRA", "status": "flagged", "createdAt": "2026-09-03T00:46:51.868Z", "messa` |
| POST employeeId numeric | `POST /background-checks` | 500 | N | 176.2 |  | `{"message": "Internal Server Error"}` |
| POST malformed json | `POST /background-checks` | 400 | Y | 85.8 |  | `{"error": "Bad Request", "message": "Invalid JSON in request body", "statusCode": 400}` |
| POST empty body | `POST /background-checks` | 400 | Y | 91.8 |  | `{"error": "Bad Request", "message": "Missing required field: employeeId", "statusCode": 400}` |
| POST text/plain content-type | `POST /background-checks` | 201 | Y | 141.0 |  | `{"checkId": "CHK-ea3be542-f20c-41d8-9f14-016c34d64347", "employeeId": "HBRC-TEST-CT-CT", "status": "pending", "createdAt": "2026-09-03T00:46:52.348Z", "message"` |
| POST very long employeeId | `POST /background-checks` | 201 | Y | 160.0 |  | `{"checkId": "CHK-f0227cd7-fcde-48da-a88e-6a8fc12414c1", "employeeId": "HBRC-TEST-XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX` |
| GET valid checkId | `GET /background-checks/CHK-7ae94ff1-4644-499a-a346-9727d9fd836b` | 503 | Y | 77.7 | 누락=['checkId', 'employeeId', 'firstName', 'lastName', 'dateOfBirth', 'status', 'criminalRecord', 'educationVerified', 'employmentVerified', 'creditScore', 'createdAt', 'completedAt'] / 명세외=['error', 'message', 'retryAfter', 'statusCode'] | `{"error": "Service Unavailable", "message": "The service is currently overloaded. Please retry after the specified time.", "retryAfter": 30, "statusCode": 503}` |
| GET nonexistent well-formed checkId | `GET /background-checks/CHK-00000000-0000-0000-0000-000000000000` | 404 | Y | 26060.4 | 누락=[] / 명세외=[] | `{"error": "Not Found", "message": "Background check not found: CHK-00000000-0000-0000-0000-000000000000", "statusCode": 404}` |
| GET malformed checkId | `GET /background-checks/not-a-check-id` | 404 | Y | 261.1 | 누락=[] / 명세외=[] | `{"error": "Not Found", "message": "Background check not found: not-a-check-id", "statusCode": 404}` |
| GET empty checkId (trailing slash) | `GET /background-checks/` | 404 |  | 85.7 |  | `{"error": "Not Found", "message": "Unknown route: GET /background-checks/", "statusCode": 404}` |
| GET list valid | `GET /background-checks?employeeId=HBRC-TEST-CT-001` | 200 | Y | 20052.1 | 누락=[] / 명세외=[] | `{"employeeId": "HBRC-TEST-CT-001", "checks": [{"checkId": "CHK-7ae94ff1-4644-499a-a346-9727d9fd836b", "status": "flagged", "createdAt": "2026-09-03T00:46:50.040` |
| GET list unknown employeeId | `GET /background-checks?employeeId=HBRC-TEST-NOBODY` | 500 | Y | 58.8 | 누락=['employeeId', 'checks', 'totalCount'] / 명세외=['error', 'message', 'statusCode'] | `{"error": "Internal Server Error", "message": "Service temporarily unavailable. Please try again later.", "statusCode": 500}` |
| GET list without employeeId | `GET /background-checks` | 500 | Y | 69.3 | 누락=[] / 명세외=[] | `{"error": "Internal Server Error", "message": "Service temporarily unavailable. Please try again later.", "statusCode": 500}` |
| GET list empty employeeId | `GET /background-checks?employeeId=` |  | N | 30029.9 | 누락=['error', 'message', 'statusCode'] / 명세외=[] | timeout: The read operation timed out |
| PUT /background-checks | `PUT /background-checks` | 404 |  | 56.5 |  | `{"message": "Not Found"}` |
| DELETE /background-checks | `DELETE /background-checks` | 404 |  | 47.9 |  | `{"message": "Not Found"}` |
| PATCH /background-checks | `PATCH /background-checks` | 404 |  | 55.2 |  | `{"message": "Not Found"}` |
| OPTIONS /background-checks | `OPTIONS /background-checks` | 204 |  | 49.4 |  |  |
| HEAD /background-checks | `HEAD /background-checks` | 404 |  | 49.2 |  |  |
| DELETE /background-checks/{id} | `DELETE /background-checks/CHK-7ae94ff1-4644-499a-a346-9727d9fd836b` | 404 |  | 50.2 |  | `{"message": "Not Found"}` |
| GET / | `GET /` | 404 |  | 58.1 |  | `{"message": "Not Found"}` |
| GET /health | `GET /health` | 404 |  | 47.9 |  | `{"message": "Not Found"}` |

### 헤더 관측

- 응답 헤더 이름 출현 횟수: `{"date": 37, "content-type": 36, "content-length": 36, "connection": 37, "apigw-requestid": 37}`
- `Retry-After` 헤더 관측: []
- 본문 `retryAfter` 필드 관측: [{"tag": "GET valid checkId", "status": 503, "retryAfter": 30}]

### 목록 항목 필드 대조

| 프로브 | 차이 |
|---|---|
| GET list valid | {"missing_from_response": [], "not_in_spec": []} |

## 6. 전체 요청 상태코드 (모든 phase 합산)

| phase | HTTP | 건수 |
|---|---|---|
| contract | 201 | 11 |
| contract | 400 | 10 |
| contract | 500 | 3 |
| contract | 503 | 1 |
| contract | 404 | 10 |
| contract | 200 | 1 |
| contract | None | 1 |
| contract | 204 | 1 |
| duplicate | 500 | 2 |
| duplicate | 201 | 6 |
| duplicate | 200 | 1 |
| lifecycle | 201 | 4 |
| lifecycle | 500 | 5 |
| lifecycle | 200 | 3 |
| latency | None | 6 |
| latency | 500 | 16 |
| latency | 200 | 16 |
| latency | 503 | 2 |
| concurrency | 503 | 3 |
| concurrency | 200 | 11 |
| concurrency | 500 | 12 |
| concurrency | None | 4 |

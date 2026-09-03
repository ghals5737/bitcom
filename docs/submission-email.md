제목: [과제 제출] 사내 직원 관리 시스템 — 황호민

조재석 CSO님, 안녕하세요.
비트컴퓨터 개발자 채용 과제 "사내 직원 관리 시스템(Internal Employee Portal)"을 제출드립니다.

■ 제출물 1. 동작하는 애플리케이션

- 배포 URL: https://bitcom.pages.dev
- 관리자 계정: ADMIN-001 / admin1234!
- 일반 직원 계정: EMP-001 / emp1234!
- 소스코드: https://github.com/ghals5737/bitcom

배포는 제출 후에도 유지하겠습니다. 그 외 시드 직원(EMP-002~010)은 임시 비밀번호 상태라 첫 로그인 시 비밀번호 변경을 요구합니다(임시 비밀번호는 저장소 README에 있습니다).

■ 제출물 2·3·4 (모두 저장소 루트에 있습니다)

- MEASUREMENTS.md — Background Check API 실측 결과와 그로부터 정한 타임아웃·재시도·폴링·중단 조건
- DECISIONS.md — 명세가 정하지 않은 4개 항목에 대한 설계 판단 (4개 모두 작성)
- AI_LOG.md — AI 협업 기록. A(거절/변경 4건), B(잘못 만들어 고친 7건), C(설명하기 어려운 부분)
- README.md — 구성도, 구현 범위(넣은 것 / 뺀 것과 이유), 알려진 제약

■ AI 대화 로그 보는 법

Claude Code(Anthropic)를 사용했고, 대화 전문은 저장소 log/ 폴더에 있습니다.

- log/conversation.md — 사람이 읽는 전체 대화. 상단에 턴별 요약 표(시작 시각, 소요 시간, 도구 호출 수, 질문)가 있고, 그 아래 턴마다 제 질문과 AI 답변, 접힌 도구 호출 내역이 이어집니다. GitHub에서 그대로 열어 보시면 됩니다.
- log/conversation.jsonl — 같은 내용을 턴 단위 JSON으로 정제한 파일
- log/raw/*.jsonl — Claude Code가 남기는 원본 transcript (가공 없음)

로그는 Claude Code의 Stop 훅이 답변이 끝날 때마다 log/parse_transcript.py를 실행해 자동으로 갱신했으므로, 작업 중 누락된 구간 없이 처음부터 끝까지 담겨 있습니다.

■ MEASUREMENTS를 어떻게 측정했는지

- 도구: measure/bg_measure.py (Python 표준 라이브러리만 사용). 해석 없이 수치와 원본 응답만 기록하도록 만들었고, 판단은 MEASUREMENTS.md에 따로 적었습니다.
- 다섯 단계로 측정했습니다.
  1) 명세 대조 프로브 38건 — 필수 필드 누락, 생년월일 형식 변형, 한글 이름, 잘못된 JSON, 미정의 메서드 등을 보내 상태코드·응답 필드·헤더를 swagger.yaml과 대조
  2) 같은 employeeId로 POST 10회 반복
  3) 체크 10건 생성 후 2초 간격 폴링으로 pending→최종 소요 시간 측정 (클라이언트 관측 시간과 서버 createdAt→completedAt 둘 다)
  4) GET 200회 순차 호출로 지연 p50/p90/p95/p99/max와 상태코드 분포
  5) 동시 1/5/10/20/50 × 각 50건으로 GET과 POST의 지연·상태코드 변화
- 실행 결과는 measure/results/HBRC-FULL-0903/ 에 있습니다. requests.jsonl(요청 1건 = 1줄, 총 785건), lifecycle.jsonl(체크별 폴링 기록), summary.md(집계 표). MEASUREMENTS.md의 모든 수치는 이 파일에서 그대로 가져왔고 표본 수를 함께 적었습니다.
- 다른 응시자의 데이터와 섞이지 않도록 employeeId에 실행마다 고유 접두어를 붙였습니다.

■ 온사이트 인터뷰 참고

코드 구조는 backend/README.md(도메인별 Controller/Service/Repository, 주입 규칙, 트랜잭션 경계)와 docs/planning.md(기능·비기능 결정 기록)에 정리해 두었습니다. AI_LOG.md의 C 항목에 제가 원리를 확인하지 못한 부분을 미리 적었습니다.

시간 들여 검토해 주셔서 감사합니다.

황호민 드림
hhm2hbrc@gmail.com

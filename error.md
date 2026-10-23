# 오류 및 수정 기록

## 2026-10-23 — Flyway 마이그레이션 실패

### 증상

- `./gradlew :app:bootRun`에서 Java 컴파일은 통과했지만 Spring Boot 시작 중 종료됐다.
- MySQL 연결은 성공했고, Flyway 검증 단계에서 다음 오류가 발생했다.
  - `Migrations have failed validation`
  - `Detected failed migration to version 5 (optimize latest message lookup)`
- 따라서 이번 오류는 WebSocket 빈이나 DB 연결 실패가 아니라 Flyway의 마이그레이션 이력 검증 실패다.
- 로그의 `/etc/zshenv` 경고와 `ChatWebSocketHandler` deprecated API 경고는 이 시작 실패의 직접 원인이 아니다.

### 원인과 수정

1. 읽음 상태 테이블을 추가하면서 새 마이그레이션을 `V5`로 만들었다. 그런데 테스트 DB에는 이미 `optimize latest message lookup`이라는 실패한 V5 기록이 있었다. 마이그레이션 버전을 겹치게 한 것이 잘못이었다.
2. 읽음 상태 마이그레이션을 `V6__conversation_read_receipts.sql`로 이동했다.
3. 프로젝트 문서에 기록되어 있던 기존 V5 목적과 DB 인덱스 상태를 대조했다. MySQL Workbench 캡처의 `messages_by_conversation`은 `(conversation_id, created_at DESC)`였고, 문서상 목표는 `id`까지 포함하는 복합 인덱스였다.
4. 기존 V5를 저장소에 복구해 `(conversation_id, created_at DESC, id DESC)` 조회 인덱스를 추가하도록 `V5__optimize_latest_message_lookup.sql`을 추가했다. 읽음 상태 테이블은 V6에서 생성한다.
5. V5가 기존 `messages_by_conversation` 인덱스를 삭제하려다 MySQL 오류 1553으로 실패했다. 이 인덱스가 `conversation_id` 외래 키에 필요했기 때문이다. 기존 인덱스는 보존하고 `messages_by_conversation_latest`라는 별도 복합 인덱스를 생성하도록 V5를 수정했다.

### DB 복구 시 주의

- 코드의 버전 충돌은 수정했지만, DB의 실패한 V5 이력은 자동으로 지워지지 않는다. 따라서 Flyway `repair`가 필요하다.
- Repair 전에 **테스트 DB `chatting_test`**에 연결했는지 확인하고, `flyway_schema_history`에서 버전 5의 `success` 상태를 확인한다.
- 실패 상태가 확인된 뒤 repair를 실행하고 앱을 다시 시작하면 V5 인덱스 변경과 V6 읽음 테이블 생성이 진행된다.
- V5는 조회 인덱스를 추가하며 기존 인덱스를 유지한다. 메시지 행은 변경하지 않는다. DB나 Docker 볼륨을 초기화할 필요는 없다.
- DB repair 및 재시작은 이 기록을 작성하는 시점에는 아직 실행하지 않았다. 재시작 성공을 확인한 상태는 아니다.

### 두 번째 오류: V5에서 인덱스 삭제 거부

실패 메시지:

```text
Failed to execute script V5__optimize_latest_message_lookup.sql
Error Code: 1553
Cannot drop index 'messages_by_conversation': needed in a foreign key constraint
```

V5가 `DROP INDEX messages_by_conversation`에서 실패했다. `conversation_id` 외래 키가 기존 인덱스를 사용 중이므로 제거할 수 없다. Workbench 캡처에서도 기존 인덱스가 `(conversation_id, created_at DESC)`로 남아 있음을 확인했다. 따라서 V5를 기존 인덱스 삭제 없이 별도 복합 인덱스를 추가하는 방식으로 수정했다. 이 실패 기록도 Flyway 이력에서 `success = 0`으로 남으므로, 수정된 파일을 다시 적용하기 전에 테스트 DB에서 실패한 V5 이력을 다시 정리해야 한다.

조회용 SQL:

```sql
SELECT installed_rank, version, description, script, success
FROM flyway_schema_history
WHERE version = '5';

SHOW INDEX FROM messages;
```

이 프로젝트에는 Flyway Gradle 플러그인이 없어 `./gradlew flywayRepair` 명령은 제공되지 않는다. Flyway CLI가 설치되어 있지 않다면, MySQL Workbench에서 **테스트 DB에 연결된 것을 확인한 뒤** 실패한 V5 행만 제거하는 방식으로 복구할 수 있다. 아래 `DELETE`는 `success = 0`인 버전 5 이력만 대상으로 한다. 실행 전 `SELECT DATABASE()`와 조회 결과를 확인한다.

```sql
USE chatting_test;
SELECT DATABASE();

SELECT installed_rank, version, description, script, success
FROM flyway_schema_history
WHERE version = '5';

DELETE FROM flyway_schema_history
WHERE version = '5' AND success = 0;

SELECT installed_rank, version, description, script, success
FROM flyway_schema_history
WHERE version = '5';
```

실패 행만 제거한 뒤 앱을 다시 시작하면 Flyway가 저장소의 V5 및 V6 마이그레이션을 실행한다. V5는 추가 인덱스만 만들고 기존 인덱스와 메시지 행을 삭제하지 않는다. 이 절차는 마이그레이션 이력만 정리하며 운영 DB에서는 실행하지 않는다.

## 2026-10-23 — 채팅 기능 추가 중 확인한 동작

- 메시지 테이블에 100개 저장 제한은 없었다. `/api/conversations/{id}/messages`의 첫 조회가 최근 100개만 반환하는 UI 로딩 제한이었다. 오래된 메시지도 DB에는 유지된다.
- 대화방 참여자 이름을 확인하는 API와 참여자 목록 UI를 추가했다.
- 프로필 이미지가 없는 경우 빈 이미지 대신 단색 배경을 표시하도록 화면을 수정했다.
- 읽음 시각 테이블과 메시지별 미읽음 참여자 수 표시를 추가했다. 읽음 정보는 V6 마이그레이션으로 저장한다.

## 2026-10-23 — 접속 중 인원이 실제 접속 상태와 달라지는 문제

### 증상과 원인

- 상대방이 브라우저나 앱을 닫았는데도 접속 인원 수가 바로 줄지 않았다.
- 기존 구현은 `@Scheduled` 작업에서 WebSocket 세션의 `isOpen()`만 확인하고 Redis 접속 기록의 만료 시각을 계속 연장했다. 네트워크가 끊겨도 TCP/WebSocket 세션이 열린 상태로 남으면 이 기록이 계속 살아 있을 수 있었다.
- 스케줄러는 누락된 상태가 아니었다. `RedisChatConfig`에 이미 `@EnableScheduling`이 설정되어 있다. `ChatApplication`에 같은 어노테이션을 중복 추가했다가 이를 확인 후 제거했다.

### 수정

- 서버가 열린 WebSocket 연결에 15초마다 presence ping을 보내고 브라우저가 pong으로 응답하도록 했다.
- 마지막 pong이 30초 이상 도착하지 않으면 해당 세션을 닫고 Redis 접속 기록을 제거한다. Redis의 presence 유효 시간은 45초이며, 열린 대화방의 인원 수를 주기적으로 다시 계산한다.
- 화면에서 `실시간 접속 N명`과 `참여자 보기`를 별도 요소로 나눴다.
- 참여자 보기는 Redis 접속 상태가 아니라 DB의 `conversation_members`를 조회한다. 즉, 앱을 닫은 사람은 실시간 접속 인원에서 제외되지만 `나가기`를 누르지 않았다면 대화방 참여자 목록에는 남는다.

### 검증 상태

- 코드와 문서 수정 후 `git diff --check` 통과.
- 실제 두 브라우저 연결/종료 및 heartbeat 만료 동작은 아직 실행해 확인하지 않았다.

## 검증 기록

- `git diff --check` 통과.
- Flyway 오류가 해결된 상태에서의 애플리케이션 재시작 및 기능 테스트는 아직 확인되지 않았다.

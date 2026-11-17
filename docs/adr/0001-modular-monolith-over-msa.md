# ADR 0001 — 단일 Spring Boot 배포와 도메인 패키지 분리

- 상태: 채택

## 맥락

GatherIt은 개인이 개발하고 운영하는 서비스입니다. 그룹, 일정, 투표, 알림은 같은 사용자와 데이터에 의존합니다. 여러 팀의 독립 배포를 위한 조직 경계는 없습니다.

서비스를 나누면 데이터 소유권, 서비스 간 인증, 통신 실패, 배포 순서, 관측과 롤백을 함께 설계해야 합니다. 현재는 이 비용을 부담하기보다 한 애플리케이션의 트랜잭션 안에서 기능을 유지하기로 했습니다. 이 결정은 운영 용량이나 대규모 트래픽을 검증했다는 주장과는 별개입니다.

## 결정과 현재 구현

`auth`, `group`, `event`, `poll`, `notification`, `photo`, `ai` 등 도메인별 패키지를 사용하고, Spring Boot JAR 하나로 배포합니다. 여기서 모듈러 모놀리스라는 표현은 패키지 중심의 코드 구성 의도를 뜻합니다. 별도 Gradle 모듈, Spring Modulith 검증, ArchUnit 의존성 규칙으로 경계를 강제하지는 않습니다.

격리 수준은 부분마다 다릅니다.

- `PhotoStorage`는 인터페이스이며 로컬 디스크/S3 구현을 선택할 수 있습니다.
- `LlmExtractor`는 외부 추출 호출을 추상화합니다. 그러나 `AiScheduleService`는 일정 서비스와 저장소와 사용량 저장소를 직접 참조합니다.
- `RefreshTokenStore`는 현재 JPA/MySQL 영속 저장소입니다. 사용자 행과 refresh 행의 잠금에 의존하는 구체 클래스이며 Caffeine 세션 캐시가 아닙니다.
- `RateLimitFilter`와 초대 토큰 등 일부 상태는 Caffeine 인메모리입니다. 특히 rate limit은 필터 안에 카운터 구현이 있으므로 Redis를 바로 끼워 넣는 어댑터 구조로 검증된 상태가 아닙니다.

## 결과와 제약

하나의 배포와 DB 트랜잭션을 관리할 수 있지만, 특정 도메인만 독립적으로 배포하거나 확장할 수 없고, JVM 장애의 영향도 함께 받습니다. 패키지 간 직접 의존성도 있어 서비스 추출에는 코드와 데이터 계약을 다시 정리해야 합니다.

다중 인스턴스 전환 시 refresh 세션이 DB에 있다는 사실만으로 모든 준비가 끝나지는 않습니다. 인메모리 rate limit과 초대 상태, 스케줄러의 중복 실행, 푸시 전달과 재시도, 기존 버전과 새 버전의 동시 실행을 별도로 검토해야 합니다. 단일 프로세스의 `@Async`는 별도 작업 큐나 전달 보장을 제공하지 않습니다.

## 재검토 조건

도메인별 부하나 장애가 다른 API에 영향을 주거나 독립 배포 필요가 반복될 때 분리를 검토합니다. 먼저 타임아웃과 풀 및 큐의 한도를 점검하고, 관측으로 병목을 확인한 뒤, 데이터 소유권과 실패 복구 계약을 정해 분리 여부를 결정합니다. [AI 추출 분리 제안](0003-ai-service-msa-split.md)은 이 과정의 후보이며 아직 구현하지 않았습니다.

## 코드 근거

- [Gradle 설정](../../build.gradle)
- [RefreshTokenStore](../../src/main/java/com/gatherit/api/auth/service/RefreshTokenStore.java), [RateLimitFilter](../../src/main/java/com/gatherit/api/common/ratelimit/RateLimitFilter.java)
- [PhotoStorage](../../src/main/java/com/gatherit/api/photo/storage/PhotoStorage.java), [AiScheduleService](../../src/main/java/com/gatherit/api/ai/service/AiScheduleService.java)

# GatherIt Backend

GatherIt은 개인 일정과 그룹 일정을 한 달력에서 보고, 앱을 설치하지 않은 친구도 링크로 날짜 투표에 참여할 수 있는 서비스입니다. 이 저장소에는 Java/Spring Boot API, 테스트, 기술 사례를 담았습니다. 서비스 기획부터 데이터 모델과 API, 앱과 웹 구현, AWS 배포와 운영까지 담당했습니다.

[서비스 소개](https://www.gatherit.co.kr/), [App Store](https://apps.apple.com/kr/app/gatherit/id6767410085), [Google Play](https://play.google.com/store/apps/details?id=com.audora.gatherit)

## 해결한 문제

| 사례 | 구현과 검증 |
| --- | --- |
| [인증 갱신 응답 유실](docs/cases/auth-refresh-recovery.md) | 토큰 회전은 완료됐지만 응답이 끊겼을 때, 같은 갱신 요청의 재시도로 세션을 복구합니다. 로그인과 로그아웃의 경합은 사용자 행과 토큰 행의 잠금 순서로 다뤘습니다. |
| [Metaspace OOM 분석](docs/cases/metaspace-oom.md) | 반복 종료 기록과 JVM 계측을 대조해, 제한된 메모리 안에서 힙과 Metaspace 한도를 재배분했습니다. |
| [인덱스 실험 재검증](docs/cases/index-benchmark-recheck.md) | 첫 실험에서 테이블 통계 조건이 달랐던 점을 찾아 같은 조건으로 다시 측정하고, 근거가 약했던 복합 인덱스 추가를 철회했습니다. |

[설계 결정 기록](docs/adr/README.md), [AI 일정 추출과 등록](docs/ai-schedule-import.md), [알림 폴링과 푸시 전환](docs/perf/polling-to-fcm.md)도 정리했습니다.

## 서버 구조

```text
iOS / Android / Web
        │ HTTPS
Spring Boot REST API
        ├── JPA / MySQL
        ├── 사진 저장: 로컬 디스크 / S3
        ├── 푸시: APNs / FCM
        ├── 일정 후보 추출: Gemini
        └── Actuator / Micrometer
```

Java 17, Spring Boot, Spring Security, JPA/Hibernate를 사용합니다. 인증, 일정, 투표, 그룹, 사진 등은 `com.gatherit.api`의 도메인별 패키지에 있고 서버는 JAR 하나로 배포합니다.

- 일정 접근 범위는 연결된 그룹의 멤버십으로 판정합니다. [일정 조회 코드](src/main/java/com/gatherit/api/event/repository/EventRepository.java)
- 투표 확정과 일정 연결은 `@Version`과 `linkEventOnce`를 사용합니다. 테스트는 낙관적 락 충돌과 중복 연결 분기를 확인합니다. [투표 모델](src/main/java/com/gatherit/api/poll/entity/Poll.java), [투표 테스트](src/test/java/com/gatherit/api/poll/service/PollServiceTest.java)
- AI 일정 저장은 요청 키로 재시도 결과를 재사용합니다. 추출은 Gemini 키가 필요하며 키가 없는 로컬 서버는 추출 요청에 `503`을 반환합니다. [AI 일정 설계](docs/ai-schedule-import.md)

## 로컬 데모

### Docker Compose

Docker Engine과 Compose가 있다면 저장소 루트에서 실행합니다. `.env.demo`는 최초 한 번 만들고, 같은 MySQL 볼륨을 다시 사용할 때는 같은 값을 사용합니다.

```sh
if [ ! -e .env.demo ]; then
  (
    umask 077
    printf 'DEMO_DB_PASSWORD=%s\n' "$(openssl rand -hex 24)"
    printf 'DEMO_JWT_SECRET=%s\n' "$(openssl rand -hex 32)"
  ) > .env.demo
fi
docker compose --env-file .env.demo up --build -d
docker compose --env-file .env.demo ps
curl -fsS http://127.0.0.1:8080/actuator/health
```

API는 `127.0.0.1:8080`에 표시되고, MySQL은 Compose 네트워크 안에서 접근합니다. 다른 포트가 필요하면 `.env.demo`에 `DEMO_API_PORT=8084`를 추가한 뒤 health 주소도 맞춥니다. `docker compose --env-file .env.demo down`은 MySQL 볼륨을 보존합니다. 데모 사진은 컨테이너를 제거하면 사라집니다.

### JDK 17과 MySQL 8.4

로컬에서 실행 중인 MySQL 8.4에 빈 스키마와 TLS로 접속할 전용 사용자를 준비합니다. 다음 SQL의 비밀번호 자리에는 새 로컬 비밀번호를 입력합니다.

```sql
CREATE DATABASE gatherit CHARACTER SET utf8mb4;
CREATE USER 'gatherit'@'127.0.0.1' IDENTIFIED BY '<local DB password>' REQUIRE SSL;
GRANT ALL PRIVILEGES ON gatherit.* TO 'gatherit'@'127.0.0.1';
```

```sh
cp -n src/main/resources/application-local.example.yml src/main/resources/application-local.yml
printf 'Local MySQL password: '
read -r -s LOCAL_DB_PASSWORD
printf '\n'
export LOCAL_DB_PASSWORD
export JWT_SECRET="$(openssl rand -hex 32)"
./gradlew bootRun --args='--spring.profiles.active=local'
```

별도 터미널에서 `curl -fsS http://127.0.0.1:8080/actuator/health`를 실행하고 `UP`을 확인합니다. [Swagger UI](http://127.0.0.1:8080/swagger-ui.html)에서 회원가입과 로그인, 개인 캘린더 일정 생성과 조회를 실행할 수 있습니다. 발급된 access token은 Swagger의 인증 입력에 사용합니다. Windows에서는 설정 파일을 복사해 같은 환경 변수를 지정한 다음 `gradlew.bat`을 사용합니다.

## 테스트

```sh
./gradlew test check
python3 -m unittest discover -s deploy -p 'test_*.py'
```

- Mockito 단위 테스트와 H2 기반 JPA 트랜잭션 회귀 테스트가 있습니다. MySQL 통합 테스트는 Docker가 필요하며 로컬에 Docker가 없으면 해당 테스트 클래스는 생략됩니다.
- CI는 JDK 17 빌드, MySQL 통합 검사, JaCoCo 커버리지, JAR의 빌드 정보와 로컬 설정 파일 제외 여부를 확인합니다. Docker Compose 데모에서는 health, 인증이 필요한 경로, OpenAPI 경로와 실제 DB 테이블이 데모 계약과 맞는지 검사합니다. [기능 CI](.github/workflows/ci.yml)
- [MySQL 호환 검증](deploy/db-compat/README.md)은 샘플 스키마와 합성 데이터로 두 Hibernate 세대의 호환성을 검사합니다. [런타임 의존성 검사](.github/workflows/security.yml)는 패키징된 JAR의 라이브러리를 OSV와 대조합니다.

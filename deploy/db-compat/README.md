# MySQL 스키마 호환 검증

행 데이터가 없는 **21개 테이블의 데모 스키마**를 MySQL 8.4.9 컨테이너에 복원하고, Hibernate 6.5.3/Connector-J 8.3과 Hibernate 7.4.13/Connector-J 9.7을 별도 JVM으로 실행한다. 애플리케이션의 시작 작업이나 스케줄러는 실행하지 않는다.

## 실행

실제 MySQL 검증은 GitHub Actions에서만 허용한다. 로컬 Docker는 시작하지 않는다.

```sh
./gradlew classes --no-daemon
GATHERIT_RUN_DB_COMPAT=true DB_COMPAT_EXPECT_JAVA=17 \
  ./gradlew -p deploy/db-compat runCompatibility --no-daemon
```

GitHub가 제공하는 `GITHUB_ACTIONS=true`도 필요하다. `JAVA_HOME`의 JDK가 coordinator와 구/신 child JVM에 그대로 적용된다. `DB_COMPAT_JVM_TIMEZONE`은 `UTC` 또는 `Asia/Seoul`만 허용한다. JDK 17/UTC와 JDK 21/Asia/Seoul을 각각 검증한다. 컴파일 대상은 Java 17이다.

컨테이너는 전용 데모 스키마와 합성 자격증명만 사용하며 호스트 포트도 `127.0.0.1`에만 바인딩한다. child JDBC는 loopback 주소와 해당 스키마만 허용하며 직접 실행에도 CI 두 플래그를 검사한다. MySQL에서는 `create` 모드를 금지한다. 이 하네스는 Spring 프로필, 운영 데이터, 자격증명을 읽지 않는다. 호환 검증용 JDBC 시간대는 `Asia/Seoul`이며 JVM 시간대는 매트릭스별로 설정한다. SQL mode, 대소문자 규칙, charset, collation은 격리 컨테이너의 계약으로 검사한다. 컨테이너 TLS는 이 검증의 범위 밖이다.

서버 global SQL mode는 `NO_ENGINE_SUBSTITUTION`이다. Connector/J 8.3과 9.7은 기본 `jdbcCompliantTruncation` 처리로 JDBC session에 `STRICT_TRANS_TABLES`를 추가하므로 CLI session과 동일하다고 검사하지 않는다. 두 값은 별도로 보고하며 구/신 JDBC session은 두 모드의 집합과 정확히 일치해야 한다.

## 검증과 배포 경계

- 기존 ORM과 새 ORM 모두 21개 엔티티가 데모 스키마에서 `validate`를 통과해야 한다. 구조 fingerprint도 비교한다.
- 구 ORM으로 쓴 합성 계정, 해시, 일정, 그룹, 투표, 사진, 세션을 신 ORM에서 읽고, 신 ORM의 쓰기를 구 ORM이 다시 읽는다. 실제 DTO JSON, KST 자정/월/연도 경계, microsecond, 종일, RRULE/EXDATE, enum, null, Unicode를 비교한다.
- 실제 `RefreshTokenStore`와 DB 행 잠금으로 동시 갱신이 한 번만 회전하는지, 반대 ORM에서 세션을 이어받는지, grace 이후 동일 attempt 재시도가 기존 세션과 만료 시각을 보존하는지 확인한다. 시작 작업과 예약 작업은 실행하지 않는다.
- validate/read/session 단계는 JDBC에서 **DDL 실행 0개**, 구조 불변을 강제한다. 읽기 및 복구 단계는 합성 행 전체 fingerprint도 불변이어야 한다.
- `update` 두 단계는 core 검증 뒤 폐기 DB에서만 수행하는 **진단**이다. 최종 구조가 같아도 drop/recreate index 등 시도한 DDL 수와 hash를 남긴다. 이 진단은 운영 자동 DDL을 허용하는 근거가 아니다.

통과 시에도 gate는 **`compatible-only-with-ddl-auto-validate`**다. 실제 배포에서는 환경 변수, 명령행 인수, 설정의 우선순위까지 점검하여 `ddl-auto=validate`를 강제해야 한다. 운영 시험 기동은 하지 않으며, 실제 운영 데이터의 모든 변형이나 외부 OAuth/S3까지 증명한 결과가 아니다. core schema/data/session 호환이 실패하면 배포하지 않는다.

## 결과와 민감한 테스트 상태

- 업로드 가능: `build/reports/compatibility.json`, `build/reports/phases/*.json`.
- 업로드 금지: `build/private/` 전체. 합성 JWT, fixture password, child process 로그가 들어간다. 실제 운영 자격증명은 없지만 일반 보고서에 섞지 않는다.
- `schema/reference-structure.sql`에는 데이터, 호스트, DB명, definer, 현재 AUTO_INCREMENT 카운터가 없다. 테이블 열의 AUTO_INCREMENT 속성은 보존한다. 엔티티가 바뀌면 스키마 fixture도 검토한다.

Docker 없이 하네스 컴파일과 fixture의 기본 흐름만 점검하려면 `./gradlew -p deploy/db-compat runH2Smoke --no-daemon`을 사용한다. **H2 통과는 MySQL 호환 확인이나 배포 승인이 아니다.** 기존 JVM과 새 JVM의 파일 포맷 차이를 피하기 위해 이 smoke만 H2 2.2.224를 고정한다. 구 버전 의존성은 호환 검증을 위한 폐쇄된 child JVM에서만 사용하며 앱 JAR에 포함하지 않는다.

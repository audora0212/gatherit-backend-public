# syntax=docker/dockerfile:1
# ---- build stage : 소스 → 부팅 가능한 jar ----
FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
# 의존성 레이어 캐시 — gradle 설정만 먼저 복사해 받아두면, 소스만 바뀐 빌드에선 의존성 재다운로드를 건너뛴다.
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
# Windows 체크아웃 시 gradlew 가 CRLF → 컨테이너(Linux)에서 shebang(#!/usr/bin/env sh\r) 깨져 "not found".
# CR 제거 후 실행 권한 부여. (의존성 선다운로드로 소스만 바뀐 재빌드 캐시 활용)
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew && ./gradlew dependencies --no-daemon || true
COPY src ./src
RUN ./gradlew bootJar -x test --no-daemon

# ---- runtime stage : 슬림 JRE 만 ----
FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app
# 헬스체크용 curl + 비루트 사용자(보안)
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd -r -u 1001 spring
COPY --from=build /app/build/libs/gatherit-server-0.0.1-SNAPSHOT.jar app.jar
USER spring
EXPOSE 8080
# 컨테이너 메모리 제한의 일부를 JVM 힙 외 영역에 남긴다.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0"
HEALTHCHECK --interval=15s --timeout=3s --start-period=50s --retries=5 \
  CMD curl -fs http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1
ENTRYPOINT ["java", "-jar", "app.jar"]

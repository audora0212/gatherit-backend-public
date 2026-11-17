# ADR 0002 — APNs/FCM 직접 전송

- 상태: 채택

## 맥락

클라이언트는 Expo 기반 React Native 앱이지만, 알림 전송이 반드시 Expo Push Service를 거쳐야 하는 것은 아닙니다. 네이티브 기기 토큰을 등록하고 서버에서 플랫폼별 인증과 payload를 관리하는 경로를 선택했습니다.

선택의 목적은 중계 경로 하나를 줄이고 플랫폼별 전송과 오류 처리 방식을 직접 다루는 것입니다. Expo Push가 앱 아이콘이나 브랜드 표현을 일반적으로 지원하지 않는다는 주장은 이 결정의 근거로 사용하지 않습니다.

## 결정과 현재 구현

- `NotificationService`가 알림 레코드와 사용자 및 그룹별 허용 조건을 처리하고 APNs/FCM 발송기를 호출합니다. 방해금지와 사진 알림 중복 억제는 레코드 저장 후 푸시를 생략하는 조건입니다.
- iOS는 `ApnsService`가 `.p8` 키로 서명한 ES256 provider token을 사용해 APNs HTTP/2로 전송합니다.
- Android는 `FcmService`가 서비스 계정의 RSA 키로 RS256 JWT를 서명하고 OAuth access token으로 교환한 뒤 FCM HTTP v1을 호출합니다. 인증 토큰은 프로세스 안에서 캐시합니다.
- 각 발송기의 `sendToUser`는 `@Async`이고 저장된 `pushPlatform`으로 대상을 구분합니다. 전송 요청에는 타임아웃을 두며, 자격증명 경로가 없으면 발송기는 비활성으로 시작합니다.

`ExpoPushService` 클래스는 저장소에 남아 있지만 현재 `NotificationService`의 발송 경로는 APNs/FCM입니다. 기존 `expoPushToken` 필드명도 남아 있으며 이 경로에서의 값은 네이티브 토큰입니다.

## 결과와 제약

외부 중계를 거치지 않는 대신 키 보관, 인증 토큰 갱신, payload 형식, 제공자 응답과 기기 토큰 정리를 직접 유지해야 합니다. 푸시 수락 응답과 사용자의 실제 수신은 같은 보장이 아닙니다.

발송은 같은 JVM의 비동기 풀에서 수행됩니다. DB 커밋 이후 이벤트나 outbox 기반 전달이 아니므로 DB 롤백 및 프로세스 종료와 푸시 전송의 일관성을 보장하지 않습니다. 영속 재시도 큐, DLQ, exactly-once 전달도 구현되어 있지 않습니다. `@Async`가 메인 트랜잭션과 모든 실패 영향을 분리한다고 설명하지 않습니다.

알림 목록과 unread-count 폴링은 남아 있으며, 앱에서 원격 푸시 수신 시 캐시를 갱신합니다. 주기 완화와 과거 운영 관측은 [폴링 전환 기록](../perf/polling-to-fcm.md)에서 구분합니다.

## 코드 근거

- [NotificationService](../../src/main/java/com/gatherit/api/notification/service/NotificationService.java)
- [ApnsService](../../src/main/java/com/gatherit/api/notification/service/ApnsService.java), [FcmService](../../src/main/java/com/gatherit/api/notification/service/FcmService.java)
- [비동기 설정](../../src/main/java/com/gatherit/api/config/AsyncConfig.java), [풀 한도 설정](../../src/main/resources/application.yml)

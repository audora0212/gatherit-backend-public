# 보안

## 취약점 제보

저장소의 **Security** 탭에 **Report a vulnerability** 버튼이 표시되면 GitHub 비공개 제보를 사용해 주세요. 비공개 제보가 열려 있지 않으면 저장소 유지보수자에게 비공개 연락 방법을 요청해 주세요. 공개 이슈나 PR에는 재현 세부 정보, 접속 정보, 사용자 데이터, 토큰을 첨부하지 마세요.

제보에는 확인한 소스 버전과 영향 범위, 필요한 조건을 적어 주세요. 재현은 합성 데이터가 있는 로컬 또는 격리 환경을 기준으로 설명해 주세요.

## 의존성 검사

[Dependency security 워크플로](.github/workflows/security.yml)는 실행 JAR의 라이브러리를 Gradle이 해석한 Maven 좌표와 대조해 SBOM을 만들고, 공개 패키지 이름과 버전을 [OSV](https://google.github.io/osv.dev/api/)에서 조회합니다. 매핑 불일치, 검사 오류, 영향 받는 버전의 발견은 검사 실패로 처리합니다. 소스, JAR 내용, 설정 파일은 이 조회에 보내지 않습니다.

저장소 루트에서 서비스나 DB를 시작하지 않고 같은 검사를 실행할 수 있습니다. OSV 조회와 의존성 다운로드에는 네트워크가 필요합니다.

```sh
./gradlew --no-daemon -I security/runtime-inventory.init.gradle bootJar securityRuntimeInventory
python3 -m unittest discover -s security -p 'test_*.py' -v
python3 security/scan_jar.py --jar build/libs/gatherit-server-0.0.1-SNAPSHOT.jar --resolved build/security/resolved-runtime.json --output-dir build/security
```

이 검사는 검사 시점의 JAR 의존성을 다룹니다. 호스트 OS, JDK, 배포된 바이너리, 운영 설정이나 비밀값을 검증하는 절차는 아닙니다.

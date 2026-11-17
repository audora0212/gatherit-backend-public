# 백엔드 검증 도구

- [MySQL 호환 테스트](db-compat/README.md)는 데이터 없는 스키마와 합성 행으로 두 Hibernate 버전 사이의 읽기와 쓰기를 검사합니다.
- `inspect-build-provenance.py`는 빌드된 실행 JAR의 Git 커밋과 빌드 정보를 조회합니다.
- `inspect-artifact-safety.py`는 JAR의 로컬 설정과 자격증명 파일명 포함 여부를 검사합니다.
- `scan-public-secrets.py`는 Gitleaks로 현재 파일과 Git 이력을 검사하고 후보의 위치만 출력합니다.

```sh
./gradlew check bootJar
python3 -m unittest discover -s deploy -p 'test_*.py'
```

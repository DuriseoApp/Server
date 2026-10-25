# 설정 가이드

## 1. 맥에서 처음 한 번

1. 폴더 구조를 맞춰요. Docs와 같은 폴더 안에 있어야 `../Docs`를 읽을 수 있어요.
   ```
   Duriseo/
   ├── Docs/      (DuriseoApp/Docs)
   ├── Server/    (DuriseoApp/Server)
   └── Android/   (DuriseoApp/Android)
   ```
2. JDK 21이 필요해요 (`java -version`).
3. 테스트까지 맥에서 돌리려면 Docker Desktop이 필요해요 (Testcontainers). 없으면 컴파일만 확인하고 테스트는 GitHub Actions CI에 맡겨요.
4. `.env.example`을 `.env`로 복사해 값을 채워요. 값은 claude.ai 프로젝트 "둘이서 - 커플 앱"의 `config/environment.md`(공개 설정값)와 맥에 보관한 비밀값에서 가져와요. `.env`는 커밋되지 않아요.
5. Claude Code는 `Server` 폴더에서 `claude --add-dir ../Docs`로 열어요. 세션이 열릴 때 `scripts/sync-docs.sh`가 Docs를 최신으로 받아요.

## 2. 환경변수

| 이름 | 구분 | 설명 |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | 설정 | `dev` 또는 `prod` |
| `DB_URL`, `DB_USERNAME` | 설정 | PostgreSQL 접속 정보 |
| `DB_PASSWORD` | **비밀** | PostgreSQL 비밀번호 |
| `GOOGLE_WEB_CLIENT_ID` | 공개 설정 | Google 웹 애플리케이션 클라이언트 ID. ID 토큰 `aud` 검증 |
| `KAKAO_APP_ID` | 공개 설정 | 카카오 앱 ID. 토큰 `app_id` 검증 |
| `KAKAO_ADMIN_KEY` | **비밀** | 카카오 어드민 키. 탈퇴 시 연결 끊기 |
| `FIREBASE_PROJECT_ID` | 공개 설정 | Firebase 프로젝트 ID |
| `FIREBASE_CREDENTIALS_BASE64` | **비밀** | 서비스 계정 키 JSON을 base64로 (`base64 -i key.json \| tr -d '\n'`) |

토큰 서명 키 같은 값은 계정 단위(단위 1)에서 추가해요. 새 환경변수를 만들면 `.env.example`, 이 표, `application.yml`을 같은 PR에서 함께 고쳐요.

## 3. GitHub Secrets (배포용)

키트 3(배포)에서 GitHub Actions가 AWS 서버에 값을 넣을 때 써요. 그때 이름을 확정해 이 절을 채워요. 비밀값은 레포 Settings › Secrets and variables › Actions에만 넣어요.

## 4. 공개 레포 주의

이 레포는 공개예요. 커밋에 비밀값이 들어가면 지워도 기록에 남아요. 실수로 올렸으면 바로 그 키를 폐기하고 새로 발급해요.

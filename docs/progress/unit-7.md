# 단위 7: 프로필 사진 — 업로드·재인코딩·저장소 (user, storage)

> 브랜치: `feat/7-profile-image` · PR: (draft 때 채움) · 상태: 진행 중 (2026-11-13 범위 승인, 확인 필요 1~4 추천안대로)
> 기준: PRD v0.12 (FR-AUTH-07 AC3·AC5·AC7, NFR-FILE-01, NFR-PRIV-02, D-33·D-147·D-153), API.md 2장 R-11·3.1(`/me/profile-image`, `/users/{id}/profile-image`, `Me`·`MePartner`)·3.2(`SpaceMember`)·5장(413·415)·6장·8장, ERD `users.profile_image_key`·4.3·4.4
> Docs 기준 커밋: DuriseoApp/Docs main `cee0885`
> 앞 단위: 단위 6 머지됨 (main `d619d1b`)

## 요약

- 새 경로 3개: `PUT /me/profile-image`(multipart `file`), `DELETE /me/profile-image`(멱등), `GET /users/{id}/profile-image`(R-11, `image/jpeg` 스트리밍, 캐시 헤더)
- 응답 필드 추가: `Me`·`MePartner`·`SpaceMember`의 `profileImageVersion` (지금은 `hasProfileImage`만 있음)
- 재인코딩(API.md 8장): 실제 디코드로 형식 판정 → 10MB·25MP 검사 → 회전 적용 → 메타 제거 → 투명은 흰 배경 → 긴 변 512px JPEG
- 저장소 인터페이스: 로컬 디스크(로컬·개발 서버) / S3(운영). 새 패키지 `storage/`
- 교체·삭제는 DB 커밋 뒤 이전 파일 best-effort 삭제, 탈퇴는 `UserWithdrawn.profileImageKey`로 즉시 삭제, 정리 작업 8단계(미참조 파일, 24시간)
- SYNC `SPACE`/`UPDATED` (단위 6의 `SpaceUpdated` 재사용)
- 새 `ErrorCode` 없음(`IMAGE_TOO_LARGE`·`IMAGE_TYPE_NOT_ALLOWED`가 이미 있는지 확인, 없으면 API.md 5장대로 추가). 새 마이그레이션 없음. 새 환경변수 있음(저장소 종류·경로·버킷) → SETUP.md·`.env.example`·CloudFormation

## 확인 필요

| # | 빈 곳 | 추천안 | 근거 |
| --- | --- | --- | --- |
| 1 | `users.profile_image_key`에 무엇을 넣는지. ERD는 "객체 키 … 키는 uuid … 이 값이 `profileImageVersion`", API.md 8장은 "객체 키는 `profile/{userId}/{uuid}.jpg` … 이 uuid가 `profileImageVersion`" | 컬럼에는 **uuid만** 넣고, 객체 키는 `profile/{userId}/{uuid}.jpg`로 만든다. 컬럼 값이 그대로 `profileImageVersion`이다. 문구 정리는 Docs 후속 | ERD "이 값이 API의 `profileImageVersion`으로 나간다" |
| 2 | 개발 서버의 저장소 (API.md 8장 "개발 환경은 로컬 디스크, 운영 환경은 S3") | 로컬(맥)과 개발 서버(dev 앱)는 로컬 디스크(개발 서버는 Docker 볼륨), 운영(prod 앱)은 새 S3 버킷. CloudFormation에 버킷(공개 차단·SSE-S3·소유자 강제)과 EC2 역할 권한을 더하고, **스택 업데이트는 사람이** 해요 | API.md 8장, 서버 1대(DB-03) |
| 3 | 재인코딩 JPEG 품질 (문서에 없음) | 0.85. 설정값으로 둬요 | — |
| 4 | DB에는 키가 있는데 파일이 없을 때 `GET /users/{id}/profile-image` | 404 `NOT_FOUND` (사진 없음과 같게), warn 로그 | API.md 3.1 "사진이 없는 경우 … 404" |

## 범위

테스트 클래스(`user/`, `storage/` 아래). 이미지 테스트는 테스트 리소스에 작은 JPEG·PNG(투명·회전 EXIF)를 만들어 쓴다.

### 1. API

| ID | 내용 | 테스트 |
| --- | --- | --- |
| FR-AUTH-07 AC3, NFR-FILE-01 | `PUT /me/profile-image` 204. 새 uuid, 객체 저장 → `profile_image_key` 갱신(사용자 행 잠금) → 커밋 뒤 이전 파일 삭제 | `FR-AUTH-07 AC3 사진을 올리면 204이고 profileImageVersion이 새 값이며 이전 파일이 지워진다` |
| | `file` 없음 400 (`file`, `REQUIRED`) | `FR-AUTH-07 AC3 file이 없으면 400 VALIDATION_FAILED (file REQUIRED)` |
| FR-AUTH-07 AC7, D-147 | 10MB 초과 413 `IMAGE_TOO_LARGE`(multipart 한도 초과도 같은 코드), 25MP 초과 413, JPEG·PNG 아님·디코드 실패 415 `IMAGE_TYPE_NOT_ALLOWED`(확장자·Content-Type이 아니라 실제 디코드로) | `FR-AUTH-07 AC7 10MB를 넘으면 413 IMAGE_TOO_LARGE다` · `…AC7 25MP를 넘으면 413이다` · `…AC7 이름만 jpg인 GIF나 깨진 파일은 415다` |
| 8장 재인코딩 | 긴 변 512px(작으면 그대로), EXIF 회전 적용, 메타(위치 포함) 없음, 투명 → 흰 배경, 결과는 늘 JPEG | `NFR-FILE-01 큰 사진은 긴 변 512px JPEG로 저장한다` · `…회전 EXIF를 픽셀에 적용하고 메타를 남기지 않는다` · `…투명한 PNG는 흰 배경 JPEG가 된다` |
| FR-AUTH-07 AC5 | `DELETE /me/profile-image` 204, 멱등, 커밋 뒤 파일 삭제 | `FR-AUTH-07 AC5 사진을 지우면 파일과 키가 지워지고 다시 지워도 204다` |
| R-11 | `GET /users/{id}/profile-image`: 본인 또는 같은 활성 공간 구성원만, 아니면 404. 사진 없음 404. `Cache-Control: private, max-age=31536000, immutable`, `image/jpeg` | 허용: `R-11 본인과 같은 공간 상대는 사진을 받는다` / 거부: `R-11 다른 공간 사용자와 해제한 상대의 사진은 404다` · `R-11 사진이 없으면 404다` |
| 3.1·3.2 | `Me`·`MePartner`·`SpaceMember`에 `profileImageVersion`(uuid 또는 null) | `FR-AUTH-07 GET me와 GET space는 profileImageVersion을 준다` |
| 6장 | 사진 올리기·지우기 성공 때 커플 공간이면 상대에게 `SPACE`/`UPDATED`(멱등 삭제로 바뀐 게 없으면 없음) | `6장 커플 공간에서 사진을 바꾸면 상대에게 SPACE UPDATED가 간다` |
| 동시성 | 같은 사용자의 업로드 두 건이 겹쳐도 최종 키 하나, 고아 파일은 정리 작업이 회수 | `FR-AUTH-07 동시에 두 번 올려도 키는 하나이고 진 쪽 파일은 남지 않거나 정리 대상이다` |

### 2. 탈퇴·정리 (ERD 4.3·4.4)

| ID | 내용 | 테스트 |
| --- | --- | --- |
| ERD 4.3, NFR-PRIV-02 | 탈퇴 커밋 뒤 `UserWithdrawn.profileImageKey`의 파일 즉시 삭제(게스트 정리 포함) | `FR-AUTH-06 탈퇴하면 프로필 사진 파일을 바로 지운다` |
| ERD 4.4 8단계 | 저장소 목록에서 `profile_image_key`에 없고 마지막 수정이 24시간 지난 객체만 삭제 | `ERD 4.4 참조되지 않고 24시간 지난 사진 파일만 지운다` |

### 이월 (PLAN "단위 7")

| 할 일 | 확인 |
| --- | --- |
| 위 2의 탈퇴 파일 삭제, 정리 8단계 | 2 테스트 |
| 사진 변경 SYNC | 1의 6장 테스트 |
| `hasProfileImage`·`profileImageVersion`, 3개 경로 | 1 테스트 |

### 구현 메모

- 메모리(t4g.small 2GB, 앱 2개): 25MP 전체 디코드는 100MB 넘게 쓴다. 헤더로 크기만 먼저 읽어 413을 내고, 디코드는 `ImageReader` 축소 읽기(source subsampling)로 512px 근처에서만. 처리 동시 실행 수를 작게(예: 2) 제한
- 처리(디코드·인코딩)는 트랜잭션 밖, 키 갱신만 짧은 트랜잭션
- 라이브러리: 기본은 JDK ImageIO. EXIF 회전은 `metadata-extractor`, CMYK·일부 JPEG는 TwelveMonkeys `imageio-jpeg`가 필요하면 추가(진행 기록 "결정"에)
- multipart 한도 초과 예외를 `GlobalExceptionHandler`에서 413 `IMAGE_TOO_LARGE`로. Caddy 요청 크기 한도도 확인

## 결정

구현하면서 정한 것 중 문서에 없는 것. 문서에 있어야 할 결정이면 여기 적지 말고 작업을 멈추고 보고한다.

## 리뷰 기록

| 회차 | Critical | Major | 비고 |
| --- | --- | --- | --- |

## 남은 일

- 사람: CloudFormation 스택 업데이트(운영 사진 버킷), 운영 배포 뒤 업로드 확인

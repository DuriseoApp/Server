# 단위 7: 프로필 사진 — 업로드·재인코딩·저장소 (user, storage)

> 브랜치: `feat/7-profile-image` · PR: #13 · 상태: 진행 중 (2026-11-13 범위 승인, 확인 필요 1~4 추천안대로)
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

- **한도 값**: 10MB = 10 MiB(10 × 1024 × 1024, Spring `10MB`와 같음), 25MP = 25,000,000픽셀(십진수). 5000 × 5000은 통과, 5001 × 5000은 413. 원본 크기(10MB)를 먼저 보고, 그 뒤 헤더로 형식(JPEG·PNG)과 픽셀 수를 본다. 10MB가 넘는 비이미지는 415가 아니라 413이다(multipart 한도 초과도 413이라 같게 맞춤)
- **디코드 방식**: 헤더로 크기만 읽고 `ImageReader` source subsampling을 `긴 변 ÷ 512`(정수 나눗셈, 최소 1)로 걸어 긴 변 512~1023px로 읽은 뒤, 회전·bicubic 축소를 한 번의 `Graphics2D` 변환으로 흰 배경(`surface-0` = `#ffffff`) RGB에 그린다. 작은 사진은 늘리지 않는다
- **TwelveMonkeys `imageio-jpeg` 추가**: JDK JPEG 디코더는 CMYK·YCCK JPEG와 일부 색 프로파일이 든 JPEG를 못 읽어 415로 잘못 거부한다(일부 카메라 앱·스캐너 사진). 형식 판정은 "실제 디코드"가 기준이라 읽을 수 있는 JPEG는 읽어야 해서 추가. 의존성 한 개(`imageio-jpeg:3.12.0`, 전이로 imageio-core·metadata)이고 `ImageIO.scanForPlugins()`로 실행 jar에서도 등록한다. HEIC·WebP·GIF는 그대로 415(D-147)
- **EXIF 회전**: `metadata-extractor:2.19.0`으로 JPEG의 Orientation(1~8)만 읽는다. PNG의 `eXIf`는 무시. 읽을 수 없으면 회전 없음. 결과 JPEG는 메타데이터를 넘기지 않아 EXIF·GPS·ICC가 없다
- **품질·동시 실행 수**(확인 필요 3): `duriseo.image.jpeg-quality`(0.85), `duriseo.image.max-concurrent-processing`(2, 공정 Semaphore). 처리 중 실패해도 자리를 돌려준다
- **저장소 인터페이스**: `list(prefix, action)`이 콜백으로 하나씩 넘긴다(S3 페이지를 메모리에 모으지 않음). 로컬 디스크는 임시 파일에 쓴 뒤 옮기고, 빈 사용자 폴더는 지우지 않는다(같은 사용자의 동시 업로드와 겹치지 않게). 설정은 `duriseo.storage.type=local|s3`·`local-dir`·`s3-bucket`·`s3-region`(기본 `ap-northeast-2`), 환경변수 `STORAGE_TYPE`·`STORAGE_LOCAL_DIR`·`STORAGE_S3_BUCKET`. S3 클라이언트는 `s3`일 때만 만들고 자격 증명은 기본 체인(코드·설정에 키 없음)
- **AWS SDK v2**: `software.amazon.awssdk:s3:2.29.52`, 동기 클라이언트만 쓰므로 `netty-nio-client`는 제외(메모리). 버전은 CI에서 확인
- **키 규칙**(확인 필요 1): 컬럼 = `UUID.randomUUID()` 문자열, 객체 키 = `profile/{userId}/{uuid}.jpg`. 정리 작업은 컬럼 값을 키로 만들어 비교한다
- **교체·보상 삭제**: 이전 파일 삭제는 `ProfileImageFileObsolete` 이벤트를 `AFTER_COMMIT` 리스너가 받아 지운다(`ProfileImageFileListener`, `UserWithdrawn`도 같은 리스너가 받음. 게스트 정리·switch·Kakao 웹훅 탈퇴는 모두 `eraseInTransaction` → `UserWithdrawn`이라 같은 경로). 키 갱신 실패 때 방금 올린 파일 삭제는 DB가 그 uuid를 가리키지 않을 때만 한다(커밋 뒤 예외로 쓰이는 파일을 지우지 않게). 확인하지 못하면 지우지 않고 정리 작업에 맡긴다
- **업로드한 사용자가 그 사이 탈퇴**: 키 갱신이 401 `SESSION_INVALID`로 끝나고 파일은 지워진다
- **R-11**: `SpaceAccessPolicy.requireProfileImageAccess`가 단위 1부터 있어 새 메서드를 만들지 않고 그대로 쓴다. 경로의 `{id}`가 uuid가 아니면 400이 아니라 404(다른 경로와 같음). 파일 없음(확인 필요 4)은 404 + warn 로그(`profile image file is missing …`)
- **GET 응답**: `Cache-Control`은 문자열 그대로 `private, max-age=31536000, immutable`, `InputStreamResource`로 스트리밍(트랜잭션은 조회까지만, 파일은 트랜잭션 밖). `Accept`에 `image/jpeg`가 없는 요청은 지원하지 않는다(앱 이미지 로더는 `*/*`)
- **`GlobalExceptionHandler`**: `MaxUploadSizeExceededException` → 413 `IMAGE_TOO_LARGE`가 이미 있어 테스트만 더했다. `spring.servlet.multipart.max-file-size: 10MB`(`max-request-size: 11MB`)도 이미 있다. MockMvc는 이 한도를 거치지 않아 10MB 검사는 서비스(내용을 읽기 전 `file.size`)에서도 한다
- **배포**: Caddy에는 요청 크기 한도(`request_body max_size`)가 없다(기본 무제한)라 10MB 업로드를 막지 않아 Caddyfile은 그대로 둔다. `compose.app.yml`은 두 앱 모두 볼륨 `profile-images`(`/data/profile-images`)를 마운트하고(prod는 쓰지 않음) `Dockerfile`이 그 폴더를 `app` 소유로 만든다. `deploy.sh`는 prod에서 `PROFILE_IMAGE_BUCKET`이 Parameter Store에 없으면 **배포를 시작 전에 멈춘다**(스택 업데이트 전 prod 배포를 막음). dev는 영향 없음
- **CloudFormation**: `ProfileImageBucket`은 백업 버킷과 같은 방식(공개 차단, SSE-S3, BucketOwnerEnforced, TLS 아닌 요청 거부, `DeletionPolicy: Retain`)이고 만료 규칙은 없다(앱이 지운다. 미완료 멀티파트 업로드만 1일 뒤 정리). 서버 역할에 그 버킷의 `GetObject`·`PutObject`·`DeleteObject`와 `ListBucket`. `AWS::EC2::Instance`에는 메타데이터 hop limit 속성이 없어(`MetadataOptions`는 시작 템플릿에만 있음) 컨테이너가 EC2 역할을 쓰려면 hop limit ≥ 2가 필요한지 사람이 확인·변경한다(`docs/SETUP.md` 3.8)
- **빌드 확인 못 함**: 이 환경은 Gradle·Maven Central이 막혀 컴파일·테스트를 돌리지 못했다. AWS SDK·metadata-extractor·ImageIO API는 눈으로만 확인했다. CI(PR #13)에서 확인한다

## 리뷰 기록

| 회차 | Critical | Major | 비고 |
| --- | --- | --- | --- |

## 남은 일

- 사람: CloudFormation 스택 업데이트(운영 사진 버킷, 변경 세트에서 `Server` 교체가 `False`인지 확인 — `docs/SETUP.md` 3.8), 서버 메타데이터 hop limit 확인, 그 뒤 prod 배포와 업로드 확인
- 리드: CI(PR #13) 결과 확인. 컴파일·테스트를 이 환경에서 못 돌렸다
- Docs 후속: ERD "키는 uuid"와 API.md 8장 "객체 키는 `profile/{userId}/{uuid}.jpg`" 문구 정리(확인 필요 1)

# 단위 8: 운영 — 공지 팝업·업데이트 규칙·기기 정보

> 브랜치: `feat/8-app-ops` · PR: (링크) · 상태: 진행 중
> 기준: PRD v0.22 (FR-APP-01~03, D-176~D-183, NFR-SEC-04, NFR-PRIV-03), API.md 1장 공통 요청 헤더·인증 검사 순서, 2장 R-19·R-20, 3.1 `PUT /me/devices`, 3.7, 4.9, 5장, 6장, 8.1·8.2, ERD 3.1 `devices`·3.4·4.4·DB-21·DB-22

## 범위

| ID | 내용 | 테스트 |
| --- | --- | --- |
| ERD 3.4, DB-21, DB-22 | Flyway `V4__app_notices_update_rules_device_info.sql`: `app_notices`, `app_update_rules`, `devices.os_version`·`app_version`·`app_build` | `MigrationTest` (기존)에 V4 적용 확인 |
| FR-APP-01, D-176 | `GET /app-notices`: 지금 유효한 것만(기간), 모든 플랫폼, `created_at`·`id` 정렬, 로그인 없이 | `AppNoticeApiTest.FR_APP_01_공개조회는_지금_유효한_공지만_정렬해서_준다` |
| FR-APP-02, D-177 | `GET /app-update-rules`: 모든 규칙, 같은 정렬, 로그인 없이 | `AppUpdateRuleApiTest.FR_APP_02_공개조회는_모든_규칙을_준다` |
| D-176, D-177, D-183 | 관리자 `GET`·`PUT`(upsert)·`DELETE` 두 종류. 본문 검사(필드 길이, `id` 형식, `closable` 필수, `build.op` 조합, 정수 범위, `osVersion`, `startsAt`<`endsAt`, `https://`, 빈 문자열 `INVALID`, 제목·본문·이미지 조합 표). 틀린 필드를 모두 `details.fields`에, 중첩은 `build.op`·`build.values[0]` | `AdminAppNoticeApiTest.*`, `AdminAppUpdateRuleApiTest.*` (upsert, 404, 400 경우별) |
| R-19, NFR-SEC-04 | `X-Admin-Key` 상수 시간 비교, 환경변수 비면 `/admin/**` 전부 404, 키 없음·틀림 401 `ADMIN_KEY_INVALID`, 키 검사가 경로·본문 검사보다 먼저 | `AdminKeyTest.R_19_키가_맞으면_허용` / `R_19_키가_없거나_틀리면_401` / `R_19_환경변수가_비면_404` / `R_19_키가_틀리면_본문검사보다_먼저_401` |
| R-20, D-180, FR-APP-02 AC6 | `/api/v1` 아래 모든 요청에서 인증보다 먼저 `FORCE` 규칙 판단 → 426 `APP_VERSION_UNSUPPORTED` `details` `{ruleId,title,body,image,imageAlt}`. 예외: 공개 조회 3개, `/admin/**`. 헤더 없음·형식 틀림·범위 밖이면 통과, OS 헤더가 없으면 OS 조건 있는 규칙만 건너뜀, 여럿이면 정렬 첫 번째, 30초 캐시 | `ForceUpdateGuardTest.R_20_맞는_FORCE_규칙이면_426` / `R_20_로그인전_요청도_막는다` / `R_20_헤더가_없으면_통과` / `R_20_예외경로는_막지않는다` / `R_20_RECOMMEND는_막지않는다` / `R_20_OS헤더없으면_OS조건규칙만_건너뜀` / 판단 식 단위 테스트 (`EQ·LTE·GTE·RANGE·IN`) |
| D-181, API 8.2 | `POST /admin/app-notice-images` (PNG·JPEG·WebP 실제 디코드, 1MB, 원본 저장, 키 `notice/{uuid}.{ext}`, 201 `{url}`), `DELETE /admin/app-notice-images/{fileName}`, `GET /app-notice-images/{fileName}` (로그인 없이, `Content-Type`, `Cache-Control: public, max-age=86400`, `nosniff`, 형식 틀리면 저장소를 찾지 않고 404) | `AppNoticeImageApiTest.*` |
| ERD 4.4, API 8.1 | 프로필 사진 미참조 파일 정리가 `profile/` 접두어만 본다(공지 이미지를 지우지 않는다) | `CleanupJobTest.프로필사진_정리는_notice_접두어를_지우지_않는다` |
| FR-APP-03, D-182, DB-22 | `PUT /me/devices` 요청에 `osVersion`(≤20)·`appVersion`(≤50)·`appBuild`(0~2147483647) 선택 필드, 보낸 값만 저장(안 보낸 값 null로 덮어씀), 형식 400 | `DeviceApiTest.FR_APP_03_AC2_기기정보를_저장한다` / `…_안보낸값은_null` / `…_범위밖은_400` |
| API 6장 | 관리자 쓰기는 SYNC·알림을 보내지 않는다 | (보내는 코드가 없음을 리뷰로 확인) |
| API 5장 | 새 오류 코드 `APP_VERSION_UNSUPPORTED`(426), `ADMIN_KEY_INVALID`(401), 1.1 `details` 모양 | `GlobalExceptionHandler` 기존 테스트 형식 |

## 읽을 곳

| 묶음 | 문서·파일 | 위치 |
| --- | --- | --- |
| A DB | `../Docs/docs/ERD.md` | 3.1 `devices`(115행~), 3.4 운영(348행~), 4.4 정리 작업, 5장 DB-21·DB-22 |
| A DB | 마이그레이션 | 새 `src/main/resources/db/migration/V4__app_notices_update_rules_device_info.sql` (V1~V3는 고치지 않는다) |
| B 공개·관리자 API | `../Docs/docs/API.md` | 1장 21~29행(공통 헤더), 2장 R-19·R-20(123~124행), 3.7 전체(554~649행), 5장(800행~) 새 두 코드와 400·404·413·415 행, 1.1 `details` 표 |
| B | 새 패키지 | `appops/` (api, application, domain). 기존 형식은 `memo/` 패키지를 따른다 |
| C R-20 | `../Docs/docs/API.md` | 1장 36행(인증 검사 순서, R-20 먼저), 3.7 "업데이트 규칙 맞춤 판단", 4.9 7·8단계 |
| C | 고칠 코드 | `common/web/WebConfig.kt`(인터셉터 순서: 차단을 `AuthInterceptor`보다 먼저), `auth/api/AuthInterceptor.kt`(참고), `common/api/ErrorCode.kt`, `common/web/GlobalExceptionHandler.kt`(426 `details`) |
| D 이미지 | `../Docs/docs/API.md` | 3.7 이미지 3경로, 8.1 "미참조 파일 정리", 8.2 전체(898~907행) |
| D | 고칠 코드 | `storage/ProfileImageStorage.kt`(인터페이스·키), `storage/*Storage.kt`, `storage/StorageConfig.kt`, `job/CleanupJob.kt`·`job/CleanupStore.kt`(8단계 접두어), `config/DuriseoProperties.kt`, `application.yml`, `.env.example`, `docs/SETUP.md`, `deploy/bin/deploy.sh` |
| E 기기 | `../Docs/docs/API.md` | 3.1 `PUT /me/devices` 행(137행 부근), 5장 400 행 |
| E | 고칠 코드 | `user/api/RegisterDeviceRequest.kt`, `user/application/DeviceService.kt`, devices 엔티티 |

## 결정

범위 확인 때 리드가 정한 것 (문서에 없는 구현 방법):

1. **WebP 디코드**: Java ImageIO는 WebP를 읽지 못해 `com.twelvemonkeys.imageio:imageio-webp`(읽기 전용, BSD) 의존성을 더한다. PNG·JPEG는 기존 ImageIO.
2. **업로드 응답 `url`의 도메인**: 새 환경변수 `PUBLIC_BASE_URL`(예: `https://<API 도메인>`, 끝에 `/` 없음). `deploy.sh`가 dev·prod 각각 `https://$DOMAIN_DEV`·`https://$DOMAIN_PROD`로 넣는다(이미 있는 Parameter Store 값, 새 비밀값 아님). 비어 있으면 서버는 시작하고(경고 로그 한 줄), 이미지 업로드만 저장하지 않고 500이다(`url`을 만들 수 없어서).
3. **관리자 키 환경변수**: `DURISEO_ADMIN_API_KEY` (API.md R-19 이름 그대로). `deploy.sh`가 Parameter Store `/duriseo/secret/ADMIN_API_KEY`(optional)를 읽는다. 비어 있으면 관리자 API가 꺼진다.
4. **R-20 위치**: Spring MVC 인터셉터로 `AuthInterceptor`보다 먼저 등록한다(예외 → `GlobalExceptionHandler`가 1.1 형식으로 바꾼다). 대상은 `/api/v1/**`.
5. **multipart 한도**: 전역 10MB 그대로. 공지 이미지 1MB는 서비스에서 검사(413).

구현 중 정한 작은 것 (문서에 없는 구현 방법):

6. **관리자 본문은 `Map<String, Any?>`로 받는다**(`appops/application/BodyReader`, `AppOpsValidator`). DTO로 받으면 타입이 틀린 값이 JSON 읽기 오류(빈 `details.fields`)가 되어 필드 이름을 못 붙이기 때문이다. 같은 필드는 처음 틀린 이유 하나만 `details.fields`에 담는다.
7. **`build.min` > `build.max`는 `build.min`에 `INVALID`**(API.md가 필드를 지정하지 않음). `IN`의 빈 배열은 `build.values` `REQUIRED`, 51개 이상은 `TOO_LONG`. `osVersion`의 `min`·`max`가 둘 다 null이면 필드 `osVersion`에 `INVALID`(`min` > `max`는 거부하지 않는다, 문서에 없음).
8. **`PUT /me/devices`의 `appBuild`는 `Any?`로 받고 `@ValidAppBuild`로 검사**한다(문자열·소수도 `appBuild` `INVALID`로 답하려고). 저장은 기존 INSERT … ON CONFLICT에 컬럼을 더했고 null은 `cast(:x as …)`로 묶는다.
9. **R-19는 인터셉터**(`AdminKeyInterceptor`, 본문 읽기 전에 검사, 컨트롤러 메서드가 없는 `/admin/**` 경로도 키 검사 후 404). 인터셉터 순서: R-20 `order -20` → R-19 `-10` → 인증 `0`(`AuthWebConfig.AUTH_ORDER`). `AuthWebConfig.PUBLIC_PATHS`에 공개 조회 3개와 `/admin/**`를 더했다.
10. **R-20 캐시**: 규칙을 30초 메모리에 두고(`ForceUpdateRules`, `Clock` 기준) 같은 서버의 관리자 쓰기가 커밋되면 바로 비운다.
11. **공지 이미지 25MP 초과는 413 `IMAGE_TOO_LARGE`**(디코드 폭탄 방어, 프로필 사진과 같은 기준. 문서에 없는 방어 장치). 저장소는 `ProfileImageStorage`를 그대로 쓰고(이름은 그대로 둠) 키 규칙은 `NoticeImageKeys`. S3 `put`은 키 확장자로 `Content-Type`을 정한다.
12. **정리 작업은 이미 `profile/`만 본다**(`CleanupJob`이 `ProfileImageKeys.PREFIX`로 `list`). 코드 변경 없이 테스트만 더했다.

## 다음 단계용 메모

- 만든 것: `appops/{api,application,domain}`, `V4__app_notices_update_rules_device_info.sql`, `storage/NoticeImageInspector.kt`·`NoticeImageKeys`(`ProfileImageStorage.kt` 안), `user/api/ValidAppBuild.kt`·`DeviceInfo`(`DeviceService.kt` 안).
- 핵심 클래스: `AppOpsValidator`(본문 검사), `AppNoticeService`·`AppUpdateRuleService`(upsert, 쓰기 신호 없음), `ForceUpdateRules`(맞춤 판단 `matches`, 30초 캐시), `ForceUpdateGuardInterceptor`(R-20)·`AdminKeyInterceptor`(R-19)·`AppOpsWebConfig`(순서), `AppNoticeImageService`.
- 설정: `duriseo.admin.api-key`(`DURISEO_ADMIN_API_KEY`), `duriseo.public-base-url`(`PUBLIC_BASE_URL`). 테스트 값은 `src/test/resources/application.properties`. 환경변수를 바꾼 컨텍스트 테스트는 `@SpringBootTest(properties=…)` 클래스로 나눴다(`AdminKeyDisabledTest`, `AppNoticeImageNoBaseUrlTest`).
- 테스트 이름: 진행 기록 범위 표의 `AdminKeyTest.R_19_환경변수가_비면_404`는 컨텍스트가 달라 `AdminKeyDisabledTest`에 있다.
- **컴파일·테스트를 이 환경에서 못 돌렸다**(Gradle 없음). CI가 첫 컴파일이다. 의심 지점: (1) `AppUpdateRule.buildValues`의 `@JdbcTypeCode(SqlTypes.ARRAY)` + `List<Int>`와 `ddl-auto: validate`, (2) `DeviceService`의 `cast(:x as varchar/integer)`에 null 바인딩, (3) 테스트의 1x1 WebP 고정 바이트(`TINY_WEBP`)와 `imageio-webp:3.12.0` 좌표, (4) `/admin/nothing`처럼 핸들러 없는 경로에서 인터셉터가 도는지(`AdminKeyTest.R_19_키가_틀리면_본문검사보다_먼저_401`), (5) Jackson 3에서 `Map<String, Any?>` 본문의 정수가 `Int/Long/BigInteger`로 오는지.
- 업로드가 10MB를 넘는 multipart는 파싱이 인터셉터보다 먼저라 키가 틀려도 413이 나올 수 있다(그 밖은 키 검사가 먼저).

## 리뷰 기록

| 회차 | Critical | Major | 비고 |
| --- | --- | --- | --- |

## 남은 일

- 사용자: Parameter Store `/duriseo/secret/ADMIN_API_KEY` 등록 (긴 무작위 문자열). 등록 전에는 관리자 API가 꺼져 있다.

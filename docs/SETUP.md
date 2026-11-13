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
| `KAKAO_APP_ID` | 공개 설정 | 카카오 앱 ID. 토큰 `app_id` 검증, 연결 해제 웹훅 `app_id` 검증 |
| `KAKAO_ADMIN_KEY` | **비밀** | 카카오 어드민 키. 탈퇴 시 연결 끊기, 연결 해제 웹훅 인증(`Authorization: KakaoAK …`) |
| `FIREBASE_PROJECT_ID` | 공개 설정 | Firebase 프로젝트 ID |
| `FIREBASE_CREDENTIALS_BASE64` | **비밀** | 서비스 계정 키 JSON을 base64로 (`base64 -i key.json \| tr -d '\n'`) |
| `STORAGE_TYPE` | 설정 | 프로필 사진 저장소. `local`(맥·개발 서버, 기본) 또는 `s3`(운영). AWS 서버는 배포 스크립트가 dev 앱에 `local`, prod 앱에 `s3`를 넣어요 (API.md 8장) |
| `STORAGE_LOCAL_DIR` | 설정 | `local`일 때 사진 폴더. 맥 기본값 `./data/profile-images`(커밋 안 됨), 개발 서버는 Docker 볼륨 `/data/profile-images` |
| `STORAGE_S3_BUCKET` | 공개 설정 | `s3`일 때 사진 버킷 이름(Parameter Store `/duriseo/config/PROFILE_IMAGE_BUCKET`). S3 접근 키는 만들지 않아요: AWS 서버의 EC2 역할이 권한을 줘요 |
| `JWT_SECRET` | **비밀** | 토큰 서명 키. AWS 서버는 배포 스크립트가 환경마다 만들어 넣어요(`openssl rand -hex 32`, 64자). 앱은 문자열을 UTF-8 그대로 키로 쓰고, 32바이트 미만이거나 비어 있으면 시작하지 않아요 |

새 환경변수를 만들면 `.env.example`, 이 표, `application.yml`, `deploy/bin/deploy.sh`를 같은 PR에서 함께 고쳐요.

## 3. AWS 서버 준비 (처음 한 번)

서버 한 대에 개발 앱, 운영 앱, PostgreSQL, HTTPS 앞단(Caddy)을 Docker로 올려요. AWS 자원은 CloudFormation 템플릿 `deploy/aws/duriseo-stack.yml` 하나로 한 번에 만들어요. 운영 방법은 `docs/DEPLOY.md`를 봐요.

만들어지는 것: EC2 t4g.small 1대(고정 IP), 운영 DB 백업 버킷(S3, 30일 보관), 운영 프로필 사진 버킷(S3, 공개 차단·암호화), 이미지 저장소(ECR), GitHub Actions 배포 역할(OIDC, 비밀 키 없음), 설정값(Parameter Store `/duriseo/config/*`). 비용은 한 달 약 20~21달러예요 (서버 15, 고정 IP 3.6, 디스크 1.8, 나머지 몇 센트).

### 3.1 DuckDNS 도메인 2개

1. https://www.duckdns.org 에 GitHub나 Google 계정으로 로그인해요.
2. sub domain 칸에 이름을 넣고 add domain을 눌러 두 개를 만들어요. 예: `duriseo-api`(운영), `duriseo-dev-api`(개발). 이미 쓰인 이름이면 다른 이름으로 해요.
3. IP는 3.3에서 넣어요. 지금은 비워 둬도 돼요.

### 3.2 CloudFormation 스택 만들기

1. 레포의 `deploy/aws/duriseo-stack.yml`을 맥에 받아 둬요 (GitHub에서 파일 열고 Download raw file).
2. AWS 콘솔 오른쪽 위 리전이 **서울(ap-northeast-2)**인지 확인해요.
3. CloudFormation › 스택 생성 › 새 리소스 사용(표준) › 템플릿 파일 업로드 › 받은 파일 선택 › 다음.
4. 스택 이름 `duriseo`, 파라미터를 채워요.

   | 파라미터 (콘솔 표시) | 값 |
   | --- | --- |
   | Server size | `t4g.small` (그대로) |
   | OS image | 그대로 |
   | Prod API domain | 3.1의 운영 이름 + `.duckdns.org` |
   | Dev API domain | 3.1의 개발 이름 + `.duckdns.org` |
   | Certificate notice email, Google web client ID, Kakao app ID, Firebase project ID | claude.ai 프로젝트 `config/environment.md` |
   | 나머지 | 그대로 |

   템플릿 글자는 영어로만 써요. 한국어를 쓰면 콘솔에 `???`로 보여요.

5. 다음 › 다음 › 맨 아래 "AWS CloudFormation에서 IAM 리소스를 생성할 수 있음을 승인합니다"에 체크 › 제출.
6. 5~10분 뒤 상태가 `CREATE_COMPLETE`가 되면 **출력** 탭을 열어요.

### 3.3 출력값 옮기기

1. `ServerPublicIp` → DuckDNS의 두 도메인 current ip 칸에 넣고 update ip.
2. `DeployRoleArn` → GitHub 레포 Settings › Secrets and variables › Actions › New repository secret, 이름 `AWS_DEPLOY_ROLE_ARN`.

### 3.4 GitHub 배포 환경

Settings › Environments에서 두 개를 만들어요.

| 환경 | 설정 |
| --- | --- |
| `production` | Required reviewers에 본인 추가 (운영 배포 전에 승인 버튼이 생겨요). Deployment branches and tags › Selected branches and tags › `main` |
| `development` | Deployment branches and tags › Selected branches and tags › `main` |

### 3.5 비밀값 (Parameter Store)

비밀값은 GitHub이나 문서에 두지 않고 AWS Parameter Store에만 둬요. 서버가 배포할 때 읽어요. 카카오 어드민 키는 단위 1부터 앱이 써요(비어 있으면 탈퇴 때 카카오 연결 끊기를 건너뛰고, 카카오 연결 해제 웹훅이 모두 401이에요). Firebase 키는 푸시를 만드는 단위 6 전에 넣으면 돼요.

Systems Manager › 파라미터 스토어 › 파라미터 생성: 계층 **표준**, 유형 **SecureString**, KMS 키 소스 **내 현재 계정**(`alias/aws/ssm`).

| 이름 | 값 |
| --- | --- |
| `/duriseo/secret/KAKAO_ADMIN_KEY` | 카카오 어드민 키 |
| `/duriseo/secret/FIREBASE_SERVICE_ACCOUNT_JSON` | Firebase 서비스 계정 키 JSON 파일 내용 전체 (base64 하지 않고 그대로) |

DB 비밀번호와 토큰 서명 키(`JWT_SECRET`)는 서버가 처음 배포할 때 스스로 만들어요. 사람이 알 필요가 없어요.

### 3.6 첫 배포 확인

처음 서버를 만든 뒤 한 번만 해요. 이미 배포돼 있으면 건너뛰어요.

1. GitHub Actions › 배포 › Run workflow › 환경 `dev` › Run (이후로는 main 머지마다 자동이에요).
2. 끝나면 브라우저에서 `https://<개발 도메인>/actuator/health`를 열어 `{"status":"UP"...}`이 나오면 성공이에요. 인증서는 첫 요청 때 자동으로 받아요 (DuckDNS IP가 맞아야 해요).

### 3.7 GitHub Secrets 정리

| 이름 | 내용 |
| --- | --- |
| `AWS_DEPLOY_ROLE_ARN` | 3.3의 배포 역할 ARN. 이것 하나만 있으면 돼요. 서버 ID, 이미지 저장소 주소 같은 값은 배포 때 Parameter Store에서 읽어요 |

### 3.8 기존 스택에 프로필 사진 버킷 더하기 (단위 7, 사람이 해요)

스택을 이미 만들어 둔 상태에서 단위 7(프로필 사진)을 운영에 올리려면 템플릿에 새로 들어간 **프로필 사진 버킷**(`ProfileImageBucket`)과 서버 역할의 버킷 권한(`s3:GetObject`·`PutObject`·`DeleteObject`·`ListBucket`), 설정값 `/duriseo/config/PROFILE_IMAGE_BUCKET`을 스택에 반영해야 해요. **스택 업데이트는 에이전트가 하지 않고 사람이 해요.** 이 일을 하기 전에 prod를 배포하면 배포 스크립트가 "PROFILE_IMAGE_BUCKET 이 없습니다"라며 멈춰요(운영 앱은 그대로예요). dev 배포는 영향이 없어요.

1. 최신 `deploy/aws/duriseo-stack.yml`을 받아요 (3.2의 1번과 같아요).
2. CloudFormation › `duriseo` 스택 › 업데이트 › **기존 템플릿 바꾸기** › 템플릿 파일 업로드 › 다음. 파라미터는 모두 **이전 값 사용**(그대로)이에요.
3. 끝까지 가서 **바로 실행하지 말고 변경 세트로 먼저 봐요** (`docs/DEPLOY.md` 5장). 바뀌는 것은 `ProfileImageBucket`, `ProfileImageBucketPolicy`, `ParamProfileImageBucket` 추가와 `ServerRole` 수정뿐이어야 해요. **`Server`(EC2) 행이 보이고 교체(Replacement)가 `True`이면 실행하지 말고** 멈춰요. 서버가 새로 만들어지면 DB 데이터가 없어져요.
4. 실행하고 `UPDATE_COMPLETE`가 되면 출력 `ProfileImageBucketName`과 Parameter Store의 `/duriseo/config/PROFILE_IMAGE_BUCKET` 값이 같은지 봐요.
5. 컨테이너가 EC2 역할의 권한을 받으려면 서버의 메타데이터 응답 hop limit이 2 이상이어야 해요. 콘솔 Session Manager에서:
   ```bash
   sudo -i
   export AWS_REGION=ap-northeast-2
   id=$(aws ssm get-parameter --name /duriseo/config/INSTANCE_ID --query Parameter.Value --output text)
   aws ec2 describe-instances --instance-ids "$id" --query 'Reservations[].Instances[].MetadataOptions.HttpPutResponseHopLimit'
   # 1이면 2로 올려요 (재부팅 불필요)
   aws ec2 modify-instance-metadata-options --instance-id "$id" --http-put-response-hop-limit 2 --http-tokens required
   ```
6. prod를 배포하고(Actions › 배포 › `prod`) 앱에서 사진을 올려 봐요. 버킷에 `profile/<사용자ID>/<uuid>.jpg`가 생기고, `docker logs duriseo-prod-app-1`에 `AccessDenied`가 없으면 돼요.

## 4. 공개 레포 주의

이 레포는 공개예요. 커밋에 비밀값이 들어가면 지워도 기록에 남아요. 실수로 올렸으면 바로 그 키를 폐기하고 새로 발급해요.

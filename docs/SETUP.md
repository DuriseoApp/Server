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
| `STORAGE_CLEANUP_ENABLED` | 설정 | `s3`일 때 정리 작업 8단계(DB가 가리키지 않는 사진 파일 삭제)를 돌릴지. 기본 `false`이고 배포 스크립트가 **prod 앱에만** `true`를 넣어요. 맥·dev가 운영 버킷을 가리켜도 운영 사진을 지우지 않게 하는 보호 장치예요. 로컬 디스크는 이 값과 관계없이 돌아요 |
| `AWS_EC2_METADATA_DISABLED` | 설정 | AWS SDK 환경변수. 배포 스크립트가 dev 앱에 `true`를 넣어 dev 컨테이너가 서버 IAM 역할을 쓰지 못하게 해요 (dev는 로컬 저장소라 AWS가 필요 없어요) |
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
2. **현재 서버의 OS 이미지(AMI)를 고정해 둬요.** 템플릿의 `LatestAmiId`는 SSM 파라미터 형식이라 스택을 업데이트할 때마다 "최신 AMI"로 다시 읽혀요. "이전 값 사용"을 골라도 마찬가지라 `Server`(EC2) 교체가 나올 수 있어요. 지금 서버가 쓰는 AMI ID를 담은 **내 SSM 파라미터**를 만들고, 업데이트 때 `LatestAmiId`에 그 파라미터 이름을 넘겨요 (템플릿은 바꾸지 않아요). 콘솔 Session Manager 또는 AWS CLI에서:
   ```bash
   export AWS_REGION=ap-northeast-2
   id=$(aws ssm get-parameter --name /duriseo/config/INSTANCE_ID --query Parameter.Value --output text)
   ami=$(aws ec2 describe-instances --instance-ids "$id" --query 'Reservations[].Instances[].ImageId' --output text)
   aws ssm put-parameter --name /duriseo/ami/current --type String --value "$ami"
   ```
   (이 파라미터는 스택이 만든 것이 아니라 사람이 만든 것이에요. 서버를 일부러 새로 만들 때만 새 AMI로 고쳐요.)
3. CloudFormation › `duriseo` 스택 › 업데이트 › **기존 템플릿 바꾸기** › 템플릿 파일 업로드 › 다음. 파라미터는 `OS image`(`LatestAmiId`)만 값을 **`/duriseo/ami/current`**로 바꾸고 나머지는 **이전 값 사용**(그대로)이에요.
4. 끝까지 가서 **바로 실행하지 말고 변경 세트로 먼저 봐요** (`docs/DEPLOY.md` 5장). 바뀌는 것은 `ProfileImageBucket`, `ProfileImageBucketPolicy`, `ParamProfileImageBucket` 추가와 `ServerRole` 수정뿐이어야 해요. **`Server`(EC2) 행이 보이고 교체(Replacement)가 `True`이면 실행하지 말고** 멈춰요. 서버가 새로 만들어지면 DB 데이터가 없어져요.
5. 실행하고 `UPDATE_COMPLETE`가 되면 출력 `ProfileImageBucketName`과 Parameter Store의 `/duriseo/config/PROFILE_IMAGE_BUCKET` 값이 같은지 봐요.
6. 컨테이너가 EC2 역할의 권한을 받으려면 서버의 메타데이터 응답 hop limit이 2 이상이어야 해요. 먼저 지금 값을 봐요:
   ```bash
   id=$(aws ssm get-parameter --name /duriseo/config/INSTANCE_ID --query Parameter.Value --output text)
   aws ec2 describe-instances --instance-ids "$id" --query 'Reservations[].Instances[].MetadataOptions'
   ```
   **`HttpPutResponseHopLimit`이 이미 2이고 `HttpTokens`가 `required`이면(Amazon Linux 2023 기본값이에요) 할 일이 없어요.** 1이면 아래로 올려요 (재부팅 불필요). 이 명령은 `--http-tokens required`(IMDSv2만 허용)도 함께 정하니, 지금 값이 `optional`이면 그것도 바뀌는 점을 알고 실행해요 (배포 스크립트는 IMDSv2를 써서 영향이 없어요).
   ```bash
   aws ec2 modify-instance-metadata-options --instance-id "$id" --http-put-response-hop-limit 2 --http-tokens required
   ```
   hop limit이 2이면 서버의 **모든 컨테이너**가 서버 역할(S3 사진 버킷, Parameter Store 읽기, ECR 받기 등)을 받을 수 있어요. 위험과 대책은 아래 3.9예요.
7. prod를 배포하고(Actions › 배포 › `prod`) 앱에서 사진을 올려 봐요. 버킷에 `profile/<사용자ID>/<uuid>.jpg`가 생기고, `docker logs duriseo-prod-app-1`에 `AccessDenied`가 없으면 돼요.

### 3.9 프로필 사진 메모리 (사람이 확인해요)

- 컨테이너 메모리 한도는 dev 512MB, prod 640MB예요(`deploy/bin/deploy.sh`). 사진 처리(디코드·인코딩)는 한 번에 **1건**만 해요(`duriseo.image.max-concurrent-processing`, 기본 1). 원본은 처리 자리를 얻은 뒤에 디스크(multipart 임시 파일)에서 바로 읽어 힙에 통째로 올리지 않아요.
- **위험**: JPEG 디코더는 크기를 줄여 읽어도(subsampling) **progressive JPEG**는 전체 계수 버퍼를 네이티브 메모리로 잡아요. 25MP·4:4:4 progressive 사진은 약 150MB가 더 필요할 수 있고, 힙 상한(`MaxRAMPercentage=65`) 밖이라 컨테이너가 메모리 한도를 넘으면 앱이 종료돼요(`restart: unless-stopped`로 다시 떠요).
- **확인 방법**: dev에서 25MP에 가까운 progressive 4:4:4 JPEG(예: 5000 × 5000, 품질 90 이상, 크로마 서브샘플링 없음)를 올려 보고 서버에서 `docker stats --no-stream duriseo-dev-app-1`과 `docker inspect -f '{{.State.OOMKilled}}' duriseo-dev-app-1`(`true`면 메모리 부족)을 봐요. 부족하면 서버 크기(`t4g.medium`)나 컨테이너 한도를 올려요. 앱이 25MP 안으로 줄여 올리므로(D-153) 실제로는 드물어요.

### 3.10 서버 역할 노출 (사람이 정할 것)

IMDS(`169.254.169.254`)의 hop limit이 2이면 도커 컨테이너(Caddy, PostgreSQL, dev 앱, prod 앱)가 모두 서버 IAM 역할의 자격 증명을 받을 수 있어요. 앱에 코드 실행 취약점이 생기면 그 역할의 권한(사진 버킷 읽기·쓰기·삭제, `/duriseo/secret/*` 읽기, 백업 버킷)이 노출돼요. 이 위험은 사진 기능 이전에도 hop limit 기본값(AL2023은 2)에 있었을 수 있어요.

- 지금 적용한 것: dev 앱 컨테이너에 `AWS_EC2_METADATA_DISABLED=true`(배포 스크립트). dev는 로컬 저장소라 AWS 호출이 없어요 (앱은 실행 중에 Parameter Store를 읽지 않아요. 읽기는 서버의 배포 스크립트만 해요).
- **권장 대책 (사람이 정해요, 배포 스크립트에는 넣지 않았어요)**: Docker의 `DOCKER-USER` 체인에서 prod 앱 컨테이너 말고는 IMDS를 막아요. 예시 (Session Manager, root):
  ```bash
  # prod 앱 컨테이너의 IP 확인
  docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' duriseo-prod-app-1
  # prod만 허용하고 나머지 컨테이너는 IMDS 차단 (위 IP를 넣어요)
  iptables -I DOCKER-USER -d 169.254.169.254 -j DROP
  iptables -I DOCKER-USER -s <prod 앱 컨테이너 IP>/32 -d 169.254.169.254 -j ACCEPT
  ```
  주의: 컨테이너 IP는 다시 만들어질 때 바뀔 수 있어 `compose`에서 prod 앱에 고정 IP를 주거나 규칙을 배포 뒤에 다시 적용해야 하고, 재부팅하면 규칙이 사라져 영구 저장(`iptables-services` 등)이 필요해요. 어느 방식으로 할지, 아니면 hop limit을 1로 두고 prod 앱을 호스트 네트워크로 띄울지는 사람이 정해요.

## 4. 공개 레포 주의

이 레포는 공개예요. 커밋에 비밀값이 들어가면 지워도 기록에 남아요. 실수로 올렸으면 바로 그 키를 폐기하고 새로 발급해요.

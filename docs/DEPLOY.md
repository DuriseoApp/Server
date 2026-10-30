# 배포와 운영

처음 설정은 `docs/SETUP.md` 3장을 봐요. 이 문서는 설정이 끝난 뒤 배포하고, 문제를 보고, 되돌리고, 복구하는 방법이에요.

## 1. 구조

```
앱 ──HTTPS──> AWS EC2 t4g.small (서울, 고정 IP)
               ├─ Caddy (443)  운영 도메인 → app-prod, 개발 도메인 → app-dev   인증서 자동 발급·갱신
               ├─ app-prod  (Compose 프로젝트 duriseo-prod)  SPRING_PROFILES_ACTIVE=prod
               ├─ app-dev   (Compose 프로젝트 duriseo-dev)   SPRING_PROFILES_ACTIVE=dev
               └─ PostgreSQL 16 (duriseo-postgres)  DB duriseo_prod, duriseo_dev (사용자도 따로, 서로 접속 불가)
S3 백업 버킷 <── 매일 04:00(KST) 운영 DB pg_dump, 30일 보관
ECR duriseo-server <── 커밋 SHA 태그 이미지, 최근 20개 보관
```

| 서버 경로 | 내용 |
| --- | --- |
| `/opt/duriseo/releases/<SHA>` | 그 커밋의 `deploy/` 폴더 (최근 5개) |
| `/opt/duriseo/current` | 마지막으로 성공한 배포의 릴리스 |
| `/opt/duriseo/infra` | 공용 구성(Compose, Caddyfile, DB 초기화) 복사본 |
| `/opt/duriseo/state` | 설정 파일(`*.env`), 환경별 현재 이미지(`image-dev`, `image-prod`). root만 읽음 |
| `/opt/duriseo/state/secrets` | 서버가 만든 비밀값: DB 비밀번호, `JWT_SECRET`. **지우면 DB에 접속할 수 없어요** |

## 2. 배포

| 환경 | 언제 | 방법 |
| --- | --- | --- |
| dev | main에 머지되고 CI가 통과하면 | 자동 |
| prod | 원할 때 | Actions › 배포 › Run workflow › 환경 `prod`, SHA는 비우면 main 최신 › 승인 |

- 이미지는 커밋마다 한 번만 만들어요. prod는 dev에서 확인한 바로 그 이미지를 써요.
- 배포 순서: 설정 파일 갱신 → PostgreSQL·Caddy 확인 → 새 이미지로 앱 교체 → `/actuator/health/readiness` 확인(최대 3분).
- 헬스 확인에 실패하면 **이전 이미지로 자동으로 되돌리고** 워크플로는 실패로 끝나요. 로그 마지막 80줄이 워크플로 출력에 나와요.
- 앱을 바꾸는 동안 그 환경은 20~40초 끊겨요 (서버 1대 구성). 다른 환경은 영향 없어요.
- DB 마이그레이션(Flyway)은 앱이 뜰 때 실행돼요. 실패하면 트랜잭션이 되돌려지고 위처럼 이전 이미지로 돌아가요. 단, 이미 성공한 마이그레이션은 이미지를 되돌려도 남아요. 그래서 마이그레이션은 이전 버전 코드와 같이 돌아가도 문제없게 만들어요 (컬럼 삭제는 코드에서 안 쓰게 된 다음 배포에서).

### 이전 버전으로 되돌리기

Actions › 배포 › Run workflow에서 환경과 **이전 커밋 SHA**를 넣어 실행해요. 이미지가 ECR에 남아 있으면(최근 20개) 다시 빌드하지 않아요.

## 3. 서버 들여다보기

SSH 포트는 열려 있지 않아요. 콘솔로 접속해요: EC2 › 인스턴스 › `duriseo-server` 선택 › 연결 › **Session Manager** › 연결.

```bash
sudo -i
export AWS_REGION=ap-northeast-2

docker ps                                      # 컨테이너 상태
docker logs --tail 200 -f duriseo-prod-app-1   # 운영 앱 로그 (개발: duriseo-dev-app-1)
docker logs --tail 100 duriseo-caddy           # 인증서·접속 문제
cat /opt/duriseo/state/image-prod              # 지금 운영에 올라간 이미지
free -m                                        # 메모리
systemctl list-timers duriseo-backup.timer     # 다음 백업 시각
journalctl -u duriseo-backup --since today     # 오늘 백업 결과
```

로그의 `[traceId]`는 오류 응답의 `traceId`와 같아요. 사용자 오류 보고에 traceId가 있으면 `docker logs duriseo-prod-app-1 2>&1 | grep <traceId>`로 찾아요.

## 4. 백업과 복구

- 매일 04:00(KST)에 운영 DB를 백업해요. 버킷 이름은 CloudFormation 출력 `BackupBucketName`이에요. 30일이 지나면 자동으로 지워져요.
- 지금 바로 백업: `systemctl start duriseo-backup`

복구(운영 DB를 백업 시점으로 되돌림, 그 뒤 데이터는 사라짐):

```bash
sudo -i
export AWS_REGION=ap-northeast-2
bucket=$(aws ssm get-parameter --name /duriseo/config/BACKUP_BUCKET --query Parameter.Value --output text)
aws s3 ls "s3://$bucket/duriseo_prod/" --recursive | tail -5            # 백업 목록
aws s3 cp "s3://$bucket/<위 목록의 키>" /tmp/restore.dump

systemctl start duriseo-backup                                         # 복구 전 현재 상태도 백업
docker stop duriseo-prod-app-1
docker exec -i duriseo-postgres pg_restore -U postgres -d duriseo_prod \
  --clean --if-exists --no-owner --role=duriseo_prod < /tmp/restore.dump
docker start duriseo-prod-app-1
shred -u /tmp/restore.dump
```

## 5. 설정 바꾸기

- 도메인, 앱 설정값: Systems Manager › 파라미터 스토어에서 `/duriseo/config/*` 값을 고치고 배포를 다시 실행해요.
- 비밀값: `/duriseo/secret/*`을 고치고 배포를 다시 실행해요.
- **CloudFormation 스택은 업데이트하지 않아요.** 템플릿의 OS 이미지가 최신으로 바뀌면 서버가 새로 만들어져 DB 데이터가 새 서버에 없어요. 꼭 업데이트해야 하면 "변경 세트"를 먼저 만들어 `Server` 행의 교체(Replacement)가 `False`인지 보고, `True`면 실행하지 않아요.
- 도메인을 바꿀 때(예: DuckDNS → 구입한 도메인): 새 도메인을 서버 IP로 연결 → Parameter Store `DOMAIN_PROD`/`DOMAIN_DEV` 변경 → 재배포. 출시 뒤라면 구버전 앱이 쓰는 이전 주소도 한동안 같이 열어 둬야 해요 (Caddyfile에 주소 추가).

## 6. 서버 지우기

1. EC2 › 인스턴스 › `duriseo-server` › 작업 › 인스턴스 설정 › 종료 방지 변경 › 끄기.
2. CloudFormation › `duriseo` 스택 › 삭제.
3. 백업 버킷은 남아요(데이터 보호). 필요 없으면 S3에서 비우고 지워요.

## 7. 자주 나는 오류

| 증상 | 원인과 해결 |
| --- | --- |
| Actions `configure-aws-credentials`에서 `Not authorized to perform sts:AssumeRoleWithWebIdentity` | GitHub이 보낸 신분(OIDC `sub`)이 배포 역할 신뢰 정책에 없음. CloudTrail › 이벤트 기록 › `AssumeRoleWithWebIdentity` 이벤트의 `userName`이 GitHub이 보낸 값이다. GitHub은 `repo:DuriseoApp@<조직ID>/Server@<레포ID>:...` 형식을 쓴다. 템플릿 파라미터 `GitHubRepositoryWithIds`와 IAM 역할 `duriseo-DeployRole-*`의 신뢰 정책을 그 값에 맞춘다 |
| `https://도메인`이 열리지 않거나 인증서 오류 | DuckDNS IP가 `ServerPublicIp`와 같은지, `docker logs duriseo-caddy`에 인증서 발급 오류가 있는지 본다 |

## 8. 비밀값이 있는 곳

| 값 | 위치 |
| --- | --- |
| 카카오 어드민 키, Firebase 서비스 계정 키 | AWS Parameter Store `/duriseo/secret/*` (SecureString), 사용자 맥 |
| DB 비밀번호, `JWT_SECRET` | 서버 `/opt/duriseo/state/secrets` (서버가 만듦) |
| AWS 배포 권한 | 없음. GitHub Actions가 OIDC로 그때그때 임시 권한을 받아요 (`AWS_DEPLOY_ROLE_ARN`은 비밀이 아닌 역할 이름) |

배포 스크립트는 비밀값을 출력하지 않아요. 비밀값이 로그나 GitHub에 보이면 그 값을 바로 폐기하고 새로 만들어요.

## 9. 처리하지 못한 Kakao 해제 알림

사용자가 Kakao 계정 설정에서 둘이서 연결을 끊으면 Kakao가 서버의 `/api/v1/webhooks/kakao/unlink`를 불러요 (Kakao만 쓰던 회원은 탈퇴 처리, Google도 쓰면 Kakao 로그인만 해제). 서버는 처리 중 오류가 나도 **Kakao에는 항상 200**으로 답해요 (200이 아니면 Kakao가 웹훅을 꺼 버릴 수 있어요). 그래서 처리하지 못한 알림은 Kakao가 다시 보내 주지 않고, 로그에만 남아요. 운영자가 찾아서 처리해요.

**찾기**: 앱 로그에서 ERROR 줄을 찾아요 (3장).

```bash
docker logs duriseo-prod-app-1 2>&1 | grep "Kakao unlink notice was not processed"
# Kakao unlink notice was not processed: user_id=<Kakao 회원번호> referrer_type=<값> traceId=<traceId>
```

- `user_id`는 Kakao 회원번호, `referrer_type`은 연결을 끊은 경로(`ACCOUNT_DELETE`, `UNLINK_FROM_APPS` 등)예요. 어느 값이든 처리는 같아요.
- 같은 `traceId`로 `grep <traceId>`하면 줄 바로 아래에 오류 원인(예외와 스택)이 있어요. 트랜잭션은 롤백돼서 사용자 데이터는 알림이 오기 전 그대로예요.
- 인증 실패(401 `WEBHOOK_UNAUTHORIZED`)는 이 ERROR와 달라요. Kakao 개발자 콘솔에 등록한 어드민 키·앱 ID가 서버 설정(`KAKAO_ADMIN_KEY`, `KAKAO_APP_ID`)과 다르면 모든 알림이 401이 돼요. 서버 설정에 어드민 키가 비어 있어도 마찬가지예요.

**다시 처리하기**: 원인을 고친 뒤 같은 알림을 서버에 다시 보내요. 같은 알림이 여러 번 와도 결과가 같아서(이미 처리했거나 모르는 사용자면 아무것도 안 해요) 안전해요. 어드민 키가 화면과 셸 기록에 남지 않게 컨테이너 안의 환경변수를 써요.

```bash
docker exec duriseo-prod-app-1 sh -c 'curl -s -o /dev/null -w "%{http_code}\n" -X POST \
  http://localhost:8080/api/v1/webhooks/kakao/unlink \
  -H "Authorization: KakaoAK $KAKAO_ADMIN_KEY" \
  -d "app_id=$KAKAO_APP_ID" -d "user_id=<로그의 Kakao 회원번호>" -d "referrer_type=<로그의 값>"'
```

`200`이 나와도 처리 결과는 로그로 확인해요. 같은 ERROR가 또 나오면 원인이 아직 남아 있는 거예요. 사용자의 기기는 탈퇴 처리가 끝나면 다음 요청에서 401 `SESSION_INVALID`를 받아 시작 화면으로 돌아가요.

**등록**: Kakao Developers의 앱 › 웹훅 › 연결 해제 웹훅에 `https://<서버 주소>/api/v1/webhooks/kakao/unlink`를 등록하는 것은 사람이 할 일이에요 (서버를 배포한 뒤). 운영 도메인과 개발 도메인을 각각 등록해요.


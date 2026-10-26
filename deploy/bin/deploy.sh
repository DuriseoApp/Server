#!/bin/bash
# 서버에서 앱 하나를 배포한다. GitHub Actions 배포 워크플로가 AWS SSM으로 root 권한으로 실행한다.
#   사용: deploy.sh <dev|prod> <이미지 URI:커밋SHA>
#
# 하는 일
#   1. 서버에서만 쓰는 비밀값(DB 비밀번호, 토큰 서명 키)을 처음 한 번 만든다
#   2. Parameter Store 값으로 설정 파일(state/*.env)을 만든다
#   3. PostgreSQL과 Caddy를 띄운다 (이미 떠 있으면 그대로)
#   4. 새 이미지로 앱을 바꾸고 헬스 확인. 실패하면 이전 이미지로 되돌리고 실패로 끝난다
#   5. 매일 DB 백업 타이머를 설치한다
#
# 비밀값은 화면(SSM 출력)에 찍지 않는다.
set -euo pipefail

ENV_NAME="${1:?사용: deploy.sh <dev|prod> <이미지>}"
IMAGE="${2:?사용: deploy.sh <dev|prod> <이미지>}"

case "$ENV_NAME" in
	dev)  HOST_PORT=8081; APP_MEM_LIMIT=512m ;;
	prod) HOST_PORT=8080; APP_MEM_LIMIT=640m ;;
	*) echo "환경은 dev 또는 prod여야 합니다: $ENV_NAME" >&2; exit 2 ;;
esac

ROOT=/opt/duriseo
STATE_DIR="$ROOT/state"
SECRET_DIR="$STATE_DIR/secrets"
RELEASE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export STATE_DIR

log() { echo "[deploy $ENV_NAME] $*"; }

imds_token="$(curl -fsS -X PUT http://169.254.169.254/latest/api/token -H 'X-aws-ec2-metadata-token-ttl-seconds: 60')"
AWS_REGION="$(curl -fsS -H "X-aws-ec2-metadata-token: $imds_token" http://169.254.169.254/latest/meta-data/placement/region)"
export AWS_REGION AWS_DEFAULT_REGION="$AWS_REGION"

# Parameter Store 값. 두 번째 인자가 optional이면 없을 때 빈 값
param() {
	local name="$1" optional="${2:-}"
	if ! aws ssm get-parameter --name "$name" --with-decryption --query Parameter.Value --output text 2>/dev/null; then
		if [ "$optional" = optional ]; then
			echo ""
		else
			echo "Parameter Store에 $name 이 없습니다" >&2
			return 1
		fi
	fi
}

# ── 1. 서버에서 만드는 비밀값 (처음 한 번) ──
install -d -m 700 "$STATE_DIR" "$SECRET_DIR"
generate_secret() {
	local file="$SECRET_DIR/$1"
	if [ ! -s "$file" ]; then
		(umask 077 && openssl rand -hex 32 > "$file")
		log "비밀값 $1 을 새로 만들었습니다"
	fi
}
for name in postgres-superuser db-dev db-prod jwt-dev jwt-prod; do
	generate_secret "$name"
done
secret() { cat "$SECRET_DIR/$1"; }

# ── 2. 설정 파일 ──
write_env() {
	local file="$1"
	local tmp
	tmp="$(mktemp "$STATE_DIR/.env.XXXXXX")"
	cat > "$tmp"
	chmod 600 "$tmp"
	mv "$tmp" "$file"
}

write_env "$STATE_DIR/postgres.env" <<EOF
POSTGRES_PASSWORD=$(secret postgres-superuser)
DEV_DB_PASSWORD=$(secret db-dev)
PROD_DB_PASSWORD=$(secret db-prod)
EOF

# heredoc 안의 $(...) 실패는 set -e로 멈추지 않으므로 값은 먼저 변수로 읽는다
domain_dev="$(param /duriseo/config/DOMAIN_DEV)"
domain_prod="$(param /duriseo/config/DOMAIN_PROD)"
acme_email="$(param /duriseo/config/ACME_EMAIL)"
google_web_client_id="$(param /duriseo/config/GOOGLE_WEB_CLIENT_ID)"
kakao_app_id="$(param /duriseo/config/KAKAO_APP_ID)"
firebase_project_id="$(param /duriseo/config/FIREBASE_PROJECT_ID)"

write_env "$STATE_DIR/caddy.env" <<EOF
DOMAIN_DEV=$domain_dev
DOMAIN_PROD=$domain_prod
ACME_EMAIL=$acme_email
EOF

firebase_json="$(param /duriseo/secret/FIREBASE_SERVICE_ACCOUNT_JSON optional)"
firebase_base64=""
if [ -n "$firebase_json" ]; then
	firebase_base64="$(printf '%s' "$firebase_json" | base64 -w0)"
fi
kakao_admin_key="$(param /duriseo/secret/KAKAO_ADMIN_KEY optional)"
[ -n "$firebase_base64" ] || log "참고: Firebase 서비스 계정 키가 아직 Parameter Store에 없습니다"
[ -n "$kakao_admin_key" ] || log "참고: 카카오 어드민 키가 아직 Parameter Store에 없습니다"

write_env "$STATE_DIR/app-$ENV_NAME.env" <<EOF
SPRING_PROFILES_ACTIVE=$ENV_NAME
DB_URL=jdbc:postgresql://postgres:5432/duriseo_$ENV_NAME
DB_USERNAME=duriseo_$ENV_NAME
DB_PASSWORD=$(secret "db-$ENV_NAME")
JWT_SECRET=$(secret "jwt-$ENV_NAME")
GOOGLE_WEB_CLIENT_ID=$google_web_client_id
KAKAO_APP_ID=$kakao_app_id
KAKAO_ADMIN_KEY=$kakao_admin_key
FIREBASE_PROJECT_ID=$firebase_project_id
FIREBASE_CREDENTIALS_BASE64=$firebase_base64
EOF
unset firebase_json firebase_base64 kakao_admin_key

# ── 3. PostgreSQL, Caddy ──
log "ECR 로그인"
aws ecr get-login-password | docker login --username AWS --password-stdin "${IMAGE%%/*}" >/dev/null

# 공용 구성 파일은 고정 위치에 복사해서 쓴다. 릴리스 폴더를 직접 마운트하면 배포마다 경로가 바뀌어
# PostgreSQL까지 다시 만들어지기 때문이다 (다른 환경 앱이 끊김).
INFRA_DIR="$ROOT/infra"
install -d -m 755 "$INFRA_DIR" "$INFRA_DIR/postgres" "$INFRA_DIR/caddy"
install -m 644 "$RELEASE_DIR/compose.infra.yml" "$INFRA_DIR/"
install -m 644 "$RELEASE_DIR/caddy/Caddyfile" "$INFRA_DIR/caddy/"
install -m 755 "$RELEASE_DIR/postgres/init-databases.sh" "$INFRA_DIR/postgres/"

log "PostgreSQL, Caddy 확인"
docker compose -f "$INFRA_DIR/compose.infra.yml" up -d --wait --remove-orphans
# Caddyfile 내용이 바뀌었을 수 있으므로 무중단으로 다시 읽힌다
docker exec duriseo-caddy caddy reload --config /etc/caddy/Caddyfile --adapter caddyfile >/dev/null 2>&1 \
	|| log "참고: Caddy 설정 다시 읽기에 실패했습니다 (도메인 설정을 확인하세요)"

# ── 4. 앱 교체 ──
app_compose() {
	APP_ENV="$ENV_NAME" APP_IMAGE="$1" HOST_PORT="$HOST_PORT" APP_MEM_LIMIT="$APP_MEM_LIMIT" \
		docker compose -p "duriseo-$ENV_NAME" -f "$RELEASE_DIR/compose.app.yml" "${@:2}"
}

wait_healthy() {
	local url="http://127.0.0.1:$HOST_PORT/actuator/health/readiness"
	for _ in $(seq 1 60); do
		if curl -fsS --max-time 3 "$url" >/dev/null 2>&1; then
			return 0
		fi
		sleep 3
	done
	return 1
}

start_app() {
	local image="$1"
	docker pull --quiet "$image" >/dev/null
	app_compose "$image" up -d --remove-orphans
	wait_healthy
}

image_file="$STATE_DIR/image-$ENV_NAME"
previous_image="$(cat "$image_file" 2>/dev/null || true)"

log "새 이미지로 교체: ${IMAGE##*/}"
if start_app "$IMAGE"; then
	echo "$IMAGE" > "$image_file"
	log "헬스 확인 통과"
else
	log "헬스 확인 실패. 앱 로그 마지막 80줄:"
	app_compose "$IMAGE" logs --no-color --tail 80 app || true
	if [ -n "$previous_image" ] && [ "$previous_image" != "$IMAGE" ]; then
		log "이전 이미지로 되돌립니다: ${previous_image##*/}"
		if start_app "$previous_image"; then
			log "되돌리기 완료"
		else
			log "되돌리기도 실패했습니다. 서버 확인이 필요합니다"
		fi
	fi
	exit 1
fi

# ── 5. 마무리: 현재 릴리스 표시, 백업 타이머, 정리 ──
ln -sfn "$RELEASE_DIR" "$ROOT/current"

install -m 644 "$RELEASE_DIR/systemd/duriseo-backup.service" /etc/systemd/system/duriseo-backup.service
install -m 644 "$RELEASE_DIR/systemd/duriseo-backup.timer" /etc/systemd/system/duriseo-backup.timer
systemctl daemon-reload
systemctl enable --now duriseo-backup.timer >/dev/null 2>&1

# 오래된 릴리스 폴더(최근 5개만 남김)와 10일 넘게 안 쓴 이미지
mapfile -t old_releases < <(find "$ROOT/releases" -mindepth 1 -maxdepth 1 -type d -printf '%T@ %p\n' | sort -rn | tail -n +6 | cut -d' ' -f2-)
for dir in "${old_releases[@]}"; do
	[ "$dir" = "$RELEASE_DIR" ] || rm -r -- "$dir"
done
docker image prune -af --filter "until=240h" >/dev/null

log "배포 완료"

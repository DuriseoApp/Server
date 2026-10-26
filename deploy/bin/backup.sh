#!/bin/bash
# 운영 DB를 pg_dump(custom 형식)로 떠서 S3 백업 버킷에 올린다. systemd 타이머가 매일 실행한다.
# 버킷은 30일 지난 백업을 자동으로 지운다 (CloudFormation 수명주기 규칙). 복구 방법은 docs/DEPLOY.md.
set -euo pipefail

imds_token="$(curl -fsS -X PUT http://169.254.169.254/latest/api/token -H 'X-aws-ec2-metadata-token-ttl-seconds: 60')"
AWS_REGION="$(curl -fsS -H "X-aws-ec2-metadata-token: $imds_token" http://169.254.169.254/latest/meta-data/placement/region)"
export AWS_REGION AWS_DEFAULT_REGION="$AWS_REGION"

bucket="$(aws ssm get-parameter --name /duriseo/config/BACKUP_BUCKET --query Parameter.Value --output text)"
db=duriseo_prod
key="$db/$(date -u +%Y/%m)/$db-$(date -u +%Y%m%dT%H%M%SZ).dump"

docker exec duriseo-postgres pg_dump --username postgres --format custom "$db" \
	| aws s3 cp - "s3://$bucket/$key" --only-show-errors

echo "백업 완료: s3://$bucket/$key"

#!/bin/bash
# PostgreSQL 데이터 폴더가 처음 만들어질 때 한 번만 실행된다 (공식 이미지의 docker-entrypoint-initdb.d).
# 개발 DB와 운영 DB를 나누고, 각 DB 사용자는 자기 DB에만 접속할 수 있다.
# 비밀번호는 서버에서 만든 값이다 (bin/deploy.sh, postgres.env). 16진수라 SQL에 그대로 넣어도 안전하다.
set -euo pipefail

for env in dev prod; do
	var="$(echo "$env" | tr '[:lower:]' '[:upper:]')_DB_PASSWORD"
	password="${!var:?$var가 없습니다}"
	db="duriseo_${env}"

	psql -v ON_ERROR_STOP=1 --username postgres --dbname postgres <<-SQL
		CREATE ROLE ${db} LOGIN PASSWORD '${password}';
		CREATE DATABASE ${db} OWNER ${db};
		REVOKE ALL ON DATABASE ${db} FROM PUBLIC;
		GRANT CONNECT ON DATABASE ${db} TO ${db};
	SQL
done

-- 공지 팝업·업데이트 규칙·기기 정보 (PRD v0.22 D-176~D-183, ERD 3.4, DB-21, DB-22)
-- 문자열 PK는 UUID 원칙의 예외다(운영자가 정한 id, DB-21). 제목·이미지 조합과 build_op별 컬럼 조합은 서비스에서 검사하고 DB CHECK를 두지 않는다.

create table app_notices (
    id         varchar(50)  primary key,
    closable   boolean      not null,
    title      varchar(40),
    body       varchar(500),
    image      varchar(500),
    image_alt  varchar(100),
    link_text  varchar(20),
    link_url   varchar(500),
    platform   varchar(10),
    starts_at  timestamptz,
    ends_at    timestamptz,
    created_at timestamptz  not null default now(),
    updated_at timestamptz  not null default now()
);

create table app_update_rules (
    id           varchar(50)  primary key,
    mode         varchar(10)  not null,
    platform     varchar(10)  not null,
    build_op     varchar(5)   not null,
    build_value  integer,
    build_min    integer,
    build_max    integer,
    build_values integer[],
    os_min       integer,
    os_max       integer,
    title        varchar(40),
    body         varchar(500),
    image        varchar(500),
    image_alt    varchar(100),
    created_at   timestamptz  not null default now(),
    updated_at   timestamptz  not null default now(),
    constraint ck_app_update_rules_mode check (mode in ('FORCE', 'RECOMMEND')),
    constraint ck_app_update_rules_build_op check (build_op in ('EQ', 'LTE', 'GTE', 'RANGE', 'IN'))
);

-- 기기 정보: 현재 값만 둔다. 기존 행은 null (DB-22)
alter table devices add column os_version  varchar(20);
alter table devices add column app_version varchar(50);
alter table devices add column app_build   integer;

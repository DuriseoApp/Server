-- 둘이서 초기 스키마 (Docs ERD 3장, PRD v0.6 기준)
-- ERD 표의 "제약" 열만 DDL로 옮긴다. "서비스"로 표시된 규칙(날짜 범위 DB-13, 구성원 수 등)은 코드에서 검사한다.
-- 기본 키 uuid 값은 애플리케이션에서 UUIDv7로 만든다. 열거형은 varchar + CHECK (ERD 1장).
-- 제약 이름은 서비스가 위반을 오류 코드로 바꿀 때 쓰므로 바꾸지 않는다.

-- ─────────────────────────────────────────────
-- 3.1 계정
-- ─────────────────────────────────────────────

create table users (
    id                uuid         primary key,
    account_type      varchar(10)  not null,
    nickname          varchar(10)  not null,
    profile_image_key varchar(200),
    time_zone         varchar(50)  not null default 'Asia/Seoul',
    deleted_at        timestamptz,
    created_at        timestamptz  not null default now(),
    updated_at        timestamptz  not null default now(),
    constraint ck_users_account_type check (account_type in ('GUEST', 'MEMBER'))
);

create table social_accounts (
    id               uuid         primary key,
    user_id          uuid         not null references users (id) on delete cascade,
    provider         varchar(10)  not null,
    provider_user_id varchar(100) not null,
    created_at       timestamptz  not null default now(),
    constraint ck_social_accounts_provider check (provider in ('GOOGLE', 'KAKAO')),
    -- 한 소셜 계정은 한 사용자에게만 연결된다. 연동 거부(PRD Q-01) 판정에 쓴다
    constraint uq_social_accounts_provider_user unique (provider, provider_user_id),
    constraint uq_social_accounts_user_provider unique (user_id, provider)
);

create table auth_sessions (
    id                 uuid        primary key,
    user_id            uuid        not null references users (id) on delete cascade,
    refresh_token_hash char(64)    not null,
    expires_at         timestamptz not null,
    last_used_at       timestamptz not null,
    revoked_at         timestamptz,
    revoked_reason     varchar(10),
    created_at         timestamptz not null default now(),
    constraint uq_auth_sessions_refresh_token_hash unique (refresh_token_hash),
    constraint ck_auth_sessions_revoked_reason check (revoked_reason in ('LOGOUT', 'REPLACED', 'WITHDRAWN'))
);

create table devices (
    id         uuid         primary key,
    user_id    uuid         not null references users (id) on delete cascade,
    fcm_token  varchar(255) not null,
    platform   varchar(10)  not null default 'ANDROID',
    updated_at timestamptz  not null default now(),
    constraint uq_devices_fcm_token unique (fcm_token)
);

create table notification_settings (
    user_id         uuid        primary key references users (id) on delete cascade,
    event_reminder  boolean     not null default true,
    partner_created boolean     not null default true,
    memo_pinned     boolean     not null default true,
    anniversary     boolean     not null default true,
    updated_at      timestamptz not null default now()
);

-- ─────────────────────────────────────────────
-- 3.2 공간
-- ─────────────────────────────────────────────

create table spaces (
    id             uuid        primary key,
    type           varchar(10) not null,
    status         varchar(10) not null default 'ACTIVE',
    start_date     date,
    together_color varchar(20),
    unlinked_at    timestamptz,
    purge_at       timestamptz,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now(),
    constraint ck_spaces_type check (type in ('PERSONAL', 'COUPLE')),
    constraint ck_spaces_status check (status in ('ACTIVE', 'UNLINKED')),
    -- 커플 공간이 아니면 커플 전용 컬럼은 모두 null
    constraint ck_spaces_couple_only_columns check (
        type = 'COUPLE'
        or (start_date is null and together_color is null and unlinked_at is null and purge_at is null)
    ),
    constraint ck_spaces_unlinked_columns check (
        (status = 'UNLINKED') = (unlinked_at is not null and purge_at is not null)
    )
);

create index ix_spaces_purge_at on spaces (purge_at) where purge_at is not null;

create table space_members (
    id        uuid        primary key,
    space_id  uuid        not null references spaces (id) on delete cascade,
    user_id   uuid        not null references users (id) on delete cascade,
    color     varchar(20) not null,
    joined_at timestamptz not null,
    left_at   timestamptz,
    constraint uq_space_members_space_user unique (space_id, user_id)
);

-- 사용자는 동시에 활성 공간 하나에만 속한다 ("한 사람은 한 커플")
create unique index uq_space_members_active_user on space_members (user_id) where left_at is null;

create table invite_codes (
    id              uuid        primary key,
    code            char(6)     not null,
    creator_user_id uuid        not null references users (id) on delete cascade,
    expires_at      timestamptz not null,
    used_at         timestamptz,
    used_by_user_id uuid        references users (id) on delete set null,
    revoked_at      timestamptz,
    created_at      timestamptz not null default now(),
    constraint uq_invite_codes_code unique (code)
);

-- 사용자당 유효한 코드는 하나
create unique index uq_invite_codes_active_creator on invite_codes (creator_user_id)
    where used_at is null and revoked_at is null;

create table invite_code_attempts (
    id           uuid        primary key,
    user_id      uuid        not null references users (id) on delete cascade,
    succeeded    boolean     not null,
    attempted_at timestamptz not null
);

create index ix_invite_code_attempts_user_attempted on invite_code_attempts (user_id, attempted_at);

-- 알림은 spaces를 참조하므로 공간 다음에 만든다 (ERD 3.1)
create table notifications (
    id          uuid         primary key,
    user_id     uuid         not null references users (id) on delete cascade,
    space_id    uuid         references spaces (id) on delete cascade,
    type        varchar(30)  not null,
    title       varchar(100) not null,
    body        varchar(200),
    target_type varchar(20),
    target_id   uuid,
    read_at     timestamptz,
    created_at  timestamptz  not null default now(),
    constraint ck_notifications_type check (
        type in ('EVENT_REMINDER', 'PARTNER_CREATED', 'MEMO_PINNED', 'ANNIVERSARY', 'COUPLE_LINKED', 'COUPLE_UNLINKED')
    ),
    constraint ck_notifications_target_type check (target_type in ('EVENT', 'MEMO', 'ANNIVERSARY', 'SETTINGS'))
);

create index ix_notifications_user_created on notifications (user_id, created_at desc);
create index ix_notifications_user_unread on notifications (user_id) where read_at is null;

-- ─────────────────────────────────────────────
-- 3.3 콘텐츠
-- 사용자 참조(author_id, updated_by, pinned_by)는 ON DELETE RESTRICT. 탈퇴는 익명화로 처리한다 (ERD 4.3)
-- ─────────────────────────────────────────────

create table events (
    id          uuid          primary key,
    space_id    uuid          not null references spaces (id) on delete cascade,
    author_id   uuid          not null references users (id) on delete restrict,
    is_together boolean       not null default false,
    title       varchar(50)   not null,
    note        varchar(1000),
    is_all_day  boolean       not null,
    start_at    timestamptz,
    end_at      timestamptz,
    start_date  date,
    end_date    date,
    updated_by  uuid          references users (id) on delete restrict,
    created_at  timestamptz   not null default now(),
    updated_at  timestamptz   not null default now(),
    -- 종일이면 날짜만, 아니면 일시만. 끝은 시작보다 앞설 수 없다
    constraint ck_events_time_columns check (
        (is_all_day
            and start_date is not null and end_date is not null
            and start_at is null and end_at is null
            and end_date >= start_date)
        or
        (not is_all_day
            and start_at is not null and end_at is not null
            and start_date is null and end_date is null
            and end_at >= start_at)
    )
);

create index ix_events_space_start_at on events (space_id, start_at);
create index ix_events_space_start_date on events (space_id, start_date);

create table event_reminders (
    id         uuid        primary key,
    event_id   uuid        not null references events (id) on delete cascade,
    user_id    uuid        not null references users (id) on delete cascade,
    option     varchar(20) not null,
    fire_at    timestamptz not null,
    sent_at    timestamptz,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint ck_event_reminders_option check (
        option in ('AT_TIME', 'MIN_10', 'HOUR_1', 'DAY_1', 'ALLDAY_9AM', 'ALLDAY_PREV_9AM')
    ),
    constraint uq_event_reminders_event_user unique (event_id, user_id)
);

create index ix_event_reminders_unsent_fire_at on event_reminders (fire_at) where sent_at is null;

create table anniversaries (
    id             uuid        primary key,
    space_id       uuid        not null references spaces (id) on delete cascade,
    author_id      uuid        not null references users (id) on delete restrict,
    name           varchar(30) not null,
    date           date        not null,
    repeats_yearly boolean     not null default true,
    created_at     timestamptz not null default now(),
    updated_at     timestamptz not null default now()
);

create index ix_anniversaries_space on anniversaries (space_id);

create table memos (
    id         uuid          primary key,
    space_id   uuid          not null references spaces (id) on delete cascade,
    author_id  uuid          not null references users (id) on delete restrict,
    title      varchar(50),
    body       varchar(5000),
    memo_date  date,
    pinned_at  timestamptz,
    pinned_by  uuid          references users (id) on delete restrict,
    created_at timestamptz   not null default now(),
    updated_at timestamptz   not null default now()
);

create index ix_memos_space_created on memos (space_id, created_at desc);
create index ix_memos_space_pinned on memos (space_id, pinned_at desc) where pinned_at is not null;
create index ix_memos_space_memo_date on memos (space_id, memo_date) where memo_date is not null;

-- memo_checklist_items는 삭제됨 (PRD v0.4)

create table memo_favorites (
    memo_id    uuid        not null references memos (id) on delete cascade,
    user_id    uuid        not null references users (id) on delete cascade,
    created_at timestamptz not null default now(),
    constraint pk_memo_favorites primary key (memo_id, user_id)
);

-- 단위 3: 커플 공간의 연결한 날 spaces.linked_at (Docs ERD 3.2, PRD v0.9 D-94, DB-17)
-- ERD 3.2 "마이그레이션 순서"를 바꾸지 않는다. 채우기(②) 없이 새 CHECK(④)를 걸면 기존 COUPLE 행에서 실패한다.
-- V1은 고치지 않는다.

-- ① 컬럼 추가
alter table spaces add column linked_at timestamptz;

-- ② 기존 커플 공간은 만든 시각으로 채운다 (출시 전이라 개발 데이터뿐이고, 복구된 개발 데이터는 처음 연결 시각이 된다)
update spaces set linked_at = created_at where type = 'COUPLE';

-- ③ 기존 CHECK(V1의 ck_spaces_couple_only_columns)를 지우고 linked_at을 넣어 같은 이름으로 다시 만든다
alter table spaces drop constraint ck_spaces_couple_only_columns;
alter table spaces add constraint ck_spaces_couple_only_columns check (
    type = 'COUPLE'
    or (
        start_date is null and together_color is null and linked_at is null
        and unlinked_at is null and purge_at is null
    )
);

-- ④ 커플 공간은 늘 연결 시각이 있다
alter table spaces add constraint ck_spaces_couple_linked_at check (type = 'PERSONAL' or linked_at is not null);

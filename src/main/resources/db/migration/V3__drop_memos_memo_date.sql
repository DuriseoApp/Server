-- 메모 날짜 삭제 (PRD v0.21 D-174, ERD DB-20). 캘린더에 메모를 보이지 않아 memo_date와 부분 인덱스를 없앤다.
drop index if exists ix_memos_space_memo_date;
alter table memos drop column memo_date;

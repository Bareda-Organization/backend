-- destination_seed.sql 이 심은 것(L3DEST- 학생 · 학부모 계정 · 전용 승하차지)과 그 명단 · 로그인 기록을 지운다 — 다른 시험의 데이터 규모를 원래대로 돌린다.
-- 위치용 회차(LP-…)에 붙은 run_rider 도 함께 지운다. 한 트랜잭션이라 중간에 실패하면 아무것도 지워지지 않는다.
--
-- 실행: docker exec -i school-bus-postgres-1 psql -U schoolbus -d schoolbus_load -q -v ON_ERROR_STOP=1 < sql/destination_cleanup.sql
BEGIN;
DELETE FROM run_rider WHERE student_id IN (SELECT id FROM student WHERE name LIKE 'L3DEST-%');
DELETE FROM guardian_student WHERE student_id IN (SELECT id FROM student WHERE name LIKE 'L3DEST-%');
DELETE FROM link_code WHERE student_id IN (SELECT id FROM student WHERE name LIKE 'L3DEST-%');
DELETE FROM refresh_token WHERE account_id IN (SELECT id FROM account WHERE login_id LIKE 'l3dest-%-par');
DELETE FROM device_token WHERE account_id IN (SELECT id FROM account WHERE login_id LIKE 'l3dest-%-par');
DELETE FROM notification_setting WHERE account_id IN (SELECT id FROM account WHERE login_id LIKE 'l3dest-%-par');
DELETE FROM guardian WHERE account_id IN (SELECT id FROM account WHERE login_id LIKE 'l3dest-%-par');
DELETE FROM student WHERE name LIKE 'L3DEST-%';
DELETE FROM account WHERE login_id LIKE 'l3dest-%-par';
DELETE FROM stop WHERE name LIKE 'L3DEST-%';
COMMIT;
SELECT (SELECT count(*) FROM student WHERE name LIKE 'L3DEST-%') AS l3dest_student,
       (SELECT count(*) FROM account WHERE login_id LIKE 'l3dest-%-par') AS l3dest_account,
       (SELECT count(*) FROM stop WHERE name LIKE 'L3DEST-%') AS l3dest_stop;

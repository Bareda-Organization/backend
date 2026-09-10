-- 회차 산출물 테이블의 행 크기 — 이쪽은 보존 정책이 없어 무기한 쌓인다.
\set n 100000
CREATE TEMP TABLE sb AS
SELECT relname, pg_total_relation_size(c.oid) AS bytes
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND relkind = 'r';

-- uk_run_rider_run_student(run_id, student_id) 때문에 같은 조합을 반복할 수 없다 — 회차 50개 ×
-- 학생 2,000명 조합으로 10만 행을 만든다.
INSERT INTO run_rider (run_id, student_id, stop_id, status, seat_no, boarded_at, alighted_at)
SELECT r.id, s.id, (SELECT id FROM stop ORDER BY id LIMIT 1), 'alighted', 12, now(), now()
FROM (SELECT id FROM run ORDER BY id LIMIT 50) r
CROSS JOIN (SELECT id FROM student ORDER BY id LIMIT 2000) s;

ANALYZE run_rider;

SELECT sb.relname AS 테이블,
       pg_size_pretty(pg_total_relation_size(c.oid) - sb.bytes) AS 증가분,
       round((pg_total_relation_size(c.oid) - sb.bytes)::numeric / :n, 1) AS 행당_바이트
FROM sb JOIN pg_class c ON c.relname = sb.relname
JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'public'
WHERE pg_total_relation_size(c.oid) - sb.bytes > 0;

-- 이력 테이블의 실제 행 크기 측정 — 추정 대신 넣어 보고 잰다.
-- pg_total_relation_size 는 힙 + 인덱스 + TOAST 를 모두 포함한다(인덱스가 힙보다 큰 테이블이 흔해
-- 힙만 재면 절반을 놓친다).
\set n 200000
\timing off

CREATE TEMP TABLE size_before AS
SELECT relname, pg_total_relation_size(c.oid) AS bytes
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public' AND relkind = 'r';

-- ① run_position — 90일 보존. 목표 규모에서 가장 빠르게 쌓인다.
INSERT INTO run_position (run_id, lat, lng, recorded_at, received_at, speed, heading)
SELECT (SELECT id FROM run ORDER BY id LIMIT 1),
       37.5 + (i % 1000) * 0.0001, 127.0 + (i % 1000) * 0.0001,
       now() - (i || ' seconds')::interval, now() - (i || ' seconds')::interval,
       (i % 60)::numeric(5,2), (i % 360)::numeric(5,2)
FROM generate_series(1, :n) AS i;

-- ② notification_log — 14일 보존. 한글 본문이라 문자당 3바이트로 잡는다.
INSERT INTO notification_log (academy_id, recipient_account_id, recipient_name, recipient_role,
                              student_id, student_name, run_id, bus_no, type, title, body, popup,
                              push_state, push_attempts, dedup_key, created_at, sent_at)
SELECT 1, 1, '홍길동보호자', 'parent', 1, '홍길동', 1, 'BUS-01', 'boarding',
       '승차 알림 — 홍길동 학생이 버스에 탑승했습니다',
       '홍길동 학생이 2026-09-09 08:12 에 반포2동 정류장에서 1호차에 탑승했습니다. 도착 예정 시각은 08:35 입니다.',
       false, 'sent', 1, 'measure-' || i, now(), now()
FROM generate_series(1, :n) AS i;

-- ③ audit_log — 무기한 보존. detail jsonb 가 붙는다.
INSERT INTO audit_log (academy_id, actor_account_id, actor_login_id, category, action,
                       target_type, target_id, ip, block_event, detail, occurred_at)
SELECT 1, 1, 'staffA', 'data_access', 'read', 'student', i, '203.0.113.10'::inet, false,
       jsonb_build_object('fields', ARRAY['name','phone','address'], 'reason', 'roster_view'),
       now() - (i || ' seconds')::interval
FROM generate_series(1, :n) AS i;

-- ④ rider_status_history — 무기한 보존.
INSERT INTO rider_status_history (run_rider_id, from_status, to_status, is_revert, reason,
                                  verify_method, client_key, occurred_at, changed_at, actor_type, changed_by)
SELECT 1, 'waiting', 'boarded', false, NULL, 'manual', gen_random_uuid(),
       now() - (i || ' seconds')::interval, now() - (i || ' seconds')::interval, 'escort', 1
FROM generate_series(1, :n) AS i;

ANALYZE run_position;
ANALYZE notification_log;
ANALYZE audit_log;
ANALYZE rider_status_history;

SELECT b.relname AS 테이블,
       pg_size_pretty(pg_total_relation_size(c.oid) - b.bytes) AS 증가분,
       round((pg_total_relation_size(c.oid) - b.bytes)::numeric / :n, 1) AS 행당_바이트,
       pg_size_pretty(pg_relation_size(c.oid)) AS 힙만,
       pg_size_pretty(pg_indexes_size(c.oid)) AS 인덱스
FROM size_before b
JOIN pg_class c ON c.relname = b.relname
JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = 'public'
WHERE pg_total_relation_size(c.oid) - b.bytes > 0
ORDER BY 2 DESC;

-- 학생 목적지 3,000곳 이상 시험(scenario6_student_destinations.js)용 시드 — r0_capacity_seed.sql · r46_parent_seed.sql 뒤에 실행한다.
-- 학생 채널은 학생 1명 = 목적지 1곳(/topic/students/{id}/run)이라, 기존 학생 2,000명에 학생 per_bus × 100(기본 12 → 1,200)명을 더해 3,200곳을 만든다.
-- 새 학생은 이름이 L3DEST- 로 시작한다 — 'LOADCAP-%' 가 아니라서 r46_mint_tokens.py 의 기본 학부모 추출 · 확정 배치(노선 승하차지 기준) · 기존 회차 명단에 섞이지 않는다.
-- 승하차지도 학원마다 노선에 안 들어간 전용 1곳(L3DEST-<학원코드>-S0)이라 확정 배치의 명단 계산을 건드리지 않는다.
-- 학생마다 학부모 1명(계정 l3dest-…-par · 비밀번호 평문 "password") + guardian + guardian_student. 다시 실행해도 겹치지 않는다.
-- 정리: destination_cleanup.sql.
--
-- 실행: docker exec -i school-bus-postgres-1 psql -U schoolbus -d schoolbus_load -q [-v per_bus=12] < sql/destination_seed.sql
\if :{?per_bus}
\else
    \set per_bus 12
\endif
\set seed_password_hash '$2a$10$Noeszx0nzJUfNo4ubCD03eNfMVfMD9feMo04y/8DHiFLUBF.JZ/Fq'

BEGIN;

INSERT INTO stop (academy_id, name, address, lat, lng)
SELECT a.id, 'L3DEST-' || a.code || '-S0', 'L3DEST 주소', a.lat, a.lng
FROM academy a
WHERE a.code LIKE 'LOADCAP-%'
  AND NOT EXISTS (SELECT 1 FROM stop s WHERE s.academy_id = a.id AND s.name = 'L3DEST-' || a.code || '-S0');

INSERT INTO student (academy_id, name, student_phone, grade, can_go_alone)
SELECT b.academy_id, 'L3DEST-' || substr(b.bus_no, 9) || '-V' || lpad(k::text, 2, '0'), '010-3000-0000', '초3', false
FROM bus b
CROSS JOIN generate_series(1, :per_bus) AS k
WHERE b.bus_no LIKE 'LOADCAP-%'
  AND NOT EXISTS (SELECT 1 FROM student s WHERE s.name = 'L3DEST-' || substr(b.bus_no, 9) || '-V' || lpad(k::text, 2, '0'));

INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status)
SELECT s.academy_id, lower(s.name) || '-par', :'seed_password_hash', s.name || '-PAR', '010-4000-0000', 'parent', 'active'
FROM student s WHERE s.name LIKE 'L3DEST-%'
ON CONFLICT (login_id) DO NOTHING;

INSERT INTO guardian (academy_id, account_id, name, phone)
SELECT ac.academy_id, ac.id, ac.name, '010-4000-0000'
FROM account ac
WHERE ac.login_id LIKE 'l3dest-%-par'
  AND NOT EXISTS (SELECT 1 FROM guardian g WHERE g.account_id = ac.id);

INSERT INTO guardian_student (guardian_id, student_id, linked_at)
SELECT g.id, s.id, now()
FROM guardian g
JOIN account ac ON ac.id = g.account_id
JOIN student s ON lower(s.name) || '-par' = ac.login_id
WHERE ac.login_id LIKE 'l3dest-%-par'
ON CONFLICT (guardian_id, student_id) DO NOTHING;

COMMIT;

SELECT 'l3dest_student' AS what, count(*) AS n FROM student WHERE name LIKE 'L3DEST-%'
UNION ALL SELECT 'l3dest_guardian_student', count(*) FROM guardian_student gs JOIN student s ON s.id = gs.student_id WHERE s.name LIKE 'L3DEST-%'
UNION ALL SELECT 'l3dest_stop', count(*) FROM stop WHERE name LIKE 'L3DEST-%';

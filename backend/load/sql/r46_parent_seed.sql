-- R46-LOAD — 학부모 계정 시드. r0_capacity_seed.sql 뒤에 한 번 더 실행한다(다시 실행해도 겹치지 않는다).
-- 학생마다 보호자 1명(학부모 계정 + guardian + guardian_student) — 조사 D 의 가정 "학생당 보호자 약 0.95명" 에서 5% 많다.
-- 학부모 앱 홈 폴링(GET /students/{id}/runs · /change-requests · /notifications)과 학생 채널 구독
-- (/topic/students/{id}/run)은 보호자 연결이 있어야 인가를 통과한다.
--
-- 이름 규칙: account.login_id = lower(student.name) || '-par' (비밀번호 평문 "password").
--
-- 실행: docker exec -i school-bus-postgres-1 psql -U schoolbus -d schoolbus_load -q < sql/r46_parent_seed.sql

\set seed_password_hash '$2a$10$Noeszx0nzJUfNo4ubCD03eNfMVfMD9feMo04y/8DHiFLUBF.JZ/Fq'

BEGIN;

INSERT INTO account (academy_id, login_id, password_hash, name, phone, role, status)
SELECT s.academy_id, lower(s.name) || '-par', :'seed_password_hash', s.name || '-PAR', '010-4000-0000', 'parent', 'active'
FROM student s WHERE s.name LIKE 'LOADCAP-%'
ON CONFLICT (login_id) DO NOTHING;

INSERT INTO guardian (academy_id, account_id, name, phone)
SELECT ac.academy_id, ac.id, ac.name, '010-4000-0000'
FROM account ac
WHERE ac.login_id LIKE 'loadcap-%-par'
  AND NOT EXISTS (SELECT 1 FROM guardian g WHERE g.account_id = ac.id);

INSERT INTO guardian_student (guardian_id, student_id, linked_at)
SELECT g.id, s.id, now()
FROM guardian g
JOIN account ac ON ac.id = g.account_id
JOIN student s ON lower(s.name) || '-par' = ac.login_id
WHERE ac.login_id LIKE 'loadcap-%-par'
ON CONFLICT (guardian_id, student_id) DO NOTHING;

COMMIT;

SELECT 'parent_account' AS what, count(*) AS n FROM account WHERE login_id LIKE 'loadcap-%-par'
UNION ALL SELECT 'guardian', count(*) FROM guardian g JOIN account ac ON ac.id = g.account_id WHERE ac.login_id LIKE 'loadcap-%-par'
UNION ALL SELECT 'guardian_student', count(*) FROM guardian_student gs JOIN guardian g ON g.id = gs.guardian_id
          JOIN account ac ON ac.id = g.account_id WHERE ac.login_id LIKE 'loadcap-%-par';

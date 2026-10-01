-- 퇴원 학생 개인정보 파기(R46 privacy · Ruling 480 ②·520) — 퇴원 90일 뒤 개인 필드를 익명값으로 바꾸고 그 시각을 남긴다.
-- 학생 행은 지우지 않는다: run_rider · boarding_intent · change_request · run_forced_addition 이 ON DELETE RESTRICT 로
-- 이 행을 참조하고, 승하차 이력은 무기한 보존(ERD §7.1)이라 행을 남겨 두고 알아볼 수 있는 값만 지운다.
-- anonymized_at 이 없으면 매 실행이 이미 익명화한 학생을 다시 대상으로 잡아 멱등성과 파기 건수가 거짓이 된다.
ALTER TABLE student ADD COLUMN anonymized_at timestamptz;

-- 파기 배치가 "퇴원했고 아직 익명화 안 된 학생" 만 컷오프 순서로 훑는 부분 인덱스(V8 의 보존 인덱스와 같은 형태).
CREATE INDEX ix_student_retention_cutoff ON student (deleted_at)
    WHERE deleted_at IS NOT NULL AND anonymized_at IS NULL;

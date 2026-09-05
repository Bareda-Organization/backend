-- 미승차 대기 시간 상한(X-06, Ruling 257, F4 목표 9) — 기존 CHECK 는 하한(> 0)만 있고 상한이
-- 없었다. 상한을 "①/② 구간 경계"(회차 확정 창, 출발 30분 전)와 같은 30분으로 못 박는다 —
-- 대기 시간이 그 경계를 넘기면 회차 운행 자체와 겹쳐 의미를 잃는다(Ruling 257 근거).
ALTER TABLE academy_setting DROP CONSTRAINT ck_academy_setting_wait_minutes;
ALTER TABLE academy_setting ADD CONSTRAINT ck_academy_setting_wait_minutes
    CHECK (no_show_wait_minutes > 0 AND no_show_wait_minutes <= 30);

-- 승하차 되돌리기(BRD-05, API_SPEC §4.7) 정정 알림 종류 추가(목표 13·14, Ruling 219).
--
-- Ruling 219 확정 정책 — 이미 나간 승차·하차 알림은 고치거나 지우지 않고, 되돌리기가 일어나면
-- 별도의 정정 알림을 새로 적재한다. 승차 취소·하차 취소를 서로 다른 문구로 내야 해서(목표 14)
-- 값도 2종으로 나눈다.
--
-- Postgres 는 CHECK 에 값을 덧붙일 수 없어 DROP 후 같은 이름으로 다시 ADD 한다 — 이 파일이
-- V5 의 19종 정의를 대체하므로, 이 제약을 대조하는 시험은 V1·V5 가 아니라 이 파일(가장 나중에
-- 같은 이름을 정의한 마이그레이션)을 기준으로 삼아야 한다. V1 을 베이스로 새로 쓰지 않는다 —
-- V1 에는 V5 가 보강한 'exception_reported' 가 없어, 그대로 베끼면 그 값이 다시 빠진다.
ALTER TABLE notification_log DROP CONSTRAINT ck_notification_log_type;

ALTER TABLE notification_log ADD CONSTRAINT ck_notification_log_type CHECK (type IN (
    'boarding', 'alighting', 'no_show', 'absent', 'arrive', 'delay',
    'run_started', 'run_ended', 'signup_decided', 'change_decided',
    'approval_requested', 'intent_changed', 'link_requested', 'route_changed',
    'assignment_changed', 'no_show_escalated', 'emergency', 'emergency_canceled',
    'exception_reported', 'boarding_canceled', 'alighting_canceled'));

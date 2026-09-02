-- 예외 보고(EXC-02·EXC-03, API_SPEC §4.13) 접수 시 관계자에게 즉시 통지하기 위한 알림 종류 추가.
-- §4.13 은 통지를 요구하는데 §9.7 표에는 대응 값이 없었다 — 표가 기능을 옮겨 적을 때 빠뜨린
-- 정본 내부 불일치이며, 기능 요건이 이긴다(Phase 11 T3 판정, team-lead 독립 확인 완료).
--
-- Postgres 는 CHECK 에 값을 덧붙일 수 없어 DROP 후 같은 이름으로 다시 ADD 한다 — 이 파일이
-- V1 의 18종 정의를 대체하므로, 이 제약을 대조하는 시험은 V1 이 아니라 이 파일(가장 나중에
-- 같은 이름을 정의한 마이그레이션)을 기준으로 삼아야 한다.
ALTER TABLE notification_log DROP CONSTRAINT ck_notification_log_type;

ALTER TABLE notification_log ADD CONSTRAINT ck_notification_log_type CHECK (type IN (
    'boarding', 'alighting', 'no_show', 'absent', 'arrive', 'delay',
    'run_started', 'run_ended', 'signup_decided', 'change_decided',
    'approval_requested', 'intent_changed', 'link_requested', 'route_changed',
    'assignment_changed', 'no_show_escalated', 'emergency', 'emergency_canceled',
    'exception_reported'));

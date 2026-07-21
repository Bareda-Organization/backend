-- G3(보호자 인계완료 알림): NotificationType 에 HANDOVER_DONE 을 추가하면서 V1 이 Hibernate DDL로
-- 생성한 notification_log.type CHECK 제약을 그대로 두면 알림 저장이 막힌다(V3 와 동일한 문제).
alter table notification_log
    drop constraint notification_log_type_check;

alter table notification_log
    add constraint notification_log_type_check
    check ((type in ('BOARD_DONE', 'ALIGHT_DONE', 'HANDOVER_DONE', 'APPROACH', 'NO_SHOW', 'SOS',
                      'SCHEDULE_RESULT', 'CONNECTION_LOST', 'ROUTE_RECOMMENDED')));

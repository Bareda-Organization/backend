-- G3(보호자 인계완료 상태 구분): RideType 에 HANDOVER 를 추가하면서 V1 이 Hibernate DDL로 생성한
-- ride_event.type CHECK 제약(BOARD/ALIGHT만 허용)을 그대로 두면 HANDOVER 기록이 막힌다.
alter table ride_event
    drop constraint ride_event_type_check;

alter table ride_event
    add constraint ride_event_type_check
    check ((type in ('BOARD', 'ALIGHT', 'HANDOVER')));

-- 조회 인덱스 보강 (2026-08-23 리뷰 ⑧)
--
-- PostgreSQL 은 외래키 컬럼에 인덱스를 자동으로 만들지 않는다. 이 시스템은 거의 모든 조회에
-- tenant_id 필터가 붙는데 인덱스가 없어 순차 스캔이 됐다.
--
-- 이미 unique 제약으로 인덱스가 생기는 컬럼은 넣지 않았다:
--   user_tenant_role(user_id, tenant_id, role) → user_id 선두 인덱스 존재
--   student_guardian(student_id, guardian_id)  → student_id 커버, guardian_id 는 미커버라 아래에 추가
--
-- 계획서 후보 15종 중 bus(route_id) 는 뺐다. bus 는 본질적으로 작은 테이블이다(학원당 수십 대,
-- 현 시드 실측 bus=3건) — 작은 테이블은 인덱스가 있어도 플래너가 순차 스캔을 고른다.
-- StudentRepository.countByAssignedBus_Route_IdAndActiveTrue(Task 5) 가 bus.route_id 로
-- 필터링하는 조인을 실제로 쓰지만, 이 조인에서 선택도가 높은 쪽은 student.bus_id 이고 그
-- 인덱스(idx_student_bus)는 이 파일에 이미 포함돼 있다. 버스가 수천 대 규모가 되면 그때
-- bus(route_id) 인덱스를 별도 마이그레이션으로 추가한다.

create index idx_student_tenant_active   on student (tenant_id, active);
create index idx_student_bus             on student (bus_id);
create index idx_student_user            on student (user_id);
create index idx_bus_tenant              on bus (tenant_id);
create index idx_route_tenant            on route (tenant_id);
create index idx_stop_route_seq          on stop (route_id, seq);
create index idx_guardian_by_guardian    on student_guardian (guardian_id);
create index idx_drive_session_tenant    on drive_session (tenant_id, service_date);
create index idx_drive_session_bus       on drive_session (bus_id);
create index idx_attendance_student_date on attendance_exception (student_id, target_date);
create index idx_schedule_req_student    on schedule_change_request (student_id);
create index idx_location_req_student    on location_change_request (student_id);
create index idx_notification_tenant     on notification_log (tenant_id, student_id);
create index idx_route_plan_tenant_date  on route_plan (tenant_id, service_date);

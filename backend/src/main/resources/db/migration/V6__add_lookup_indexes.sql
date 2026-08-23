-- 조회 인덱스 보강 (2026-08-23 리뷰 ⑧)
--
-- PostgreSQL 은 외래키 컬럼에 인덱스를 자동으로 만들지 않는다. 이 시스템은 거의 모든 조회에
-- tenant_id 필터가 붙는데 인덱스가 없어 순차 스캔이 됐다.
--
-- 이미 unique 제약으로 인덱스가 생기는 컬럼은 넣지 않았다:
--   user_tenant_role(user_id, tenant_id, role) → user_id 선두 인덱스 존재
--   student_guardian(student_id, guardian_id)  → student_id 커버, guardian_id 는 미커버라 아래에 추가
--
-- 계획서 후보 15종 중 bus(route_id) 는 뺐다 — BusRepository 및 전체 호출부를 훑어도
-- Bus 를 route_id 로 필터링하는 조회가 없다(Route 조회는 RouteRepository.findById 로 Route.id 를
-- 직접 찾는 것이라 Bus.route_id 인덱스와 무관하다). 근거 없는 인덱스는 쓰기 비용만 늘린다.

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

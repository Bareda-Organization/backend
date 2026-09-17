-- PREVIEW_STALE(§5.6) 재현을 위해 시드에 없던 from_academy 노선을 추가한다.
--
-- 근거: 대기(pending) change_request(approval_id=1)가 run_id=2(academy 1, bus 1,
-- direction='from_academy')에 붙어 있는데, V2 시드의 유일한 route 행은
-- direction='to_academy' 뿐이다. ApprovalQueryService.detail() 의
-- (academy, bus, weekday, direction) 4중 일치 관문이 이 행을 찾지 못해
-- 422 ROUTE_NOT_CONFIGURED_FOR_RUN 으로 막히고, preview_token 발급 단계
-- (ApprovalPreviewResolver.resolvePreview())에 도달하지 못한다(Ruling 299).
--
-- V2__seed_data.sql 은 이미 두 DB(schoolbus·schoolbus_load)에 체크섬 고정으로
-- 적용돼 있어 고칠 수 없다 — 그래서 새 버전 파일로 공백을 메운다.
-- 기존 to_academy 행은 UPDATE 하지 않는다(다른 검사가 그 값에 기댄다) — 새
-- from_academy 행만 INSERT 로 더한다. uk_route_bus_weekday_direction 은
-- (bus_id, weekday, direction) 이 키라 direction 이 다르면 기존 행과 충돌하지 않는다.
--
-- route_stop 도 함께 필요하다 — ApprovalPreviewResolver.originDestinationOf() 가
-- direction='from_academy' 일 때 "학원 → 마지막 정차지" 를 origin/destination 으로
-- 쓰므로, route_stop 이 비어 있으면 그 단계에서 다시 막힌다. 기존 route(id=1)와
-- 같은 academy 1 소속 정류장(stop 1·2)을 재사용한다 — stop 은 route 간 공유 가능
-- (FK 만 있고 route 전용 소유가 아니다).
WITH new_route AS (
    INSERT INTO route (academy_id, bus_id, weekday, direction, name, active, created_at, updated_at)
    VALUES (
        1,
        1,
        (ARRAY['sun','mon','tue','wed','thu','fri','sat'])[extract(dow from now())::int + 1],
        'from_academy',
        '본선(하원)',
        true,
        now(),
        now()
    )
    RETURNING id
)
INSERT INTO route_stop (route_id, stop_id, seq)
SELECT id, stop_id, seq
FROM new_route
CROSS JOIN (VALUES (1, 1), (2, 2)) AS s(stop_id, seq);

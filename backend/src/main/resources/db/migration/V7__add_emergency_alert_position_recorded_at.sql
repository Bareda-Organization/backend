-- 비상 알림 응답의 position.recorded_at 출처 컬럼 신설(API_SPEC §5.16, Ruling 236).
--
-- 정본이 요구하는 position{lat, lng, recorded_at} 중 recorded_at(위치 발신 장비가 찍은 시각)의
-- 출처가 emergency_alert 에 없었다 — occurred_at·received_at 은 신고 자체의 시각이지 위치
-- 스냅샷의 시각이 아니다(의미가 다르므로 대체 금지, Ruling 236 제약 1).
--
-- nullable 로 둔다 — 기존 행과, 위치 캐시가 없어 attachLocation 이 호출되지 않은 발신은
-- lat·lng 와 마찬가지로 이 컬럼도 비어 있어야 한다.
ALTER TABLE emergency_alert ADD COLUMN position_recorded_at timestamptz;

-- 근접 알림(NTF-04, API_SPEC §4.12 Ruling 207) 최초 1회 발송 판정용 컬럼.
-- notification_log.dedup_key UNIQUE 는 "같은 알림을 두 번 적재하지 않는" 것만 막는다 — 스케줄러가
-- 매 틱 같은 정차 항목을 다시 판정하지 않게 막는 것은 이 컬럼의 조건부 UPDATE 몫이다(Ruling 210).
ALTER TABLE run_stop ADD COLUMN proximity_notified_at timestamptz;

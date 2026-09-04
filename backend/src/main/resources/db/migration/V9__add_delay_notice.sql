-- 지연 알림(NTF-06, API_SPEC §4.9, F3 S1 목표 3) 발신 이력 테이블.
--
-- Ruling 253 이 정한 중복·갱신 규칙 — 같은 회차의 직전 발신과 분·사유·메시지가 전부 같으면
-- 409 로 거부하고, 하나라도 다르면 새 알림을 발신한다(합산하지 않는다) — 을 판정하려면
-- "직전 발신 1건" 을 조회할 수 있어야 한다. notification_log 는 발신 종류·문구·수신자별로
-- 여러 행이 나뉘어 적재돼(Ruling 253 판정에 필요한 "이 회차의 마지막 지연 신고 1건"이라는
-- 단위와 맞지 않는다) 전용 테이블을 둔다 — 발신 1회 = 이 테이블 1행.
CREATE TABLE delay_notice (
    id                 bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id             bigint      NOT NULL,
    sent_by_account_id bigint      NOT NULL,
    minutes            integer     NOT NULL,
    reason             varchar(20) NOT NULL,
    message            varchar(500),
    sent_at            timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_delay_notice_run FOREIGN KEY (run_id) REFERENCES run (id) ON DELETE CASCADE,
    CONSTRAINT fk_delay_notice_account FOREIGN KEY (sent_by_account_id) REFERENCES account (id) ON DELETE RESTRICT,
    CONSTRAINT ck_delay_notice_minutes CHECK (minutes > 0 AND minutes % 5 = 0),
    CONSTRAINT ck_delay_notice_reason CHECK (reason IN ('traffic', 'weather', 'vehicle_check', 'prev_stop_wait'))
);

-- 같은 회차의 "직전 발신" 을 sent_at 내림차순 1건으로 찾는 조회(DelayNoticeRepository) 를 지원한다.
CREATE INDEX idx_delay_notice_run_sent_at ON delay_notice (run_id, sent_at DESC);

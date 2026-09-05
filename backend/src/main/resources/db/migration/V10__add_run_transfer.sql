-- 버스 간 이동(RTE-07, API_SPEC §5.8, F4 S1 목표 6, Ruling 256) 대기소.
--
-- run_forced_addition 과 같은 "대기 후 배치 합류" 형태다 — 신청 즉시 재최적화를 부르지 않고,
-- 출발·도착 두 회차 중 먼저 도는 확정 배치(RunConfirmationService.confirmOne)가 그 회차 쪽
-- 절반(제외 또는 추가)을 반영한다. 두 회차의 확정 시점이 다를 수 있어 상태를 staged/applied
-- 로 나눈다 — 다만 배치 처리는 상태로 대상을 거르지 않고 항상 재계산하므로(자기 치유) 이
-- 상태는 재시도 정합을 위한 필수 조건이 아니라 조회 편의를 위한 기록이다.
CREATE TABLE run_transfer (
    id                      bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    student_id              bigint      NOT NULL,
    from_run_id             bigint      NOT NULL,
    to_run_id               bigint      NOT NULL,
    stop_id                 bigint,
    note                    text,
    status                  varchar(10) NOT NULL,
    requested_by_account_id bigint      NOT NULL,
    created_at              timestamptz NOT NULL DEFAULT now(),
    applied_at              timestamptz,
    CONSTRAINT fk_run_transfer_student FOREIGN KEY (student_id) REFERENCES student (id) ON DELETE RESTRICT,
    CONSTRAINT fk_run_transfer_from_run FOREIGN KEY (from_run_id) REFERENCES run (id) ON DELETE CASCADE,
    CONSTRAINT fk_run_transfer_to_run FOREIGN KEY (to_run_id) REFERENCES run (id) ON DELETE CASCADE,
    CONSTRAINT fk_run_transfer_stop FOREIGN KEY (stop_id) REFERENCES stop (id) ON DELETE RESTRICT,
    CONSTRAINT ck_run_transfer_status CHECK (status IN ('staged', 'applied'))
);

-- 출발 회차 쪽 확정 배치가 "이 회차에서 빠져나갈 학생" 을 찾는 조회를 지원한다.
CREATE INDEX idx_run_transfer_from_run ON run_transfer (from_run_id);
-- 도착 회차 쪽 확정 배치가 "이 회차로 들어올 학생" 을 찾는 조회를 지원한다.
CREATE INDEX idx_run_transfer_to_run ON run_transfer (to_run_id);
-- 같은 학생의 처리 대기 중인 이동 건 존재 여부(TRANSFER_ALREADY_STAGED 판정)를 지원한다.
CREATE INDEX idx_run_transfer_student_status ON run_transfer (student_id, status);

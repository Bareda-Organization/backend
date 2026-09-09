-- 보존 정리 배치(목표 6·7, ERD §7, Ruling 243)가 컷오프로 훑는 컬럼에 인덱스를 더한다. 인덱스만
-- 추가한다 — 컬럼·테이블 변경은 이 Phase 의 범위 밖이다.
--
-- notification_log·run_position 은 기존 복합 인덱스가 있지만 선행 컬럼이 각각 recipient_account_id·
-- run_id 라, "전 학원·전 회차의 컷오프 이전 행"을 훑는 이 배치의 조회 패턴에는 쓰이지 않는다.
CREATE INDEX ix_notification_log_retention_cutoff ON notification_log (created_at);
CREATE INDEX ix_run_position_retention_cutoff ON run_position (recorded_at);

-- refresh_token 삭제 조건은 폐기됐는가로 갈린다 — 폐기된 토큰은 revoked_at 을, 아직 폐기되지 않은
-- 토큰은 expires_at 을 견준다(RetentionPolicy#refreshTokenCutoff). 두 부분 인덱스로 조건을 나눈다.
CREATE INDEX ix_refresh_token_retention_revoked ON refresh_token (revoked_at) WHERE revoked_at IS NOT NULL;
CREATE INDEX ix_refresh_token_retention_expires ON refresh_token (expires_at) WHERE revoked_at IS NULL;

CREATE INDEX ix_link_code_retention_expires ON link_code (expires_at);
CREATE INDEX ix_link_request_retention_expires ON link_request (expires_at);

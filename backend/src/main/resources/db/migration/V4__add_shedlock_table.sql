-- ShedLock 이 쓰는 분산 락 테이블(TECH_DECISIONS §3.2) — 컬럼명·타입은 shedlock-provider-jdbc-template
-- 기본 스키마를 그대로 따른다. 인스턴스가 늘어도 같은 @Scheduled 배치가 중복 수행되지 않도록
-- 이 테이블 행 1개당 락 이름 1개를 잠근다.
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);

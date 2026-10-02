-- 쿼리별 실행 통계 확장 — compose 의 `shared_preload_libraries=pg_stat_statements` 와 짝이다(R46-FIXOPS · review-idx I-07).
-- 조회: SELECT query, calls, total_exec_time, mean_exec_time FROM ops_stats.pg_stat_statements ORDER BY total_exec_time DESC LIMIT 10;
--
-- public 이 아니라 전용 스키마에 둔다 — 스테이징은 백엔드 기동·초기화마다 Flyway clean() 이 public 을 비우는데 public 에 만든 확장은 그때
-- 같이 지워진다(2026-10-01 로컬 실측: public 에서 사라지고 ops_stats 에서 남음). 운영·스테이징이 같은 이름으로 조회하도록 한 곳으로 통일한다.
-- 이 스크립트는 빈 데이터 디렉터리로 처음 뜰 때만 돈다(공식 postgres 이미지 규칙) — 이미 초기화된 볼륨은 docs/backend/infra/DEPLOYMENT.md §11.5 의 한 줄로 만든다.
CREATE SCHEMA IF NOT EXISTS ops_stats;
CREATE EXTENSION IF NOT EXISTS pg_stat_statements SCHEMA ops_stats;

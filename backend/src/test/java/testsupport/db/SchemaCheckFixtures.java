package testsupport.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * CHECK 제약 단언이 쓸 **정상** 부모 행을 채우는 INSERT 모음.
 *
 * <p>준비 INSERT 가 전부 성공해야만 뒤이은 위반 INSERT 의 실패를 "CHECK 가 잡았다" 로 읽을 수 있다 —
 * 부모가 없으면 FK 위반으로 실패해 두 원인이 구분되지 않는다. 그래서 여기 담긴 값은 전부
 * 제약을 통과하는 값이며, 위반 값은 단언 쪽(SchemaContractTest)이 만든다.
 */
final class SchemaCheckFixtures {

    private SchemaCheckFixtures() {
    }

    /** 학원 1건. 나머지 픽스처 전부의 최상위 부모다. */
    static long insertAcademy(Connection connection) throws SQLException {
        return insertReturningId(connection, """
                INSERT INTO academy (code, name, region, status)
                VALUES ('CONTRACT-TEST', '대조테스트학원', '서울', 'active')
                RETURNING id
                """);
    }

    /** 계산식(`student_capacity = capacity - driver_count - escort_count`)을 만족하는 차량 1건. */
    static long insertBus(Connection connection, long academyId) throws SQLException {
        return insertReturningId(connection, """
                INSERT INTO bus (academy_id, bus_no, plate_no, capacity, driver_count, escort_count, student_capacity)
                VALUES (%d, '1호차', '12가3456', 25, 1, 1, 23)
                RETURNING id
                """.formatted(academyId));
    }

    /** 확정 예정 시각이 출발 30분 전인 회차 1건. */
    static long insertRun(Connection connection, long academyId, long busId) throws SQLException {
        return insertReturningId(connection, """
                INSERT INTO run (academy_id, bus_id, service_date, direction, depart_time, confirm_at,
                                 status, origin_name, destination_name)
                VALUES (%d, %d, DATE '2026-09-01', 'to_academy',
                        TIMESTAMPTZ '2026-09-01 08:00:00+09', TIMESTAMPTZ '2026-09-01 07:30:00+09',
                        'idle', '중앙로 집결지', '바래다학원')
                RETURNING id
                """.formatted(academyId, busId));
    }

    /** 회차의 확정 노선과 첫 배포 버전. `run_stop` 의 부모라 두 행을 함께 만든다. */
    static long insertRouteVersion(Connection connection, long runId) throws SQLException {
        insertReturningId(connection, """
                INSERT INTO confirmed_route (run_id, confirmed_at)
                VALUES (%d, TIMESTAMPTZ '2026-09-01 07:30:00+09')
                RETURNING run_id
                """.formatted(runId));
        return insertReturningId(connection, """
                INSERT INTO route_version (confirmed_route_id, version_no, source,
                                           input_fingerprint, engine_name, policy_snapshot)
                VALUES (%d, 1, 'confirm_batch', 'fingerprint-0001', 'nearest-neighbor',
                        CAST('{"confirm_lead_minutes":30}' AS jsonb))
                RETURNING id
                """.formatted(runId));
    }

    /** 승하차지 마스터 1건. */
    static long insertStop(Connection connection, long academyId) throws SQLException {
        return insertReturningId(connection, """
                INSERT INTO stop (academy_id, name, address, lat, lng)
                VALUES (%d, '중앙로 스타빌딩 앞', '서울시 중앙로 1', 37.566500, 126.978000)
                RETURNING id
                """.formatted(academyId));
    }

    /** 강제 경유지 1건. */
    static long insertWaypoint(Connection connection, long runId) throws SQLException {
        return insertReturningId(connection, """
                INSERT INTO waypoint (run_id, label, lat, lng, created_by)
                VALUES (%d, '임시 집결지', 37.570000, 126.980000, 1)
                RETURNING id
                """.formatted(runId));
    }

    private static long insertReturningId(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet generated = statement.executeQuery()) {
            generated.next();
            return generated.getLong(1);
        }
    }
}

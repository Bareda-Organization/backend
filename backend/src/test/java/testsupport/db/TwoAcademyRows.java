package testsupport.db;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 제약 시험이 쓰는 원시 SQL 행 도우미 — 스키마만 적용한 DB 에 학원 하나분의 부모 행(차량 · 승하차지 · 학생 · 노선 · 회차 둘 · 매니저 ·
 * 보호자 · 노선 버전)을 만들고, 자식 아홉 표의 "모두 같은 학원을 가리키는" 정상 행을 돌려준다. 시험은 이 행의 한 칸만 바꿔 DB 가 막는지 본다.
 *
 * <p>{@link #insert} 는 표에 없는 칸을 빼고 넣는다 — 학원 칸이 아직 없는 스키마에서도 같은 시험이 돌아 "막지 못한다" 로 실패한다.
 */
final class TwoAcademyRows {

    static final OffsetDateTime DEPART = OffsetDateTime.parse("2030-01-07T08:00:00+09:00");

    private static final Map<String, Set<String>> COLUMNS = new java.util.concurrent.ConcurrentHashMap<>();

    private TwoAcademyRows() {
    }

    /** 한 학원의 부모 행 번호 묶음. */
    record Tenant(long academy, long bus, long stop, long student, long route, long run, long run2, long manager,
            long guardian, long version) {

        /** 자식의 참조 칸 이름이 가리키는 이 학원의 부모 번호. */
        long parent(String column) {
            return switch (column) {
                case "route_id" -> route;
                case "stop_id" -> stop;
                case "student_id" -> student;
                case "run_id", "from_run_id" -> run;
                case "to_run_id" -> run2;
                case "manager_id" -> manager;
                case "guardian_id" -> guardian;
                default -> throw new IllegalArgumentException(column);
            };
        }
    }

    /** 코드가 겹치지 않는 학원 하나와 그 부모 행 전부를 커밋된 상태로 만든다. */
    static Tenant tenant(Connection c, String code) throws SQLException {
        long academy = insert(c, "academy", row("code", code, "name", code, "region", "서울", "status", "active"));
        long bus = insert(c, "bus", row("academy_id", academy, "bus_no", "1", "plate_no", code, "capacity", 10,
                "driver_count", 1, "escort_count", 1, "student_capacity", 8));
        long stop = insert(c, "stop", row("academy_id", academy, "name", "s", "address", "a", "lat", 37.5, "lng", 127.0));
        long student = insert(c, "student", row("academy_id", academy, "name", "학생"));
        long account = insert(c, "account", row("academy_id", academy, "login_id", code + "-g", "password_hash", "x",
                "name", "보호자", "phone", "010", "role", "parent", "status", "active"));
        long guardian = insert(c, "guardian", row("academy_id", academy, "account_id", account, "name", "보호자",
                "phone", "010"));
        long manager = insert(c, "manager", row("academy_id", academy, "name", "기사", "phone", "010", "role", "driver"));
        long route = insert(c, "route", row("academy_id", academy, "bus_id", bus, "weekday", "mon",
                "direction", "to_academy"));
        long run = insert(c, "run", validRun(academy, bus, 0));
        long run2 = insert(c, "run", validRun(academy, bus, 1));
        exec(c, "INSERT INTO confirmed_route (run_id, confirmed_at) VALUES (?, ?)", run, DEPART);
        long version = exec(c, "INSERT INTO route_version (confirmed_route_id, version_no, source, input_fingerprint, "
                + "engine_name, policy_snapshot) VALUES (?, 1, 'confirm_batch', 'f', 'e', '{}'::jsonb) RETURNING id", run);
        return new Tenant(academy, bus, stop, student, route, run, run2, manager, guardian, version);
    }

    /** 회차 한 행 — {@code day} 로 운행일을 밀어 유일 제약을 피한다. */
    static Map<String, Object> validRun(long academy, long bus, int day) {
        OffsetDateTime depart = DEPART.plusDays(day);
        return row("academy_id", academy, "bus_id", bus, "service_date", LocalDate.of(2030, 1, 7).plusDays(day),
                "direction", "to_academy", "depart_time", depart, "confirm_at", depart.minusMinutes(30),
                "status", "idle", "origin_name", "o", "destination_name", "d");
    }

    /** 자식 표 하나의 정상 행 — 모든 참조가 {@code t} 한 학원 안에 있다. */
    static Map<String, Object> validChild(String table, Tenant t) {
        return switch (table) {
            case "route_stop" -> row("route_id", t.route, "stop_id", t.stop, "seq", 1, "academy_id", t.academy);
            case "run_stop" -> row("route_version_id", t.version, "stop_id", t.stop, "seq", 1, "academy_id", t.academy);
            case "run_rider" -> row("run_id", t.run, "student_id", t.student, "stop_id", t.stop, "status", "waiting",
                    "academy_id", t.academy);
            case "assignment" -> row("run_id", t.run, "manager_id", t.manager, "role", "driver",
                    "assigned_at", DEPART, "academy_id", t.academy);
            case "guardian_student" -> row("guardian_id", t.guardian, "student_id", t.student, "linked_at", DEPART,
                    "academy_id", t.academy);
            case "weekly_address" -> row("student_id", t.student, "weekday", "mon", "direction", "to_academy",
                    "address", "a", "stop_id", t.stop, "academy_id", t.academy);
            case "run_forced_addition" -> row("run_id", t.run, "student_id", t.student, "stop_id", t.stop,
                    "added_by", 1, "academy_id", t.academy);
            case "boarding_intent" -> row("run_id", t.run, "student_id", t.student, "academy_id", t.academy);
            case "run_transfer" -> row("student_id", t.student, "from_run_id", t.run, "to_run_id", t.run2,
                    "stop_id", t.stop, "status", "staged", "requested_by_account_id", 1, "academy_id", t.academy);
            default -> throw new IllegalArgumentException(table);
        };
    }

    /** 이름·값 쌍을 입력 순서대로 담은 행. */
    static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    /** 행을 넣고 새 id 를 돌려준다. 표에 없는 칸은 뺀다. */
    static long insert(Connection c, String table, Map<String, Object> row) throws SQLException {
        Set<String> existing = columns(c, table);
        List<String> names = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        row.forEach((name, value) -> {
            if (existing.contains(name)) {
                names.add(name);
                values.add(value);
            }
        });
        String marks = String.join(", ", java.util.Collections.nCopies(names.size(), "?"));
        return exec(c, "INSERT INTO " + table + " (" + String.join(", ", names) + ") VALUES (" + marks
                + ") RETURNING id", values.toArray());
    }

    private static long exec(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                statement.setObject(i + 1, args[i]);
            }
            if (sql.contains("RETURNING")) {
                try (ResultSet rows = statement.executeQuery()) {
                    rows.next();
                    return rows.getLong(1);
                }
            }
            return statement.executeUpdate();
        }
    }

    private static Set<String> columns(Connection c, String table) throws SQLException {
        Set<String> found = COLUMNS.get(table);
        if (found != null) {
            return found;
        }
        found = new HashSet<>();
        try (PreparedStatement statement = c.prepareStatement(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = ?")) {
            statement.setString(1, table);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    found.add(rows.getString(1));
                }
            }
        }
        COLUMNS.put(table, found);
        return found;
    }
}

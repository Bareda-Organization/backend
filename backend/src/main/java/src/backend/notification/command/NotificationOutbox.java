package src.backend.notification.command;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;

import src.backend.global.common.enums.Role;
import src.backend.notification.entity.NotificationType;
import src.backend.notification.event.NotificationAppendedEvent;

/**
 * 알림을 아웃박스에 적재하는 <b>유일한 지점</b>(TECH_DECISIONS §7.2) — 상태 변경과 같은 트랜잭션에서
 * {@code push_state='pending'} 행을 남긴다.
 *
 * <p>{@code MANDATORY} 인 것이 이 클래스의 핵심이다. 자기 트랜잭션을 여는 순간 "상태 변경은
 * 롤백됐는데 알림만 남는" 창이 생기고, 그 알림은 <b>일어나지 않은 일</b>을 통지한다. 부를 자리가
 * 아니면 조용히 새 트랜잭션을 여는 대신 기동 실패에 준하는 예외로 알린다.
 *
 * <p><b>적재는 한 문장 {@code INSERT ... ON CONFLICT (dedup_key) DO NOTHING} 이다</b>(R46 T-6 · 중복 판정은 Ruling 622) —
 * 건별 {@code saveAndFlush} 면 수신자 수만큼 문장이 나가 회차 행 잠금을 쥔 시간이 수신자 수에 비례한다(운행 시작 약
 * 40~75건). 같은 {@code dedup_key} 가 이미 있으면 <b>건너뛴다</b> — 예외로 알리면 PostgreSQL 은 UNIQUE 위반 즉시 그
 * 트랜잭션을 중단 상태로 만들어, 알림 키 충돌 하나가 운행 시작·비상 신고 같은 상태 변경까지 통째로 되돌렸다. 같은 키는
 * 같은 알림이 이미 있다는 뜻이라 건너뛰어도 통지는 빠지지 않고, 건너뛴 건수는 경고 로그로 남는다.
 */
@Component
@RequiredArgsConstructor
public class NotificationOutbox {

    private static final Logger log = LoggerFactory.getLogger(NotificationOutbox.class);

    /** 경고 로그에 싣는 건너뛴 키의 최대 수 — 한 묶음이 수십~수백 건이라 전부 싣지 않는다. */
    private static final int LOGGED_SKIPPED_KEYS = 5;

    /** 한 문장에 묶는 행 수 — 행당 바인드 값이 최대 14개라 PostgreSQL 의 문장당 65,535개 상한에서 한참 아래다. */
    private static final int CHUNK_SIZE = 500;

    private static final String INSERT_HEAD = "INSERT INTO notification_log (academy_id, recipient_account_id, "
            + "recipient_name, recipient_role, student_id, student_name, bus_no, run_id, type, title, body, popup, "
            + "dedup_key, created_at) VALUES ";

    /** 멱등은 DB 제약이 보장한다 — 같은 키는 새 행 없이 건너뛰고, 적재된 행의 id · popup · 키를 돌려받는다. */
    private static final String INSERT_TAIL = " ON CONFLICT (dedup_key) DO NOTHING RETURNING id, popup, dedup_key";

    private final EntityManager entityManager;

    private final ApplicationEventPublisher eventPublisher;

    private final Clock clock;

    /**
     * 발송 대기 행 1건을 적재한다 — {@link #appendAll} 의 단건 형태다.
     *
     * @return 적재된 행의 식별자 — 같은 {@code dedup_key} 가 이미 있어 건너뛰었으면 비어 있다
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<Long> append(NotificationDraft draft) {
        return appendAll(List.of(draft)).stream().findFirst();
    }

    /**
     * 발송 대기 행을 한 번에 적재하고 커밋 후 즉시 발송을 예약한다.
     *
     * <p>{@link NotificationAppendedEvent} 를 여기서 발행하는 이유는 적재한 쪽만 행 식별자를 알기
     * 때문이다 — 리스너가 {@code dedup_key} 로 다시 찾게 하면 같은 키의 옛 행을 집을 수 있다. 건너뛴 행은 새로 만들어진
     * 것이 아니라 이미 발송 절차에 올라 있으므로 이벤트를 다시 내지 않는다.
     *
     * <p>중복 판정을 "조회 후 저장" 으로 하지 않는 이유는 TECH_DECISIONS §9.2 다 — {@code exists} 와 {@code save} 사이에
     * 두 요청이 함께 통과하는 창이 남는다. <b>멱등은 DB 제약이 보장하고 코드는 건너뛴 건수만 센다.</b> 동시에 같은 키를
     * 넣는 두 트랜잭션은 뒤쪽이 앞쪽의 커밋을 기다린 뒤 건너뛴다.
     *
     * @return 새로 적재된 행의 식별자들(건너뛴 행은 없다)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<Long> appendAll(List<NotificationDraft> drafts) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Appended> appended = new ArrayList<>();
        for (int from = 0; from < drafts.size(); from += CHUNK_SIZE) {
            appended.addAll(insert(drafts.subList(from, Math.min(from + CHUNK_SIZE, drafts.size())), now));
        }
        int skipped = drafts.size() - appended.size();
        if (skipped > 0) {
            log.warn("[outbox] 같은 dedup_key 가 이미 있어 {}건의 적재를 건너뛴다(멱등). 건너뛴 키(앞 {}개)={}", skipped,
                    LOGGED_SKIPPED_KEYS, skippedKeys(drafts, appended));
        }
        appended.forEach(row -> eventPublisher.publishEvent(new NotificationAppendedEvent(row.id(), row.popup())));
        return appended.stream().map(Appended::id).toList();
    }

    /** 건너뛴 초안의 키 앞부분 — 적재된 키를 하나씩 소진하고 남는 초안이 건너뛴 쪽이라 묶음 안 같은 키는 둘째 이후가 걸린다. */
    private static List<String> skippedKeys(List<NotificationDraft> drafts, List<Appended> appended) {
        Set<String> unmatched = new HashSet<>();
        appended.forEach(row -> unmatched.add(row.dedupKey()));
        return drafts.stream().map(NotificationDraft::dedupKey).filter(key -> !unmatched.remove(key))
                .limit(LOGGED_SKIPPED_KEYS).toList();
    }

    /** 새로 생긴 행의 식별자 · 팝업(비상) 여부(즉시 발송을 어느 실행기에 보낼지 가르는 기준) · 키(건너뛴 키를 가려내는 데 쓴다). */
    private record Appended(Long id, boolean popup, String dedupKey) {
    }

    /** 한 묶음을 한 문장으로 넣고 새로 생긴 행(id · popup · dedup_key)을 돌려받는다 — 비어 있는 값은 문장에 {@code NULL} 로 적는다. */
    private List<Appended> insert(List<NotificationDraft> chunk, OffsetDateTime now) {
        List<Object> values = new ArrayList<>();
        StringBuilder sql = new StringBuilder(INSERT_HEAD);
        for (int i = 0; i < chunk.size(); i++) {
            sql.append(i == 0 ? "(" : ", (").append(rowOf(chunk.get(i), now, values)).append(')');
        }
        sql.append(INSERT_TAIL);

        var query = entityManager.createNativeQuery(sql.toString());
        for (int i = 0; i < values.size(); i++) {
            query.setParameter(i + 1, values.get(i));
        }
        List<?> rows = query.getResultList();
        return rows.stream()
                .map(row -> (Object[]) row)
                .map(row -> new Appended(((Number) row[0]).longValue(), (Boolean) row[1], (String) row[2]))
                .toList();
    }

    /** 한 행의 값 목록 — 값이 있으면 {@code ?n} 으로 묶어 {@code values} 에 쌓고, 없으면 {@code NULL} 을 적는다. */
    private String rowOf(NotificationDraft draft, OffsetDateTime now, List<Object> values) {
        return String.join(", ",
                bind(draft.academyId(), values),
                bind(draft.recipientAccountId(), values),
                bind(draft.recipientName(), values),
                bind(new Role.Db().convertToDatabaseColumn(draft.recipientRole()), values),
                bind(draft.studentId(), values),
                bind(draft.studentName(), values),
                bind(draft.busNo(), values),
                bind(draft.runId(), values),
                bind(new NotificationType.Db().convertToDatabaseColumn(draft.type()), values),
                bind(draft.title(), values),
                bind(draft.body(), values),
                bind(NotificationType.EMERGENCY_TYPES.contains(draft.type()), values),
                bind(draft.dedupKey(), values),
                bind(now, values));
    }

    private static String bind(Object value, List<Object> values) {
        if (value == null) {
            return "NULL";
        }
        values.add(value);
        return "?" + values.size();
    }
}

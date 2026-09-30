package src.backend.audit.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.extern.slf4j.Slf4j;

import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.audit.entity.AuditLog;
import src.backend.audit.repository.AuditLogRepository;
import src.backend.global.request.ClientIp;

/**
 * L3 응답 조회를 감사 트랜잭션과 분리해서 적재한다(Phase 14 T1 목표 1, Ruling 242 "조회 트랜잭션과
 * 분리").
 *
 * <p><b>{@code REQUIRES_NEW} 를 고른 이유</b>(자바독에 근거를 남기라는 지시, Ruling 242) —
 * {@link src.backend.account.command.AccountUnblockCommandService} 는 상태 전이와 감사를 <b>같은</b>
 * 트랜잭션에 묶지만, 그쪽은 "감사가 없으면 그 상태 전이 자체가 무의미"한 관계(해제 처리는 감사 기록이
 * 곧 처리자 증빙)인 반면 이 클래스가 감싸는 대상(L3 응답 조회)은 <b>읽기 전용</b>이라 이미 일어난
 * 조회를 감사가 못 남겼다고 되돌릴 것이 없다. 오히려 반대 방향의 사고가 더 크다 — 감사 적재가
 * 조회 트랜잭션 안에 있으면, 감사 테이블에 잠금 경합 같은 문제가 생겼을 때 <b>정상 조회 응답까지</b>
 * 함께 실패한다. 이벤트 발행(비동기) 대신 동기 {@code REQUIRES_NEW} 를 고른 이유는 이 팀이 아직
 * 이벤트 브로커·아웃박스 인프라를 갖추지 않아서다 — 비동기로 가면 유실 시 재시도 수단이 없어
 * "감사가 조회를 막지 않는다" 는 요건은 만족해도 "감사가 조용히 사라진다" 는 새 위험을 만든다.
 * 실패는 예외를 던지지 않고 로그만 남긴다 — 이 서비스를 부르는 조회 서비스가 그 실패로 응답을
 * 실패시키면 안 되기 때문이다(같은 이유의 연장).
 *
 * <p><b>새 트랜잭션 경계가 {@code try} 의 안쪽이어야 한다</b>(BR-038). 메서드에 {@code REQUIRES_NEW} 를
 * 붙이고 본문에서 예외를 삼키면, 저장소 안에서 난 예외가 이미 그 트랜잭션에 롤백 표시를 남겨 메서드를 나설
 * 때 커밋이 {@code UnexpectedRollbackException} 을 조회 쪽으로 던진다 — 약속과 반대로 감사 실패가 조회를
 * 실패시킨다. 그래서 {@link TransactionTemplate} 으로 경계를 열고 그 바깥에서 잡는다. 커넥션을 얻지 못한
 * 경우({@code CannotCreateTransactionException})도 같은 {@code catch} 가 받는다.
 *
 * <p><b>반복 조회는 묶는다</b>(R46 감사 A · Ruling 445) — 같은 행위자가 같은 학생을 {@link #DATA_ACCESS_DEDUP_WINDOW}
 * 안에 다시 조회하면 그 학생의 행을 더 쓰지 않는다. 폴링 화면(7초마다 명단 재조회)이 하루 수만 행을 쌓지 않게
 * 하는 것이 목적이고, 판단은 <b>서버가 한다</b> — 화면이 보내는 표시로 거르면 요청을 직접 만들어 기록을 피할 수 있다.
 * 키는 <b>행위자·학생</b>이고 시간은 <b>마지막으로 기록한 시각</b>이다(마지막으로 본 시각이 아니다 — 그러면 계속
 * 보고 있는 동안 기록이 영영 안 남는다). 명단 단위 키로 잡으면 두 번째 조회에 새로 실린 학생이 빠지므로, 명단은 새로
 * 실린 학생만 골라 {@code detail.student_ids} 에 담아 쓴다. 저장소는 메모리다 — 백엔드 인스턴스가 1개라는 배포
 * 전제(CLAUDE.md)를 따르고, 재기동 때 한 번 더 기록되는 것은 허용한다(기록이 줄어드는 쪽이 아니라 느는 쪽 오차).
 * 수정·삭제·로그인 기록은 이 클래스를 거치지 않으므로 묶이지 않는다.
 */
@Service
@Slf4j
public class AuditRecorder {

    /** 같은 행위자·학생의 반복 조회를 묶는 시간 — 이 안에 다시 조회하면 새 행을 쓰지 않는다(Ruling 445). */
    public static final Duration DATA_ACCESS_DEDUP_WINDOW = Duration.ofMinutes(10);

    /** {@code detail} 에서 응답에 실린 학생 id 를 담는 키 — 묶기 키(행위자·학생)의 학생 쪽이다. */
    private static final String STUDENT_IDS_KEY = "student_ids";

    /** 묶기 기록이 이만큼 쌓이면 창이 지난 것을 치운다 — 창 안의 행위자·학생 수가 이 값을 넘을 일은 드물다. */
    private static final int PRUNE_THRESHOLD = 10_000;

    private record ViewedStudent(Long actorAccountId, String studentId) {
    }

    private final AuditLogRepository auditLogRepository;

    private final AccountRepository accountRepository;

    private final Clock clock;

    private final TransactionTemplate requiresNew;

    /** 행위자·학생별로 마지막에 행을 쓴 시각 — {@link #DATA_ACCESS_DEDUP_WINDOW} 가 지나면 쓸모가 없어 지운다. */
    private final Map<ViewedStudent, Instant> lastRecordedAt = new ConcurrentHashMap<>();

    public AuditRecorder(AuditLogRepository auditLogRepository, AccountRepository accountRepository, Clock clock,
            PlatformTransactionManager transactionManager) {
        this.auditLogRepository = auditLogRepository;
        this.accountRepository = accountRepository;
        this.clock = clock;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * L3 필드가 실린 응답 조회 1건을 감사 1행으로 남긴다(목표 1) — 조회 트랜잭션이 실패해도 이미 커밋된
     * 감사 행은 남고, 감사 적재가 실패해도 조회 트랜잭션은 커밋된다(위 클래스 자바독).
     *
     * <p>{@code actorLoginId} 스냅샷은 이 메서드가 {@code actorAccountId} 로 다시 조회해 채운다 —
     * {@link src.backend.global.security.AuthUser} 에는 그 필드가 없다({@code accountId}·
     * {@code academyId}·{@code role}·{@code status} 뿐).
     *
     * @param academyId  감사 대상(응답에 실린 자원)의 소속 학원. 그 자원 자체가 플랫폼 전역(예: 메인
     *                   관리자 콘솔이 조회한 특정 학원 회차)이면 호출부가 그 학원 id 를 넘긴다 — 요청자의
     *                   {@code academyId} 가 아니다
     * @param targetType {@code student}·{@code run_roster} 등 Ruling 242 값 도메인
     */
    public void recordDataAccessRead(Long academyId, Long actorAccountId, String targetType, Long targetId,
            Map<String, Object> detail) {
        recordDataAccessReads(academyId, actorAccountId, targetType, Map.of(targetId, detail));
    }

    /**
     * L3 를 싣는 <b>목록</b> 응답의 감사 — 실린 자원마다 1행을 남기되(Ruling 333) 트랜잭션·계정 조회는 <b>한 번</b>
     * 이다(BR-213). 자원마다 {@link #recordDataAccessRead} 를 부르면 페이지 100건이 트랜잭션 100개·계정 SELECT 100회가
     * 되고, 그동안 바깥 읽기 트랜잭션이 커넥션을 쥔 채 두 번째 커넥션을 기다린다.
     *
     * @param detailByTargetId 자원 id → 그 행의 {@code detail}. 삽입 순서대로 적재한다. 비어 있으면 아무것도 하지 않는다
     */
    public void recordDataAccessReads(Long academyId, Long actorAccountId, String targetType,
            Map<Long, Map<String, Object>> detailByTargetId) {
        Instant now = clock.instant();
        pruneExpired(now);
        List<ViewedStudent> claimed = new ArrayList<>();
        Map<Long, Map<String, Object>> toWrite = new LinkedHashMap<>();
        detailByTargetId.forEach((targetId, detail) -> {
            Map<String, Object> fresh = claimNewStudents(actorAccountId, detail, now, claimed);
            if (fresh != null) {
                toWrite.put(targetId, fresh);
            }
        });
        if (toWrite.isEmpty()) {
            return;
        }
        String ip = ClientIp.ofCurrentRequest();
        try {
            requiresNew.executeWithoutResult(status -> {
                String actorLoginId =
                        accountRepository.findById(actorAccountId).map(Account::getLoginId).orElse(null);
                OffsetDateTime occurredAt = OffsetDateTime.now(clock);
                List<AuditLog> rows = toWrite.entrySet().stream()
                        .map(target -> AuditLog.forDataAccessRead(academyId, actorAccountId, actorLoginId,
                                targetType, target.getKey(), target.getValue(), ip, occurredAt))
                        .toList();
                auditLogRepository.saveAll(rows);
            });
        } catch (RuntimeException e) {
            // 못 쓴 행의 묶기 기록은 물린다 — 남겨 두면 다음 조회가 "이미 기록함" 으로 건너뛰어 기록이 영영 빠진다
            claimed.forEach(viewed -> lastRecordedAt.remove(viewed, now));
            log.error("감사 로그 적재 실패 — 조회 자체는 정상 처리됨. targetType={}, targetIds={}, actorAccountId={}",
                    targetType, detailByTargetId.keySet(), actorAccountId, e);
        }
    }

    /**
     * {@code detail} 에 실린 학생 중 창 안에 이미 기록한 학생을 뺀다 — 새로 기록할 학생이 하나도 없으면 {@code null}.
     * 새로 기록하기로 한 학생은 그 자리에서 {@code claimed} 에 담고 시각을 남긴다(동시에 들어온 같은 조회가 둘 다
     * "새 학생" 으로 판정하지 않게 원자적으로). 학생 목록이 없는 {@code detail} 은 묶지 않고 그대로 쓴다.
     */
    private Map<String, Object> claimNewStudents(Long actorAccountId, Map<String, Object> detail, Instant now,
            List<ViewedStudent> claimed) {
        if (!(detail.get(STUDENT_IDS_KEY) instanceof List<?> studentIds) || studentIds.isEmpty()) {
            return detail;
        }
        List<String> fresh = new ArrayList<>();
        for (Object studentId : studentIds) {
            ViewedStudent viewed = new ViewedStudent(actorAccountId, String.valueOf(studentId));
            boolean[] isNew = {false};
            lastRecordedAt.compute(viewed, (key, last) -> {
                if (last != null && now.isBefore(last.plus(DATA_ACCESS_DEDUP_WINDOW))) {
                    return last;
                }
                isNew[0] = true;
                return now;
            });
            if (isNew[0]) {
                fresh.add(viewed.studentId());
                claimed.add(viewed);
            }
        }
        if (fresh.isEmpty()) {
            return null;
        }
        if (fresh.size() == studentIds.size()) {
            return detail;
        }
        Map<String, Object> narrowed = new LinkedHashMap<>(detail);
        narrowed.put(STUDENT_IDS_KEY, fresh);
        return narrowed;
    }

    private void pruneExpired(Instant now) {
        if (lastRecordedAt.size() > PRUNE_THRESHOLD) {
            lastRecordedAt.values().removeIf(at -> !now.isBefore(at.plus(DATA_ACCESS_DEDUP_WINDOW)));
        }
    }
}

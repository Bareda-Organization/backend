package src.backend.audit.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import lombok.extern.slf4j.Slf4j;

import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.audit.entity.AuditLog;
import src.backend.audit.repository.AuditLogRepository;
import src.backend.audit.service.AuditDedupWindow.ViewedStudent;
import src.backend.global.request.ClientIp;

/**
 * L3 응답 조회를 감사 트랜잭션과 분리해서 적재한다(Ruling 242 "조회 트랜잭션과
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
 * <p><b>반복 조회는 묶는다</b>(Ruling 445) — 같은 행위자가 같은 학생을 {@link #DATA_ACCESS_DEDUP_WINDOW}
 * 안에 다시 조회하면 그 학생의 행을 더 쓰지 않는다. 폴링 화면(7초마다 명단 재조회)이 하루 수만 행을 쌓지 않게
 * 하는 것이 목적이고, 판단은 <b>서버가 한다</b> — 화면이 보내는 표시로 거르면 요청을 직접 만들어 기록을 피할 수 있다.
 * 키는 <b>행위자·학생·조회한 필드 묶음</b>이고(Ruling 521 — 명단 조회가 먼저 창을 열었다고 보호자 원번호 접근이 기록에서 빠지면 안 된다) 시간은 <b>마지막으로 기록한 시각</b>이다(마지막으로 본 시각이 아니다 — 그러면 계속
 * 보고 있는 동안 기록이 영영 안 남는다). 명단 단위 키로 잡으면 두 번째 조회에 새로 실린 학생이 빠지므로, 명단은 새로
 * 실린 학생만 골라 {@code detail.student_ids} 에 담아 쓴다. 저장소는 Redis 다 — 인스턴스가 여러 대여도 같은 창을 쓰고(Ruling 900 E),
 * Redis 를 못 쓰는 동안은 이 인스턴스 안의 기억이 같은 창을 대신하고, 그 기억에서 놓친 조회가 한 번 더 기록되는 것은 허용한다(기록이 줄어드는 쪽이 아니라 느는 쪽 오차).
 * 수정·삭제·로그인 기록은 이 클래스를 거치지 않으므로 묶이지 않는다.
 */
@Service
@Slf4j
public class AuditRecorder {

    /** 같은 행위자·학생의 반복 조회를 묶는 시간 — 이 안에 다시 조회하면 새 행을 쓰지 않는다(Ruling 445). */
    public static final Duration DATA_ACCESS_DEDUP_WINDOW = Duration.ofMinutes(10);

    private final AuditLogRepository auditLogRepository;

    private final AccountRepository accountRepository;

    private final Clock clock;

    private final TransactionTemplate requiresNew;

    /** 반복 조회 묶기 판정·반환 — 인스턴스가 여러 대여도 같은 창을 쓰도록 Redis 에 두고, Redis 를 못 쓰는 동안은 인스턴스 안의 기억으로 대신한다. */
    private final AuditDedupWindow dedup;

    /** L3 조회 감사 행을 못 쓴 횟수 — 응답은 그대로 나가므로(위 클래스 자바독) 이 지표의 경보가 감사 누락을 알리는 유일한 신호다. */
    private final Counter readRecordFailures;

    public AuditRecorder(AuditLogRepository auditLogRepository, AccountRepository accountRepository, Clock clock,
            PlatformTransactionManager transactionManager, StringRedisTemplate redis, MeterRegistry meterRegistry) {
        this.auditLogRepository = auditLogRepository;
        this.accountRepository = accountRepository;
        this.clock = clock;
        this.dedup = new AuditDedupWindow(clock, redis);
        this.readRecordFailures = Counter.builder("schoolbus.audit.read.record.failures")
                .description("L3 조회 감사 행 적재 실패 수").register(meterRegistry);
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * L3 필드가 실린 응답 조회 1건을 감사 1행으로 남긴다 — 조회 트랜잭션이 실패해도 이미 커밋된
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
        List<ViewedStudent> claimed = new ArrayList<>();
        Map<Long, Map<String, Object>> toWrite =
                claimFresh(actorAccountId, detailByTargetId, clock.instant(), claimed);
        if (toWrite.isEmpty()) {
            return;
        }
        String ip = ClientIp.ofCurrentRequest();
        try {
            writeRows(academyId, actorAccountId, targetType, toWrite, ip);
        } catch (RuntimeException e) {
            // 못 쓴 행의 묶기 기록은 물린다 — 남겨 두면 다음 조회가 "이미 기록함" 으로 건너뛰어 기록이 영영 빠진다
            claimed.forEach(dedup::releaseClaim);
            readRecordFailures.increment();
            log.error("감사 로그 적재 실패 — 조회 자체는 정상 처리됨. targetType={}, targetIds={}, actorAccountId={}",
                    targetType, detailByTargetId.keySet(), actorAccountId, e);
        }
    }

    /** 자원마다 묶기 판정을 거쳐 새로 적재할 자원만 {@code detail} 과 함께 모으고, 맡은 항목은 {@code claimed} 에 담는다. */
    private Map<Long, Map<String, Object>> claimFresh(Long actorAccountId,
            Map<Long, Map<String, Object>> detailByTargetId, Instant now, List<ViewedStudent> claimed) {
        Map<Long, Map<String, Object>> toWrite = new LinkedHashMap<>();
        detailByTargetId.forEach((targetId, detail) -> {
            Map<String, Object> fresh = dedup.claimNewStudents(actorAccountId, detail, now, claimed);
            if (fresh != null) {
                toWrite.put(targetId, fresh);
            }
        });
        return toWrite;
    }

    /** 새 트랜잭션(REQUIRES_NEW) 하나로 감사 행들을 적재한다 — 행위자 계정은 한 번만 조회한다. */
    private void writeRows(Long academyId, Long actorAccountId, String targetType,
            Map<Long, Map<String, Object>> toWrite, String ip) {
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
    }
}

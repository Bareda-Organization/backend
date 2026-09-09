package src.backend.audit.service;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import src.backend.account.entity.Account;
import src.backend.account.repository.AccountRepository;
import src.backend.audit.entity.AuditLog;
import src.backend.audit.repository.AuditLogRepository;

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
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditRecorder {

    private final AuditLogRepository auditLogRepository;

    private final AccountRepository accountRepository;

    private final Clock clock;

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
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordDataAccessRead(Long academyId, Long actorAccountId, String targetType, Long targetId,
            Map<String, Object> detail) {
        try {
            String actorLoginId = accountRepository.findById(actorAccountId).map(Account::getLoginId).orElse(null);
            auditLogRepository.save(AuditLog.forDataAccessRead(academyId, actorAccountId, actorLoginId, targetType,
                    targetId, detail, OffsetDateTime.now(clock)));
        } catch (RuntimeException e) {
            log.error("감사 로그 적재 실패 — 조회 자체는 정상 처리됨. targetType={}, targetId={}, actorAccountId={}",
                    targetType, targetId, actorAccountId, e);
        }
    }
}

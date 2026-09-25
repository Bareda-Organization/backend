package src.backend.exception.command;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import src.backend.exception.dto.NoShowCaseView;
import src.backend.exception.entity.NoShowCase;
import src.backend.exception.repository.NoShowCaseRepository;

/**
 * {@code no_show_case}(소유: exception) 쓰기의 <b>단일 진입점</b>(BR-095) — 승하차 처리
 * ({@code boarding.command.BoardingCommandService})가 이 케이스를 직접 {@code save} 하던 것을
 * 대체한다. 다른 모듈이 소유 저장소를 직접 써 쓰기 규칙(ARCHITECTURE §3.3)을 우회하면 케이스
 * 개설·재개 규칙이 두 곳에 복제될 위험이 있어, 그 규칙을 이 클래스 하나로 모은다.
 *
 * <p>{@code @Transactional(propagation = MANDATORY)} 다 — 호출부(승하차 처리)가 이미 연 트랜잭션
 * 안에서 {@code run_rider} 상태 전이와 이 케이스 갱신이 <b>한 트랜잭션으로</b> 묶여야 한다
 * ({@code run.command.RunCompletionService} 와 같은 근거). 엔티티 {@code NoShowCase} 를 그대로
 * 돌려주지 않고 {@link NoShowCaseView} 로 감싸는 이유도 같다 — 호출부가 엔티티를 쥐면 이 클래스가
 * 모르는 곳에서 상태가 바뀔 수 있다.
 */
@Component
@RequiredArgsConstructor
public class NoShowCaseAccess {

    private final NoShowCaseRepository noShowCaseRepository;

    /**
     * 대기 카운트다운을 연다 — 이미 케이스가 있으면 재개한다(BR-009, 탑승자당 케이스는
     * {@code uk_no_show_case_run_rider} 로 1개뿐이다).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public NoShowCaseView openOrReopen(Long runRiderId, OffsetDateTime startedAt, OffsetDateTime expiresAt) {
        NoShowCase noShowCase = noShowCaseRepository.findByRunRiderId(runRiderId)
                .map(existing -> {
                    existing.reopen(startedAt, expiresAt);
                    return existing;
                })
                .orElseGet(() -> noShowCaseRepository.save(
                        NoShowCase.forRunRider(runRiderId, startedAt, expiresAt, startedAt)));
        return toView(noShowCase);
    }

    /** 되돌리기가 {@code no_show} 를 벗어날 때 케이스를 종결한다(BR-009) — 없으면 아무 일도 하지 않는다. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void resolveByRevert(Long runRiderId, OffsetDateTime resolvedAt) {
        noShowCaseRepository.findByRunRiderId(runRiderId)
                .ifPresent(noShowCase -> noShowCase.resolveByRevert(resolvedAt));
    }

    /** 재전송 응답 재구성(목표 12)이 최초 처리 때 만들어진 케이스를 다시 찾을 때 쓴다. */
    public Optional<NoShowCaseView> find(Long runRiderId) {
        return noShowCaseRepository.findByRunRiderId(runRiderId).map(NoShowCaseAccess::toView);
    }

    private static NoShowCaseView toView(NoShowCase noShowCase) {
        return new NoShowCaseView(noShowCase.getId(), noShowCase.getStartedAt(), noShowCase.getExpiresAt());
    }
}

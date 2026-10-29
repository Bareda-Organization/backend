package src.backend.request.command;

import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.boarding.command.RunRiderPersistence;
import src.backend.boarding.command.StopSkipJudge;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.event.RiderStatusChangedEvent;
import src.backend.boarding.repository.RunRiderRepository;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;
import src.backend.global.security.AuthUser;
import src.backend.request.domain.ChangeWindow;
import src.backend.request.dto.BoardingIntentToggleResponse;
import src.backend.request.entity.BoardingIntent;
import src.backend.request.event.IntentChangedEvent;
import src.backend.run.entity.Run;
import src.backend.run.event.RunRouteConfirmedEvent;
import src.backend.student.entity.Student;

/** 탑승 의사 토글의 ③구간(마감) 처리 단계 — 호출하는 토글 트랜잭션 안에서 실행된다. */
@Component
@RequiredArgsConstructor
class BoardingIntentClosedStep {

    /** ③구간 전원 미등원으로 건너뛴 정차지에 남기는 사유(API_SPEC §3.6 ③ · {@code run_stop.skip_notice}). */
    private static final String SKIP_NOTICE = "탑승 의사 토글로 전원 미탑승";

    private final RunRiderRepository runRiderRepository;

    private final RunRiderPersistence runRiderPersistence;

    private final StopSkipJudge stopSkipJudge;

    private final ApplicationEventPublisher eventPublisher;

    /**
     * ③구간 — 운행 시작 후(또는 출발 시각 + 10분 도달)라 재최적화 없이 미등원만 즉시 수용한다.
     * {@code riding=true}(되돌리기 시도)와 탑승자가 {@code boarded · alighted · no_show} 인 경우는
     * {@code 403 CHANGE_WINDOW_CLOSED} 다(§3.6 ③, Ruling 334) — 이미 탄 학생을 {@code absent} 로
     * 덮으면 종료 판정이 그 아이를 잔류로 세지 않는다. 단 이미 {@code absent} 이고 의사도 꺼진 학생의 {@code riding=false} 재전송은
     * 무변경 성공이다(Ruling 885②) — 탑승자 행이 아예 없어도 같다. 이미 {@code absent} 인데 의사만 켜져 있으면 의사만 끄고 성공한다
     * (Ruling 885② 보정 — 재경로·이벤트·이력 없음).
     *
     * <p>반영되면 기사·동승자에게 전달한다 — {@link RiderStatusChangedEvent}(WS {@code rider_changed})
     * 와 {@link RunRouteConfirmedEvent}(기존 {@code route_changed} 알림, 해당 승하차지 미정차).
     *
     * <p>{@code run_rider} 행이 없으면(그 학생이 애초에 이 회차 명단에 없을 때 — 이미 ①구간에서
     * {@code riding=false} 로 확정 배치의 제외 목록에 걸렸던 경우가 대표적이다) 부재 표시·정차지
     * 재계산·기사 전달을 조용히 건너뛴다 — 이미 명단에 없는 학생을 다시 없앨 대상이 없다.
     *
     * <p>③구간은 동승자의 승하차 처리와 겹치는 때라 탑승자 행을 승하차 경로(BR-267)와 같은 행 잠금으로 읽는다(BR-303) —
     * 승차가 먼저 커밋됐으면 {@code boarded} 를 읽어 403 이다. "잔여 0명이면 정차 건너뜀" 판정은 승하차 경로와 같은
     * {@link StopSkipJudge} 가 정차 항목을 잠근 뒤 세고 {@code no_show} 도 잔여에서 뺀다(BR-325).
     */
    BoardingIntentToggleResponse applyClosed(Run run, Student student, BoardingIntent intent,
            boolean riding, AuthUser requester, OffsetDateTime now) {
        // BR-303 — 탑승자 행을 잠근 채 읽는다: 방금 승차가 커밋됐다면 boarded 를 읽어 아래에서 거절되고, absent 로 덮지 않는다.
        Optional<RunRider> rider = runRiderRepository.findLockedByRunIdAndStudentId(run.getId(), student.getId());
        Optional<BoardingIntentToggleResponse> unchanged = unchangedResponseOf(intent, rider, riding, requester, now);
        if (unchanged.isPresent()) {
            return unchanged.get();
        }
        if (riding || rider.filter(r -> r.getStatus() != RiderStatus.WAITING).isPresent()) {
            throw new BusinessException(ErrorCode.CHANGE_WINDOW_CLOSED);
        }
        intent.applyRiding(false, ChangeWindow.CLOSED, now, requester.accountId());
        rider.ifPresent(r -> markAbsentAndNotify(run, student, r, now));
        eventPublisher.publishEvent(
                new IntentChangedEvent(run.getId(), run.getAcademyId(), student.getId(), false, now));
        String riderStatus = rider.map(r -> BoardingIntentStatusReader.statusNameOf(r.getStatus()))
                .orElse(BoardingIntentStatusReader.statusNameOf(RiderStatus.ABSENT));
        return BoardingIntentToggleResponse.appliedNoReroute(false, riderStatus,
                BoardingIntentStatusReader.quotaLeftOf(intent));
    }

    /** 이미 미등원 상태인 재전송은 무변경 성공 응답을 돌려주고(의사만 켜져 있으면 의사만 끈다), 아니면 빈 값이다. */
    private Optional<BoardingIntentToggleResponse> unchangedResponseOf(BoardingIntent intent,
            Optional<RunRider> rider, boolean riding, AuthUser requester, OffsetDateTime now) {
        if (!riding && !intent.isRiding() && rider.filter(r -> r.getStatus() != RiderStatus.ABSENT).isEmpty()) {
            // Ruling 885② — 의사가 이미 꺼진 학생의 재전송은 탑승자 행이 없든 absent 든 ①②구간의 같은 값 재전송처럼 무변경 성공이다(이벤트·이력 부재).
            return Optional.of(absentResponseOf(intent));
        }
        if (!riding && rider.filter(r -> r.getStatus() == RiderStatus.ABSENT).isPresent()) {
            // Ruling 885② 보정 — 탑승자는 이미 absent 이고 의사만 켜져 있으면 의사만 끈다. 재경로·정차 판정·이벤트·이력은 만들지 않는다(§3.6 ③ 403 대상 아님).
            intent.applyRiding(false, ChangeWindow.CLOSED, now, requester.accountId());
            return Optional.of(absentResponseOf(intent));
        }
        return Optional.empty();
    }

    private BoardingIntentToggleResponse absentResponseOf(BoardingIntent intent) {
        return BoardingIntentToggleResponse.appliedNoReroute(false,
                BoardingIntentStatusReader.statusNameOf(RiderStatus.ABSENT),
                BoardingIntentStatusReader.quotaLeftOf(intent));
    }

    /** 탑승자를 시스템 결석으로 바꾸고, 잔여가 없으면 정차를 건너뛴 뒤 기사·동승자에게 알리는 이벤트를 낸다. */
    private void markAbsentAndNotify(Run run, Student student, RunRider rider, OffsetDateTime now) {
        runRiderPersistence.markAbsentBySystem(rider, now);
        stopSkipJudge.skipIfNoRidersRemain(run.getId(), rider.getStopId(), SKIP_NOTICE);
        eventPublisher.publishEvent(new RiderStatusChangedEvent(run.getId(), run.getAcademyId(),
                student.getId(), rider.getId(), BoardingIntentStatusReader.statusNameOf(RiderStatus.ABSENT), now,
                false));
        eventPublisher.publishEvent(
                new RunRouteConfirmedEvent(run.getId(), run.getAcademyId(), run.getBusId(), now));
    }
}

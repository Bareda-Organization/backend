package src.backend.boarding.command;

import java.time.Duration;
import java.time.OffsetDateTime;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import src.backend.academy.entity.AcademySetting;
import src.backend.academy.repository.AcademySettingRepository;
import src.backend.boarding.dto.RiderStatusUpdateResponse;
import src.backend.boarding.entity.RiderStatus;
import src.backend.boarding.entity.RunRider;
import src.backend.boarding.event.RiderMarkedNoShowEvent;
import src.backend.exception.command.NoShowCaseAccess;
import src.backend.exception.dto.NoShowCaseView;
import src.backend.run.entity.Run;

/** 미승차 처리 협력자 — 케이스 생성 · 재개와 되돌리기 뒤 정합. 호출부 트랜잭션 안에서만 부른다. */
@Component
@RequiredArgsConstructor
class NoShowStep {

    private final NoShowCaseAccess noShowCaseAccess;

    /**
     * {@code stop_skipped} 판정·해제 협력자(CODE_CONVENTIONS §20.2, BR-101 — 미승차·되돌리기 두 경로가 공유한다) —
     * ③구간 결석 신청({@link src.backend.request.command.BoardingIntentCommandService})도 {@link StopSkipJudge} 로 같은 경로를 쓴다.
     */
    private final StopSkipJudge stopSkipJudge;

    /**
     * 미승차 대기 시간(분)을 학원별로 읽는다(API_SPEC §5.21) — 학원마다 값이 다르고,
     * {@code academy_setting} 행이 없는 학원(등록 경로가 행을 만들기 전에 생긴 구 데이터)은
     * {@link AcademySetting#DEFAULT_NO_SHOW_WAIT_MINUTES} 로 자가 치유한다({@code AcademySettingQueryService}
     * 의 GET-or-create 와 같은 근거) — 3분 고정 상수를 없애는 것이 이 목표의 본체라, 값이 없다고 예외를
     * 던지면 그 학원의 미승차 처리 자체가 막힌다.
     */
    private final AcademySettingRepository academySettingRepository;

    private final ApplicationEventPublisher eventPublisher;

    /**
     * 미승차 케이스 생성 — 학원별 대기 시간 뒤 만료로 저장하고, 학부모·관계자 두 갈래
     * 알림 + WebSocket {@code rider_changed} 방송(API_SPEC §7.1) 두 갈래 재료가
     * 될 {@link RiderMarkedNoShowEvent} 를 발행한다.
     *
     * <p>{@code stop_skipped}(API_SPEC §4.6, C-05)는 {@link StopSkipJudge#skipIfNoRidersRemain} 이 실제로
     * 계산한다 — C-05 가 명시하는 두 발생 경로(③구간 미등원·운행 중 미승차) 중 여기가 후자다. 이벤트
     * 발행은 그 계산 <b>뒤</b>에 둔다 — {@code rider_changed} 방송이 그 값을 실어야 하는데,
     * 계산 전에 발행하면 항상 {@code false} 를 실어 보내게 된다.
     */
    RiderStatusUpdateResponse handleNoShow(Run run, RunRider rider, OffsetDateTime now) {
        NoShowCaseView noShowCase = openNoShowCase(run, rider, now);

        boolean stopSkipped = stopSkipJudge.skipIfNoRidersRemain(run.getId(), rider.getStopId());
        eventPublisher.publishEvent(new RiderMarkedNoShowEvent(run.getId(), run.getAcademyId(), rider.getStudentId(),
                rider.getId(), noShowCase.caseId(), rider.getStopId(), stopSkipped, now));

        RiderStatusUpdateResponse.NoShowCaseSummary summary = new RiderStatusUpdateResponse.NoShowCaseSummary(
                noShowCase.caseId(), noShowCase.startedAt(), noShowCase.expiresAt(), noShowCase.contacts());
        return RiderStatusUpdateResponse.withNoShowCase(rider.getId(), now, summary, stopSkipped);
    }

    /**
     * 미승차 대기 카운트다운을 시작한다 — 학원별 대기 시간 뒤 만료. 탑승자당 케이스는 1개
     * ({@code uk_no_show_case_run_rider})라, 되돌린 뒤 다시 미승차면 기존 케이스를 재개한다(BR-009).
     */
    private NoShowCaseView openNoShowCase(Run run, RunRider rider, OffsetDateTime now) {
        int waitMinutes = academySettingRepository.findById(run.getAcademyId())
                .map(AcademySetting::getNoShowWaitMinutes)
                .orElse(AcademySetting.DEFAULT_NO_SHOW_WAIT_MINUTES);
        OffsetDateTime expiresAt = now.plus(Duration.ofMinutes(waitMinutes));
        return noShowCaseAccess.openOrReopen(rider.getId(), now, expiresAt);
    }

    /**
     * 되돌리기가 {@code no_show} 를 드나들 때 미승차의 부수 효과를 맞춘다(BR-009). 벗어나면 케이스를
     * 종결하고(에스컬레이션 대상에서 빠짐) 그 승하차지의 미정차 표시를 푼다 — 이 탑승자가 다시 잔여로
     * 세어지기 때문이다. 되돌린 결과가 {@code no_show} 면({@code no_show → waiting → no_show}, Ruling 305
     * 반복) {@link #handleNoShow} 와 같은 상태를 만든다. 알림 이벤트는 되돌리기 쪽 규칙(Ruling 308)을
     * 따라 새로 내지 않는다.
     */
    void syncNoShowAfterRevert(Run run, RunRider rider, RiderStatus fromStatus, RiderStatus toStatus,
            OffsetDateTime now) {
        if (fromStatus == RiderStatus.NO_SHOW && toStatus != RiderStatus.NO_SHOW) {
            noShowCaseAccess.resolveByRevert(rider.getId(), now);
            stopSkipJudge.clearSkipped(run.getId(), rider.getStopId());
        } else if (toStatus == RiderStatus.NO_SHOW && fromStatus != RiderStatus.NO_SHOW) {
            openNoShowCase(run, rider, now);
            stopSkipJudge.skipIfNoRidersRemain(run.getId(), rider.getStopId());
        }
    }
}

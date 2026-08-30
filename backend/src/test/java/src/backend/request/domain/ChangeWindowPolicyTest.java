package src.backend.request.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import src.backend.global.common.enums.Direction;
import src.backend.run.entity.Run;
import src.backend.run.entity.RunStatus;

/**
 * {@link ChangeWindowPolicy#segmentOf(Run, OffsetDateTime)} 단위 테스트 — 이 Phase 의 유일한 구간
 * 판정 지점이라(IMPLEMENTATION_PLAN:817) 컨트롤러·서비스 없이 순수 도메인 규칙만 검증한다.
 *
 * <p>{@code confirmAt}·{@code departTime} 은 회차 생성 시점에 이미 계산돼 저장된 컬럼값이므로
 * {@link Run#forSchedule} 로 직접 넘겨 만든다 — 정책이 재계산하지 않는다는 것을 이 생성 방식 자체가
 * 보여준다.
 */
class ChangeWindowPolicyTest {

    private static final OffsetDateTime DEPART = OffsetDateTime.parse("2026-09-01T08:00:00+09:00");
    private static final OffsetDateTime CONFIRM_AT = DEPART.minusMinutes(30);

    private Run runOf(RunStatus status, OffsetDateTime departTime, OffsetDateTime confirmAt) {
        Run run = Run.forSchedule(1L, 1L, 1L, departTime.toLocalDate(), Direction.TO_ACADEMY,
                departTime, confirmAt, "출발지", "도착지", 30);
        ReflectionTestUtils.setField(run, "status", status);
        return run;
    }

    @Test
    void confirmAt_직전은_즉시반영_구간이다() {
        Run run = runOf(RunStatus.IDLE, DEPART, CONFIRM_AT);
        OffsetDateTime now = CONFIRM_AT.minusSeconds(1);

        assertThat(ChangeWindowPolicy.segmentOf(run, now)).isEqualTo(ChangeWindow.IMMEDIATE);
    }

    @Test
    void confirmAt_정각은_승인필요_구간이다() {
        Run run = runOf(RunStatus.IDLE, DEPART, CONFIRM_AT);

        assertThat(ChangeWindowPolicy.segmentOf(run, CONFIRM_AT)).isEqualTo(ChangeWindow.APPROVAL_REQUIRED);
    }

    @Test
    void confirmAt_직후는_승인필요_구간이다() {
        Run run = runOf(RunStatus.IDLE, DEPART, CONFIRM_AT);
        OffsetDateTime now = CONFIRM_AT.plusSeconds(1);

        assertThat(ChangeWindowPolicy.segmentOf(run, now)).isEqualTo(ChangeWindow.APPROVAL_REQUIRED);
    }

    @Test
    void departTime_직전은_승인필요_구간이다() {
        Run run = runOf(RunStatus.IDLE, DEPART, CONFIRM_AT);
        OffsetDateTime now = DEPART.minusSeconds(1);

        assertThat(ChangeWindowPolicy.segmentOf(run, now)).isEqualTo(ChangeWindow.APPROVAL_REQUIRED);
    }

    @Test
    void departTime_정각은_불가_구간이다() {
        Run run = runOf(RunStatus.IDLE, DEPART, CONFIRM_AT);

        assertThat(ChangeWindowPolicy.segmentOf(run, DEPART)).isEqualTo(ChangeWindow.CLOSED);
    }

    @Test
    void departTime_직후는_불가_구간이다() {
        Run run = runOf(RunStatus.IDLE, DEPART, CONFIRM_AT);
        OffsetDateTime now = DEPART.plusSeconds(1);

        assertThat(ChangeWindowPolicy.segmentOf(run, now)).isEqualTo(ChangeWindow.CLOSED);
    }

    @Test
    void moving_회차는_출발_전이어도_불가_구간이다() {
        // 출발 30분도 더 남은 시각(now)이어도 status 가 moving 이면 시각과 무관하게 ③.
        Run run = runOf(RunStatus.MOVING, DEPART, CONFIRM_AT);
        OffsetDateTime now = DEPART.minusHours(1);

        assertThat(ChangeWindowPolicy.segmentOf(run, now)).isEqualTo(ChangeWindow.CLOSED);
    }

    @Test
    void finished_회차는_불가_구간이다() {
        Run run = runOf(RunStatus.FINISHED, DEPART, CONFIRM_AT);

        assertThat(ChangeWindowPolicy.segmentOf(run, DEPART.minusHours(1))).isEqualTo(ChangeWindow.CLOSED);
    }

    /**
     * 목표 14(Ruling 194) — 판정 입력은 서버 시계 하나뿐이고 요청이 실어 온 시각은 아예 받지 않는다.
     * {@code segmentOf} 시그니처에 {@code requestedAt} 파라미터가 없다는 것 자체가 그 증거이므로,
     * 여기서는 "27분 전에 접수됐다"는 사실을 흉내내는 어떤 파라미터도 넘기지 않고, 오직
     * {@code Clock} 이 가리키는 서버 시각(25분 전)만으로 ②가 나오는지 확인한다.
     */
    @Test
    void 판정은_서버시계_now_하나만_쓰고_요청_접수시각은_입력받지_않는다() {
        Run run = runOf(RunStatus.IDLE, DEPART, CONFIRM_AT);
        OffsetDateTime serverNow25MinBefore = DEPART.minusMinutes(25);

        assertThat(ChangeWindowPolicy.segmentOf(run, serverNow25MinBefore)).isEqualTo(ChangeWindow.APPROVAL_REQUIRED);
    }

    /**
     * {@code confirmAt} 이 정책 상수(출발 30분 전)와 다른 값(45분 전)이어도 판정은 <b>컬럼값을 그대로
     * 따른다</b> — {@code RunConfirmationPolicy.confirmAtOf} 로 재계산하지 않는다는 것을 검증한다.
     *
     * <p>이 시험은 DB 를 타지 않는 순수 단위 시험이라 {@code ck_run_confirm_at} CHECK 의 보호를 받지
     * 않는다 — 즉 여기서는 배치가 늦게 돌아 {@code confirmAt} 이 정책값과 어긋난 회차를 얼마든지
     * 만들 수 있다. 컬럼값(45분 전)을 읽으면 {@code now}(40분 전)는 이미 confirmAt 을 지났으므로
     * ②(APPROVAL_REQUIRED)다 — 반대로 정책값(30분 전)으로 재계산하면 {@code now} 가 아직 그 앞이라
     * ①(IMMEDIATE)로 잘못 판정된다.
     */
    @Test
    void confirmAt_이_정책값과_달라도_컬럼값을_그대로_쓰고_재계산하지_않는다() {
        OffsetDateTime confirmAtColumn = DEPART.minusMinutes(45);
        Run run = runOf(RunStatus.IDLE, DEPART, confirmAtColumn);
        OffsetDateTime now = DEPART.minusMinutes(40);

        assertThat(ChangeWindowPolicy.segmentOf(run, now)).isEqualTo(ChangeWindow.APPROVAL_REQUIRED);
    }
}

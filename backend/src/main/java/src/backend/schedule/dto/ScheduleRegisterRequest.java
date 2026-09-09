package src.backend.schedule.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * 운행 스케줄 등록 요청(API_SPEC §5.10).
 *
 * <p>{@code weekday}·{@code direction}·{@code departTime} 을 {@code String} 으로 받는다 — 값 도메인
 * 타입으로 받으면 어긋난 값이 Jackson 역직렬화 단계에서 깨져, {@code 422} 여야 할 입력이
 * {@code 400} 이 된다({@code ManagerRegisterRequest} 의 {@code role} 과 같은 이유). 변환과 거부는
 * {@code ApiValues} 한 곳이 맡는다.
 *
 * <p>학원을 담을 자리가 <b>부재한 것이 사양</b>이다 — 소속은 토큰이 정한다(§1.5).
 *
 * <p>길이 상한은 {@code schedule} 테이블의 컬럼 정의를 그대로 옮겼다.
 */
public record ScheduleRegisterRequest(
        @NotNull Long busId,
        @NotBlank String weekday,
        @NotBlank String direction,
        @NotBlank String departTime,
        @NotBlank @Size(max = 100) String originName,
        @NotBlank @Size(max = 100) String destinationName,
        @Positive Integer estDurationMin,
        Boolean active) {
}

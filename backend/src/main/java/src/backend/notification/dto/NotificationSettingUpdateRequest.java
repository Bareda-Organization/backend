package src.backend.notification.dto;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonAnySetter;

import jakarta.validation.constraints.Size;

/**
 * 알림 설정 변경 요청(API_SPEC §3.14 PATCH) — 세 항목만 boolean 으로 받고, 보낸 항목만 바뀐다(빠진 키와 {@code null} 은 유지, Ruling 869).
 *
 * <p>대상 밖 키(예: 지연 알림 {@code delay})는 {@code 422} 다. 미지정 키를 Jackson 이 조용히 버리는 것이 기본이라, 버려지는 대신 {@code unknownKeys}
 * 로 모아 검증({@code @Size(max = 0)})이 거부하게 한다 — 전역 설정을 바꾸지 않고 이 요청 한 곳에서만 엄격하다. boolean 이 아닌 값은 읽는
 * 단계에서 형식 오류({@code 422})가 된다.
 *
 * @param unknownKeys 사양에 없는 키 — 비어 있어야 통과한다
 */
public record NotificationSettingUpdateRequest(Boolean arrive, Boolean boarding, Boolean noShow,
        @JsonAnySetter @Size(max = 0) Map<String, Object> unknownKeys) {
}

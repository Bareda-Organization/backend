package src.backend.boarding.dto;

import jakarta.validation.constraints.Size;

/** 상태 정정 요청(API_SPEC §4.7) — {@code reason} 은 선택이고 길이만 컬럼({@code varchar(200)})에 맞춘다(BR-092). */
public record RiderRevertRequest(@Size(max = 200) String reason) {
}

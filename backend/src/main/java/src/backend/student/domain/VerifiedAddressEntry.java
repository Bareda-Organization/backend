package src.backend.student.domain;

import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;
import src.backend.student.geocoding.spec.GeocodedPoint;

/**
 * 검증을 통과한 요일 × 방향 한 칸 — <b>저장 단계가 받는 유일한 입력 형태</b>다(STU-05).
 *
 * <p>요청 DTO 를 저장 단계에 그대로 넘기지 않는 것이 이 타입의 존재 이유다. 저장 단계가 요청을 받으면
 * 좌표가 아직 없는 항목도 받을 수 있고, 그러면 "검증 실패는 저장 보류"(API_SPEC §3.7 · ERD
 * {@code weekly_address.verified})가 <b>실행 순서에 달린 약속</b>이 된다. 좌표를 타입에 박아 두면
 * 검증을 건너뛴 값은 이 타입으로 만들어질 수 없다.
 *
 * @param slot   요일 × 방향 — {@code weekly_address} UNIQUE 의 뒤 두 컬럼(C-16)
 * @param address 주소 원문 · 상세
 * @param point  지오코딩이 돌려준 좌표와 표기
 */
public record VerifiedAddressEntry(AddressSlot slot, AddressText address, GeocodedPoint point) {

    /** 요일 × 방향 — 노선 산출의 유일한 기준이고 기본 주소 개념이 부재하다(C-12 · C-16). */
    public record AddressSlot(Weekday weekday, Direction direction) {
    }

    /** 사용자가 적어 낸 그대로의 주소 — 지오코딩이 정규화한 표기와 별개로 보존한다(ERD {@code weekly_address.address}). */
    public record AddressText(String address, String addressDetail) {
    }
}

package src.backend.student.entity;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import src.backend.global.common.enums.Direction;
import src.backend.global.common.enums.Weekday;
import src.backend.student.domain.VerifiedAddressEntry;

/**
 * 요일별 등하원 주소 — 기본 주소 개념이 부재하고 요일 × 방향이 노선 산출의 유일한 기준이라
 * 조합마다 독립 행이 필요하다(ERD §3.2 · P-05 · STU-05·06 · C-12 · C-16).
 *
 * <p>{@code created_at} 이 없어({@code updated_at} 만 존재) {@code BaseTimeEntity} 를 상속하지
 * 않는다. {@code lat}/{@code lng}·{@code stop_id} 는 컬럼상 nullable 이나, <b>정상 경로로 저장된
 * 행은 셋이 전부 채워져 있다</b> — 검증 실패는 저장 보류라 {@code verified=false} 행이 남지 않기
 * 때문이다(ERD {@code weekly_address.verified}). 값을 채우는 유일한 자리가
 * {@link #reviseTo(VerifiedAddressEntry, Long, java.time.OffsetDateTime)} 이고, 그 입력 타입이
 * 좌표를 요구해 검증을 건너뛴 값은 여기까지 오지 못한다.
 */
@Entity
@Table(name = "weekly_address")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WeeklyAddress {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "student_id", nullable = false)
    private Long studentId;

    @Convert(converter = Weekday.Db.class)
    @Column(name = "weekday", length = 3, nullable = false)
    private Weekday weekday;

    @Convert(converter = Direction.Db.class)
    @Column(name = "direction", length = 20, nullable = false)
    private Direction direction;

    @Column(name = "address", length = 255, nullable = false)
    private String address;

    @Column(name = "address_detail", length = 255)
    private String addressDetail;

    @Column(name = "lat", precision = 9, scale = 6)
    private BigDecimal lat;

    @Column(name = "lng", precision = 9, scale = 6)
    private BigDecimal lng;

    @Column(name = "verified", nullable = false)
    private boolean verified;

    @Column(name = "stop_id")
    private Long stopId;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    private WeeklyAddress(Long studentId, Weekday weekday, Direction direction, String address,
            String addressDetail, OffsetDateTime updatedAt) {
        this.studentId = studentId;
        this.weekday = weekday;
        this.direction = direction;
        this.address = address;
        this.addressDetail = addressDetail;
        this.verified = false;
        this.updatedAt = updatedAt;
    }

    /**
     * 학부모·학생이 요일 × 방향 조합의 주소를 등록하는 시점에 생성한다(STU-05) — 검증 전이라
     * 좌표·승하차지는 아직 없다.
     */
    public static WeeklyAddress register(Long studentId, Weekday weekday, Direction direction, String address,
            String addressDetail, OffsetDateTime updatedAt) {
        return new WeeklyAddress(studentId, weekday, direction, address, addressDetail, updatedAt);
    }

    /**
     * 검증·매칭을 마친 칸을 새로 만든다(STU-05·06, API_SPEC §3.7).
     *
     * <p>검증 결과를 <b>생성 시점에</b> 받는다 — 검증 실패는 저장 보류라 좌표 없는 행이 남는 것이
     * 정상 경로가 아니기 때문이다(ERD {@code weekly_address.verified}). 나중에 채우는 형태로 두면
     * 채우기 전에 커밋되는 경로가 생기고, 그 행은 노선 계산에서 조용히 빠진다.
     */
    public static WeeklyAddress verified(Long studentId, VerifiedAddressEntry entry, Long stopId,
            OffsetDateTime updatedAt) {
        WeeklyAddress created = new WeeklyAddress(studentId, entry.slot().weekday(), entry.slot().direction(),
                entry.address().address(), entry.address().addressDetail(), updatedAt);
        created.reviseTo(entry, stopId, updatedAt);
        return created;
    }

    /**
     * 이미 있는 칸을 새 검증 결과로 덮어쓴다(§3.7 즉시 반영, Ruling 151).
     *
     * <p>행을 지우고 새로 넣지 않는다 — {@code weekly_address.id} 가 바뀌면 이 주소를 참조해 둔
     * 것들이 끊기고, 무엇보다 요일 × 방향 조합당 1행이라는 불변식(C-16)을 지키는 일이 삭제·삽입의
     * 순서에 매달리게 된다.
     */
    public void reviseTo(VerifiedAddressEntry entry, Long stopId, OffsetDateTime updatedAt) {
        this.address = entry.address().address();
        this.addressDetail = entry.address().addressDetail();
        this.lat = entry.point().lat();
        this.lng = entry.point().lng();
        this.verified = true;
        this.stopId = stopId;
        this.updatedAt = updatedAt;
    }
}

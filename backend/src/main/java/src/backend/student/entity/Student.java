package src.backend.student.entity;

import java.time.LocalDate;
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

import src.backend.global.common.BaseTimeEntity;
import src.backend.global.error.BusinessException;
import src.backend.global.error.ErrorCode;

/**
 * 학생 — 노선·명단·알림이 모두 참조하는 중심 레코드다. 계정보다 먼저 생성되며 계정 연결은
 * 가입 승인 시점에 이뤄진다(ERD §3.2 · STU-01~08 · AUTH-11).
 *
 * <p>{@code account_id} 는 계정 미연결(AUTH-11)이 정상이라 nullable 이고, {@code guardian_phone}
 * 컬럼은 존재하지 않는다 — 보호자 연락처는 {@code guardian_student} → {@code guardian} →
 * {@code account.phone} 조인 조회 대상이다(A-10). {@code deleted_at} 은 퇴원 soft delete 컬럼이며
 * 조회 필터({@code @Where} 등)를 엔티티에 붙이지 않는다 — 오늘 명단은 퇴원생을 포함해야 하고
 * (STU-04) 관리 목록은 제외해야 해서, 거를지 말지는 <b>조회하는 쪽</b>이 정할 일이다.
 */
@Entity
@Table(name = "student")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Student extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "academy_id", nullable = false)
    private Long academyId;

    @Column(name = "account_id")
    private Long accountId;

    @Column(name = "name", length = 50, nullable = false)
    private String name;

    @Column(name = "student_phone", length = 30)
    private String studentPhone;

    @Column(name = "photo_url", length = 255)
    private String photoUrl;

    @Convert(converter = Gender.Db.class)
    @Column(name = "gender", length = 10)
    private Gender gender;

    @Column(name = "birth_date")
    private LocalDate birthDate;

    @Column(name = "grade", length = 20)
    private String grade;

    @Column(name = "class_name", length = 50)
    private String className;

    @Column(name = "seat_no")
    private Integer seatNo;

    @Column(name = "note", columnDefinition = "text")
    private String note;

    @Column(name = "can_go_alone", nullable = false)
    private boolean canGoAlone;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    private Student(Long academyId, StudentProfile profile) {
        this.academyId = academyId;
        this.name = profile.name();
        this.studentPhone = profile.studentPhone();
        this.photoUrl = profile.photoUrl();
        this.gender = profile.gender();
        this.birthDate = profile.birthDate();
        this.grade = profile.grade();
        this.className = profile.className();
        this.seatNo = profile.seatNo();
        this.note = profile.note();
        this.canGoAlone = Boolean.TRUE.equals(profile.canGoAlone());
    }

    /**
     * 관계자가 학생을 등록하는 시점에 생성한다(STU-01) — {@code account_id} 는 아직 없다
     * (AUTH-11, 가입 승인 시점에 연결).
     *
     * <p>소속 학원은 <b>인자로만</b> 들어온다 — {@link StudentProfile} 에 담지 않은 것이, 요청 본문이
     * 학원을 정할 길을 없애는 방식이다(API_SPEC §1.5 학원은 토큰이 정한다).
     */
    public static Student register(Long academyId, StudentProfile profile) {
        return new Student(academyId, profile);
    }

    /**
     * 관계자가 학생 정보를 고친다(STU-03, API_SPEC §5.11 PATCH) — {@code null} 인 항목은 그대로 둔다.
     *
     * <p>보호자 연락처·승하차 주소를 인자로 받지 않는 것이 "관계자 입력 대상 밖"(A-10)을 강제하는
     * 방식이다 — 요청 본문에 실려 와도 이 메서드까지 닿을 경로가 부재하다. 학원과 계정 연결도 같은
     * 이유로 여기 없다({@link #linkAccount} 가 따로 받는다).
     */
    public void update(StudentProfile profile) {
        this.name = profile.name() == null ? this.name : profile.name();
        this.studentPhone = profile.studentPhone() == null ? this.studentPhone : profile.studentPhone();
        this.photoUrl = profile.photoUrl() == null ? this.photoUrl : profile.photoUrl();
        this.gender = profile.gender() == null ? this.gender : profile.gender();
        this.birthDate = profile.birthDate() == null ? this.birthDate : profile.birthDate();
        this.grade = profile.grade() == null ? this.grade : profile.grade();
        this.className = profile.className() == null ? this.className : profile.className();
        this.seatNo = profile.seatNo() == null ? this.seatNo : profile.seatNo();
        this.note = profile.note() == null ? this.note : profile.note();
        this.canGoAlone = profile.canGoAlone() == null ? this.canGoAlone : profile.canGoAlone();
    }

    /**
     * 퇴원 처리한다(STU-04) — 물리 삭제 경로는 부재하고 {@code deleted_at} 만 채운다.
     *
     * <p>행을 지우지 않는 이유는 <b>오늘 명단이 유지</b>돼야 하기 때문이다(API_SPEC §5.11) — 이미 편성된
     * 회차의 명단·승하차 이력이 학생 행을 참조하므로, 지우면 오늘 운행 중인 기사 화면에서 학생이 사라진다.
     * 목록·상세 조회에서 빠지는 것은 조회 쪽 조건({@code deleted_at IS NULL})이 맡는다.
     *
     * @param at 퇴원 시각. 주입된 {@code Clock} 에서 얻어 호출부가 넘긴다(횡단 규칙 1)
     */
    public void withdraw(OffsetDateTime at) {
        this.deletedAt = at;
    }

    /**
     * 가입 승인 시점에 계정을 연결한다(AUTH-11 · API_SPEC §5.2) — 학생 레코드는 계정보다 먼저
     * 만들어지므로 연결은 등록이 아니라 별도 전이다.
     *
     * <p>이미 <b>다른</b> 계정이 붙어 있으면 {@link BusinessException}({@code ALREADY_LINKED}) —
     * 덮어쓰면 앞 계정이 자기 데이터에 닿을 근거를 잃는데, 응답은 200 이라 아무도 알아채지 못한다.
     * 같은 계정을 다시 연결하는 것은 결과가 같으므로 통과시킨다.
     */
    public void linkAccount(Long newAccountId) {
        if (this.accountId != null && !this.accountId.equals(newAccountId)) {
            throw new BusinessException(ErrorCode.ALREADY_LINKED);
        }
        this.accountId = newAccountId;
    }
}

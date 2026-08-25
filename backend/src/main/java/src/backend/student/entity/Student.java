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
 * {@code account.phone} 조인 조회 대상이다(A-10). {@code deleted_at} 은 soft delete 컬럼이나
 * Phase 1 은 필드만 매핑하고 조회 필터({@code @Where} 등)는 붙이지 않는다.
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

    private Student(Long academyId, String name, String studentPhone, String photoUrl, Gender gender,
            LocalDate birthDate, String grade, String className, Integer seatNo, String note,
            boolean canGoAlone) {
        this.academyId = academyId;
        this.name = name;
        this.studentPhone = studentPhone;
        this.photoUrl = photoUrl;
        this.gender = gender;
        this.birthDate = birthDate;
        this.grade = grade;
        this.className = className;
        this.seatNo = seatNo;
        this.note = note;
        this.canGoAlone = canGoAlone;
    }

    /**
     * 관계자가 학생을 등록하는 시점에 생성한다(STU-01) — {@code account_id} 는 아직 없다
     * (AUTH-11, 가입 승인 시점에 연결).
     */
    public static Student register(Long academyId, String name, String studentPhone, String photoUrl,
            Gender gender, LocalDate birthDate, String grade, String className, Integer seatNo, String note,
            boolean canGoAlone) {
        return new Student(academyId, name, studentPhone, photoUrl, gender, birthDate, grade, className,
                seatNo, note, canGoAlone);
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

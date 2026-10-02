package src.backend.student.repository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import src.backend.global.security.access.AcademyScopeExempt;
import src.backend.student.entity.Student;

/** {@link Student} 영속성 접근. */
public interface StudentRepository extends JpaRepository<Student, Long> {

    @AcademyScopeExempt(reason = "계정 경유 조회 — 계정 자체가 이미 학원 범위 안이라 학생 쪽에 조건을 더해도 좁혀지는 것이 부재. "
            + "호출부가 토큰의 accountId 만 넘긴다는 전제 — 요청 파라미터의 accountId 를 넘기면 이 예외가 우회로가 된다")
    Optional<Student> findByAccountId(Long accountId);

    /**
     * 가입 승인이 연결할 학생 1건(AUTH-11 · API_SPEC §5.2) — 학원 조건이 <b>쿼리에 고정</b>돼 있다.
     *
     * <p>{@code findById} 로 꺼내 뒤에서 대조하지 않는 이유는, 이 경로에서 학원이 어긋난 결과가
     * {@code 403} 이 아니라 {@code 404 STUDENT_NOT_FOUND} 여야 하기 때문이다(§5.2) — 조건을 쿼리에
     * 넣으면 "없음" 과 "남의 학원" 이 같은 빈 결과가 되어 존재 여부가 응답에서 사라진다.
     *
     * <p>퇴원생({@code deleted_at})은 대상 밖이다 — 명단에서 빠진 학생에 새 학부모를 잇는 것은
     * 연결이 아니라 되살리기다.
     */
    Optional<Student> findByIdAndAcademyIdAndDeletedAtIsNull(Long id, Long academyId);

    /**
     * 이 사진 파일이 그 학원의 <b>재학생</b> 것인지(API_SPEC §5.11.1) — 학원과 퇴원 여부가 쿼리에 고정돼 있어
     * "없음" · "남의 학원" · "퇴원" 이 같은 {@code false} 가 된다(존재 비노출).
     *
     * <p>접두사 대신 끝 조각({@code /<파일명>})으로 맞춘다 — {@code photo_url} 의 접두사가 배포마다 달랐던 옛 행도
     * 같은 파일이면 찾는다.
     */
    boolean existsByPhotoUrlEndingWithAndAcademyIdAndDeletedAtIsNull(String fileNameTail, Long academyId);

    /**
     * 이 사진 파일이 <b>어느 학원이든</b> 재학생의 것인지(API_SPEC §5.11.1 · Ruling 786) — 메인 관리자 전용이다. 퇴원 여부는 그대로 쿼리에 고정돼
     * 퇴원생·부재가 같은 {@code false} 다. 호출부({@code StudentPhotoQueryService})는 {@code AcademyScope.resolveListScope} 가 빈 값(전 학원
     * 범위)일 때만 부른다.
     */
    @AcademyScopeExempt(reason = "§5.11.1 Ruling 786 — 메인 관리자는 학원 무관(O-06 관제 명단의 photo_url). 호출부가 전 학원 범위"
            + "(AcademyScope.resolveListScope 빈 값)일 때만 부르고, 그 밖의 역할은 위 학원 고정 쿼리를 쓴다")
    boolean existsByPhotoUrlEndingWithAndDeletedAtIsNull(String fileNameTail);

    /**
     * 관계자 웹의 학생 목록·검색(STU-01, API_SPEC §5.11)에 맞는 학생의 id·이름 <b>전건</b> — 학원과 퇴원 여부가 <b>쿼리에 고정</b>돼 호출부가
     * 빼먹을 자리가 부재하다. 이름의 자연 정렬(B1 #27)이 DB 정렬로는 되지 않아 서버가 줄 세울 때 쓴다. 본 행(보호자 연락처 포함)은 줄 세운 뒤 그
     * 쪽의 id 로만 읽는다.
     *
     * <p>검색어를 {@code IS NULL} 로 가르지 않고 <b>빈 문자열이 전건과 같아지는 형태</b>로 쓴다 —
     * {@code :q IS NULL} 은 PostgreSQL 이 파라미터 타입을 정하지 못해 조회 자체가 실패하는 자리다.
     * 값을 주지 않은 요청을 빈 문자열로 바꾸는 것은 호출부의 몫이다.
     *
     * <p>{@code q} 는 호출부가 {@link src.backend.global.persistence.LikeEscape} 로 이스케이프해 넘기고 이 쿼리는 {@code ESCAPE '\'} 로 그것을
     * 해석만 한다(BR-359) — 학원·매니저 검색과 같은 규칙이다.
     */
    @Query("""
            SELECT s.id AS id, s.name AS name FROM Student s
            WHERE s.academyId = :academyId
              AND s.deletedAt IS NULL
              AND LOWER(s.name) LIKE LOWER(CONCAT('%', :q, '%')) ESCAPE '\\'
            """)
    List<NameRow> findNamesByAcademyId(@Param("academyId") Long academyId, @Param("q") String q);

    /** {@link #findNamesByAcademyId} 의 한 행. */
    interface NameRow {

        Long getId();

        String getName();
    }

    /**
     * 그 학생들 중 <b>계정이 연결된</b> 학생만(Phase 9 RUN-05, API_SPEC §9.7 {@code run_started}
     * 의 "학생" 수신자) — {@code account_id} 가 없는 학생은 로그인이 없어 알림을 받을 계정 자체가
     * 없다.
     *
     * <p>학원 조건이 <b>쿼리에 고정</b>돼 있다 — 호출부는 회차 명단({@code run_rider})에서 뽑은
     * 학생 id 목록을 넘길 뿐이라, 여기서 학원을 다시 확인하지 않으면 (있을 수 없는 경로지만) 남의
     * 학원 학생 id 가 섞여 들어와도 걸러지지 않는다.
     */
    List<Student> findAllByIdInAndAcademyIdAndAccountIdIsNotNull(List<Long> ids, Long academyId);

    /**
     * 회차 명단(§4.2·§5.4)이 참조하는 학생들을 한 번에 읽는다 — 정차지마다 학생을 다시 조회하면 명단
     * 하나가 질의 N+1 개가 된다({@code RouteDetailAssembler} 의 승하차지 배치 조회와 같은 근거).
     *
     * <p>{@code deletedAt} 을 조건에 넣지 않는다 — 오늘 명단은 퇴원생도 실어야 한다(§5.11 · STU-04
     * "오늘 명단은 유지" — 이미 편성된 회차의 명단이 학생 행을 참조하는 이상 퇴원 여부와 무관하다).
     */
    List<Student> findAllByAcademyIdAndIdIn(Long academyId, Collection<Long> ids);

    /**
     * 개인정보 파기 대상 학생 id(Ruling 480 ②·520) — 퇴원한 지 컷오프를 넘었고 아직 익명화하지 않은 학생이다.
     * {@code ix_student_retention_cutoff}(부분 인덱스)와 조건이 같아야 그 인덱스를 탄다.
     */
    @AcademyScopeExempt(reason = "보존 정리 배치(Ruling 480 ②) — 전 학원의 퇴원 90일 경과 학생 전건이 대상이고, "
            + "부르는 주체가 사용자 요청이 아니라 스케줄러라 요청 주체의 소속 자체가 부재")
    @Query("select s.id from Student s where s.deletedAt < :cutoff and s.anonymizedAt is null order by s.id")
    List<Long> findIdsForAnonymization(@Param("cutoff") OffsetDateTime cutoff, Limit limit);

    /** 파기 대상 학생 묶음을 id 로 읽는다(Ruling 480 ②·520) — {@link #findIdsForAnonymization} 이 골라낸 id 만 넘긴다. */
    @AcademyScopeExempt(reason = "보존 정리 배치(Ruling 480 ②) — id 는 전 학원의 파기 대상 조회가 골라낸 값이고, "
            + "부르는 주체가 스케줄러라 요청 주체의 소속이 부재")
    List<Student> findAllByIdIn(Collection<Long> ids);

    /** 계정이 연결된 학생들을 계정 id 로 한 번에 읽는다 — 알림 수신자(학생 계정)마다 다시 조회하지 않으려고(BR-143). */
    List<Student> findAllByAcademyIdAndAccountIdIn(Long academyId, Collection<Long> accountIds);
}

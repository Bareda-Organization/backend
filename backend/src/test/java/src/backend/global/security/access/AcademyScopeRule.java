package src.backend.global.security.access;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.data.jpa.repository.Query;

/**
 * {@link AcademyScopeRepositoryConventionTest} 가 쓰는 <b>판정 전담</b> 술어 — 무엇이 학원 범위
 * 자원인지는 여기서 정하지 않는다.
 *
 * <p>{@link AcademyScopeScan}(수집)과 가른 축이 하나 더 있다. 이 클래스는 <b>"좁혀졌다"·"전건
 * 조회다" 의 정의</b>를 담고, 테스트 클래스는 <b>ERD §6.1 표와 그 표에 거는 단언</b>만 남긴다.
 * 셋은 바뀌는 계기가 각각 다르다 — 표는 사양이 바뀔 때, 술어는 회피 형태를 새로 발견할 때
 * (라운드 1 의 {@code ::} · 라운드 2 의 개행), 수집은 패키지 구조가 바뀔 때.
 *
 * <p>술어를 표와 한 파일에 두면 회피 형태를 하나 막을 때마다 ERD 표를 함께 읽어야 하고, 실제로
 * 그 누적이 클래스를 {@code reference.md §20.2} 기준의 두 배로 키웠다(리뷰 라운드 2 m-3).
 */
final class AcademyScopeRule {

    private AcademyScopeRule() {
    }

    /**
     * 학원 범위 저장소에 호출하면 격리가 통째로 빠지는 상속 메서드 — {@code JpaRepository} 가 전부 물려준다.
     *
     * <p>이름만 적고 접합자({@code .} · {@code ::})는 {@link #BULK_READ_JOINERS} 로 따로 순회한다 —
     * {@code repo.findAll()} 만 막고 {@code repo::findAll} 을 놓치면 회피가 <b>메서드 참조로 쓰는 것</b>
     * 하나로 끝난다.
     */
    private static final List<String> UNSCOPED_BULK_READS = List.of("findAll", "findAllById", "count");

    /** 저장소 필드와 메서드 이름을 잇는 두 형태 — 호출({@code .})과 메서드 참조({@code ::}). */
    private static final List<String> BULK_READ_JOINERS = List.of(".", "::");

    /**
     * 한 소스에서 주어진 저장소 필드의 전건 조회 사용을 전부 찾는다.
     *
     * <p>정규식이 <b>두 가지</b>를 함께 요구한다.
     * <ul>
     *   <li><b>낱말 경계</b>({@code \b}) — {@code findAll} 을 단순 부분 문자열로 찾으면
     *       {@code findAllByAcademyIdAndDeletedAtIsNull} 처럼 <b>좁혀진</b> 조회까지 위반으로 세어,
     *       규칙을 지킨 코드가 실패하고 결국 다음 사람이 검사를 꺼 버린다. 뒤쪽 경계가 그 오탐을 막는
     *       유일한 장치라 접합자 규칙을 넓힐 때도 유지 대상이다</li>
     *   <li><b>접합자 양옆의 공백 허용</b>({@code \s*}) — 셋을 인접으로만 찾으면 개행 한 번으로 검사가
     *       사라진다({@code studentRepository} 줄바꿈 {@code .findAll()}). 체인이 길어지면 개행이
     *       표준 서식이고 이 저장소도 이미 그 서식을 쓰므로, 회피가 <b>서식을 바꾸는 것</b> 하나로
     *       성립하던 구멍이다(리뷰 라운드 2 I-1)</li>
     * </ul>
     *
     * <p>남는 한계는 <b>공백이 아닌 것이 접합자 사이에 끼어드는 형태</b>다 — 필드와 점 사이의 블록
     * 주석, {@code Repository r = repo;} 로 받아 {@code r.findAll()} 을 부르는 별칭. 앞은 실사용 서식이
     * 아니고 뒤는 필드 이름을 따라가야 해 텍스트 판정의 범위 밖이다.
     */
    static List<String> bulkReadsIn(String body, String field, Path source) {
        List<String> found = new ArrayList<>();
        for (String bulkRead : UNSCOPED_BULK_READS) {
            for (String joiner : BULK_READ_JOINERS) {
                Matcher matcher = Pattern.compile("\\b" + Pattern.quote(field) + "\\s*"
                        + Pattern.quote(joiner) + "\\s*" + Pattern.quote(bulkRead) + "\\b").matcher(body);
                if (matcher.find()) {
                    found.add(source.getFileName() + ": " + field + joiner + bulkRead);
                }
            }
        }
        return found;
    }

    /**
     * 학원 조건이 걸려 있으면 좁혀진 것으로 센다. {@code @Query} 메서드와 파생 조회 메서드가
     * <b>서로 다른 축</b>을 쓴다 — 하나로 묶었던 것이 Phase 7 이전의 형태였고, 그 형태가 아래 회피를
     * 놓쳤다(Phase 6 T5 리뷰 실측, Phase 7 T6 이 고쳤다).
     *
     * <p><b>{@code @Query} 가 있으면 이름 · {@code @Param} 을 근거로 쓰지 않는다.</b> 둘 다 쿼리 본문과
     * 무관하게 메서드 시그니처에만 존재해, {@code @Query} 의 {@code WHERE} 절에서 학원 조건만 지워도
     * 시그니처는 그대로 남는다 — {@code findAllOrderedByRouteIdAndAcademyId} 라는 이름과
     * {@code @Param("academyId")} 가 조건이 빠진 뒤에도 계속 참을 낸 것이 이 결함의 본체였다. 그래서
     * {@code @Query} 메서드는 {@link #conditionClauseContainsAcademyId} <b>하나만</b> 본다.
     *
     * <p><b>{@code @Query} 가 없으면 이름이 유일한 근거다</b>({@code findAllByAcademyIdAnd...}) — 파생
     * 조회는 본문이라 부를 것이 없어 이름 축을 죽이면 검사 대상 전부가 사라진다.
     * {@code getName().contains("AcademyId")} 는 <b>넓은</b> 판정이라 {@code findAllByAcademyIdIsNull}
     * 처럼 학원을 좁히는 것이 아니라 <b>뒤집는</b> 이름도 통과하지만, 좁힘의 의미까지 이름으로
     * 판정하려면 Spring Data 파서를 흉내내야 하고 그 흉내가 틀리면 규칙을 지킨 조회가 실패한다. 현재
     * 그런 메서드는 부재하고, 생기면 {@code AcademyScopeIsolationTest} 의 HTTP 왕복 대조가 결과 행으로
     * 잡는다.
     */
    static boolean isNarrowedByAcademy(Method method) {
        Query query = method.getAnnotation(Query.class);
        if (query != null) {
            return conditionClauseContainsAcademyId(query.value());
        }
        return method.getName().contains("AcademyId");
    }

    /** {@code WHERE} 절 시작을 찾는다 — {@code @Modifying} UPDATE 문도 소문자 {@code where} 를 쓴다. */
    private static final Pattern WHERE_KEYWORD = Pattern.compile("\\bWHERE\\b", Pattern.CASE_INSENSITIVE);

    /**
     * {@code WHERE} 절의 끝을 찾는다 — 이 뒤는 결과의 모양(정렬·집계)이지 대상을 좁히는 조건이 아니다.
     * {@code GROUP BY} 를 여기 넣은 이유가 곧 회피 형태 하나다: {@code SELECT s.academyId AS academyId
     * ... GROUP BY s.academyId} 처럼 <b>WHERE 밖에서도 컬럼 이름이 등장</b>하는 집계 쿼리가 실제로 있어
     * ({@code AcademyStaffRepository#countByAcademyIdInGroupedByAcademyId}), 끝 경계 없이 본문 전체를
     * 뒤지면 {@code WHERE} 의 학원 조건을 지워도 {@code GROUP BY} 쪽 문자열이 남아 계속 참이 된다.
     */
    private static final Pattern CONDITION_END = Pattern.compile("\\b(GROUP\\s+BY|ORDER\\s+BY|HAVING)\\b",
            Pattern.CASE_INSENSITIVE);

    /** 줄 주석 — 표준 JPQL 문법은 아니지만 텍스트 판정을 우회할 목적으로 끼워 넣는 것을 막는다. */
    private static final Pattern LINE_COMMENT = Pattern.compile("--[^\\n]*");

    /** 블록 주석. */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    /**
     * {@code @Query} 본문의 <b>{@code WHERE} 절 안</b>에 학원 식별자가 있는지 본다 — SQL 문법을
     * 파싱하지 않고 절 경계(위 {@link #CONDITION_END})와 주석만 걷어내는 <b>텍스트 판정</b>이다.
     *
     * <p><b>어디까지 판정하는지</b> — {@code WHERE} 부터 {@code GROUP BY}·{@code ORDER BY}·
     * {@code HAVING} 직전(또는 문자열 끝)까지의 구간에서 {@code academyId}·{@code academy_id} 부분
     * 문자열을 찾는다. 주석은 먼저 걷어내 {@code -- AND r.academyId = :academyId} 처럼 조건인 척하는
     * 주석이 참을 만들지 않게 한다. {@code SELECT} 절의 별칭({@code AS academyId})은 애초에
     * {@code WHERE} 앞이라 이 구간에 들지 않는다.
     *
     * <p><b>어디부터 포기하는지</b> — 절 <b>경계</b>만 볼 뿐 조건의 <b>구조</b>는 안 본다. 예를 들어
     * {@code WHERE r.academyIdOverride = :x} 처럼 학원과 무관한 컬럼이 우연히 그 이름을 포함해도
     * 참으로 센다(현재 그런 컬럼은 부재). 이 한계를 넘으려면 JPQL 파서가 필요하고, 그 무게가
     * 이 검사가 감당할 범위를 넘는다 — 놓친 회피는 {@code AcademyScopeIsolationTest} 의 HTTP 왕복
     * 대조가 결과 행으로 잡는다. {@code WHERE} 자체가 없으면(현재 학원 범위 비예외 조회 중 그런
     * 메서드는 부재) 좁혀지지 않은 것으로 본다 — 조건이 있는지 모를 때는 없는 쪽으로 판정해야
     * 이 검사의 실패 방향이 "과잉 통과" 가 아니라 "과잉 거부" 가 된다.
     */
    static boolean conditionClauseContainsAcademyId(String jpql) {
        String withoutComments = BLOCK_COMMENT.matcher(jpql).replaceAll(" ");
        withoutComments = LINE_COMMENT.matcher(withoutComments).replaceAll(" ");

        Matcher where = WHERE_KEYWORD.matcher(withoutComments);
        if (!where.find()) {
            return false;
        }

        Matcher end = CONDITION_END.matcher(withoutComments);
        int conditionEnd = end.find(where.end()) ? end.start() : withoutComments.length();

        String condition = withoutComments.substring(where.end(), conditionEnd);
        return condition.contains("academyId") || condition.contains("academy_id");
    }
}

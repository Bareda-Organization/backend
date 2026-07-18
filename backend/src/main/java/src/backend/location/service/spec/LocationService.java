package src.backend.location.service.spec;

import java.util.List;

import src.backend.global.security.AuthUser;
import src.backend.location.dto.LocationOrigin;
import src.backend.location.dto.LocationReportRequest;
import src.backend.location.dto.LocationView;

/**
 * 실시간 위치 추적의 계약(인터페이스).
 *
 * <p>두 종류의 입력이 하나의 저장 경로로 모인다 —
 * ① 학생 앱의 자기 위치 보고(실 GPS 전환 대비, {@link #reportSelf}),
 * ② Mock 시뮬레이터의 좌표 주입({@link #ingest}). 이후 하나의 위치를
 * 학생 본인/학부모/기사/관리자가 각자 권한 범위에서 조회한다(승하차 기록과 동일한 4-계층 조회).
 */
public interface LocationService {

    /** 학생 앱이 자기 현재 위치를 보고(실 GPS 경로). 학생 계정 → 소속 학생으로 해석해 저장한다. */
    void reportSelf(AuthUser student, LocationReportRequest req);

    /**
     * 저수준 좌표 주입 — 이미 학생·학원이 확정된 좌표를 그대로 저장한다.
     * Mock 시뮬레이터가 매 틱 호출하며, 실 GPS 보고({@link #reportSelf})도 결국 이 경로로 합류한다.
     */
    void ingest(Long tenantId, Long studentId, double lat, double lng, LocationOrigin origin);

    /** 학생 본인의 최신 위치. */
    LocationView getMyLocation(AuthUser student);

    /** 학부모의 자녀(형제자매 포함) 최신 위치 목록(아직 좌표가 없는 자녀는 제외). */
    List<LocationView> getChildrenLocations(AuthUser parent);

    /** 기사가 담당 버스에 탑승하는 학생들의 최신 위치 목록. */
    List<LocationView> getBusLocations(AuthUser driver, Long busId);

    /** 관리자가 소속(또는 지정) 학원 학생들의 최신 위치 목록. */
    List<LocationView> getTenantLocations(AuthUser admin, Long tenantId);

    /**
     * WebSocket 연결이 끊긴 지 유예시간(app.connection.loss-grace-seconds)을 넘긴 학생을
     * CONNECTION_LOST 로 알린다 — {@code ConnectionLossScheduler}가 주기 호출.
     */
    void checkOverdueDisconnections();
}

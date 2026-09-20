## Task 4: 버스 1대 상세 API

**Files:**
- Create: `operations/dto/BusOperationDetail.java`
- Modify: `operations/query/OperationsQueryService.java` · `operations/controller/OperationsController.java`
- Test: 기존 `OperationsQueryServiceTest` 에 추가

**Interfaces:**
- Produces: `GET /api/operations/buses/{busId}` → 설계 §4.2

```
bus · session · routePlan { version · polyline · stops[ seq · name · lat · lng · etaSeconds · reachedAt ] }
students[ studentId · name · status · stopSeq · stopName · recordedAt · guardians[] ]
```

**반드시 담을 것**
- **정류장 이름**(`stops[].name`) — 기존 `RoutePlanResponse` 에 없어 화면이 "N번 정차"로만 표시하는 갭(`PRODUCT_SPEC` §12 **F10**)을 함께 해소한다.
- **보호자 연락처**(`students[].guardians[]`) — 설계 §5.1. **누락을 발견해도 연락 수단이 없으면 화면이 무용하다.**
- `stops[].reachedAt` 은 **추정값**이다. 필드명이나 주석으로 추정임을 드러내라.

- [ ] **Step 1~6:** 같은 절차

---


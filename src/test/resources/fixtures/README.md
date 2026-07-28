# 테스트 픽스처

## `order-source-mini.xml`

과제 샘플(HEADER 15 / ITEM 70)의 **구조적 특성만 축약해 재현**한 자체 제작 픽스처다.

실제 샘플 데이터는 BOOT-000 산출물이라 `secrets/` 아래에 있고 `.gitignore` 대상이다.
그것에 의존하는 테스트를 만들면 저장소를 클론한 사람은 테스트를 돌릴 수 없다.
회귀 방지의 본체는 이 픽스처가 맡고, 실물 검증은 별도의 조건부 테스트가 맡는다.

**인코딩은 UTF-8 이다.** 원본 샘플은 EUC-KR 이지만, 픽스처를 EUC-KR 로 두면
편집기·도구마다 깨져 유지보수가 어렵다. EUC-KR 해독 경로는 파일이 아니라
테스트 코드에서 바이트를 만들어 검증한다.

### 담고 있는 경우

| USER_ID | 상황 | 기대 |
|---|---|---|
| USER01 | HEADER 1 + ITEM 2 | 정상 → 2행 |
| USER02 | HEADER 1 + ITEM 1 | 정상 → 1행 |
| USER03 | HEADER 만 있음 | V-04 스킵 (HEADER_WITHOUT_ITEM) |
| USER77 | ITEM 만 있음 | V-03 스킵 (ORPHAN_ITEM) |
| USER78 | ITEM 만 있음 | V-03 스킵 (ORPHAN_ITEM) |

합계: HEADER 3 / ITEM 5 → **적재 3행, 스킵 3건**(고아 2 + 빈 헤더 1), 결과 `PARTIAL`

### 의도적으로 넣은 것

- **선언부 없음, 루트 엘리먼트 없음** — 원본과 동일. `<ROOT>` 래핑이 필요하다
- **ITEM 이 HEADER 순서와 무관하게 배치** — USER02 의 ITEM 이 USER02 의 HEADER 보다 먼저 나오고,
  USER03 의 HEADER 는 모든 ITEM 뒤에 나온다. 문서 순서에 의존하는 파싱이면 반드시 깨진다

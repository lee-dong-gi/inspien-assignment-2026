package com.inspien.eai.engine.mapper;

import java.util.List;

/**
 * Mapper — 소스 구조를 타깃 구조로 변환한다.
 *
 * <p>EAI 의 존재 이유가 가장 직접적으로 드러나는 지점이다. 송신 시스템은 자신의 형식으로만 말하고,
 * 수신 시스템은 자신의 형식으로만 듣는다. 어느 쪽도 상대에 맞춰 바뀌지 않으며, 바뀌어서도 안 된다.
 * 그 간극을 흡수하는 것이 이 계층의 유일한 책임이다.
 *
 * <p><b>변환은 곧 취사선택이다.</b> 시나리오 2에서 운송사 테이블로 넘길 때
 * {@code NAME}·{@code ITEM_NAME}·{@code PRICE}·{@code STATUS} 는 의도적으로 버린다.
 * 운송사는 배송에 필요한 정보만 받으면 되고, 그 이상을 넘기는 것은 편의가 아니라
 * <b>불필요한 결합과 개인정보 확산</b>이다.
 *
 * <p><b>반환이 {@code List} 인 이유.</b> 시나리오 1에서 HEADER 1건은 ITEM n건과 결합해
 * n개의 행이 된다(1:N 평탄화). 입력 1건이 출력 1건이라는 가정을 애초에 두지 않는다.
 *
 * <p><b>정합성 요건.</b> 이 메서드는 실행당 <b>단 한 번</b> 호출되고, 그 결과 리스트를
 * JDBC Receiver 와 FTP Receiver 가 <b>공유</b>한다. 두 Receiver 가 각자 매핑하면
 * 각자 채번하게 되어 DB 행의 {@code ORDER_ID} 와 영수증 파일 라인의 {@code ORDER_ID} 가
 * 어긋난다. 그 순간 두 시스템의 데이터는 대조 불가능해진다.
 *
 * @param <S> 소스 구조 (검증을 통과한 것)
 * @param <T> 타깃 구조
 */
public interface Mapper<S, T> {

    List<T> map(S source);
}

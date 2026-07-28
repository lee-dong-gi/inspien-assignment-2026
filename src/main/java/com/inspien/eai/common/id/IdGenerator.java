package com.inspien.eai.common.id;

import java.util.List;

/**
 * 채번기 — 규격에 맞는 식별자를 <b>필요한 만큼 한 번에</b> 발급한다.
 *
 * <p>단건 발급이 아니라 배치 발급을 기본 계약으로 둔 것이 이 인터페이스의 핵심이다.
 * 주문 1건이 63행이 되는 인터페이스에서 "한 개씩 63번" 은 두 가지를 잘못한다.
 *
 * <ol>
 *   <li>왕복 63회. 실시간 SYNC 응답 시간에 그대로 얹힌다</li>
 *   <li><b>부분 채번 상태를 만든다.</b> 40번째에서 실패하면 39개는 이미 소비됐고,
 *       그 39개로 만든 레코드를 어떻게 할지 결정해야 한다. 전량 선점이면 이 질문 자체가 없다</li>
 * </ol>
 *
 * <p>Mapper 는 이 메서드를 <b>실행당 정확히 한 번</b> 호출하고, 그 결과를 JDBC · FTP 두 Receiver 가
 * 공유한다. Receiver 가 각자 채번하면 DB 행의 {@code ORDER_ID} 와 영수증 파일 라인의
 * {@code ORDER_ID} 가 어긋나고, 그 순간 두 시스템의 데이터는 서로 대조할 수 없게 된다.
 */
public interface IdGenerator {

    /**
     * {@code count} 개를 발급한다.
     *
     * @return 발급 순서대로 담긴 불변 리스트. 크기는 항상 {@code count}
     * @throws IllegalArgumentException {@code count} 가 음수인 경우
     * @throws com.inspien.eai.engine.exception.NonRetryableException 채번 공간이 소진된 경우
     * @throws com.inspien.eai.engine.exception.RetryableException    저장소 접근에 실패한 경우
     */
    List<String> allocate(int count);

    /** 단건 발급. 배치 처리 대상이 아닌 곳에서만 쓴다 */
    default String next() {
        return allocate(1).get(0);
    }
}

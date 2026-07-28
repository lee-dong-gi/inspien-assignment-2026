package com.inspien.eai.engine.receiver;

/**
 * 0건 전달 — {@link Delivery#empty()} 의 구현.
 *
 * <p>상태를 갖지 않으므로 단일 인스턴스로 충분하다. 외부에 노출할 이유가 없어
 * 패키지 밖에서는 {@link Delivery#empty()} 로만 얻을 수 있게 두었다.
 */
enum NoOpDelivery implements Delivery {

    INSTANCE;

    @Override
    public int count() {
        return 0;
    }

    @Override
    public void commit() {
        // 확정할 것이 없다.
    }

    @Override
    public void compensate() {
        // 되돌릴 것이 없다.
    }
}

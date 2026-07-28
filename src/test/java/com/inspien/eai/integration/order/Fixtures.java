package com.inspien.eai.integration.order;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * 테스트 픽스처 로더.
 */
public final class Fixtures {

    public static final String ORDER_SOURCE_MINI = "/fixtures/order-source-mini.xml";

    private Fixtures() {
    }

    public static String read(String classpathResource) {
        try (InputStream in = Fixtures.class.getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalStateException("픽스처를 찾을 수 없다: " + classpathResource);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("픽스처 읽기 실패: " + classpathResource, e);
        }
    }
}

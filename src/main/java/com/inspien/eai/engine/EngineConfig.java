package com.inspien.eai.engine;

import com.inspien.eai.engine.log.FileInterfaceLogger;
import com.inspien.eai.engine.log.InterfaceLogger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 연계 엔진 공통 부품 조립.
 *
 * <p>인터페이스별 config({@code OrderFlowConfig} 등)와 <b>일부러 분리</b>했다.
 * 실행 이력 로거는 IF-ORD-001 과 IF-SHP-001 이 <b>같은 것</b>을 써야 한다 —
 * 인터페이스별로 만들면 로그 채널이 갈리거나, 인터페이스를 추가할 때 한쪽을 빠뜨린다.
 * 운영자는 "오늘 무엇이 흘렀나" 를 인터페이스 단위가 아니라 <b>한 파일</b>에서 본다.
 *
 * <p><b>조율자({@code DeliveryCoordinator})는 여기서 만들지 않는다.</b>
 * 타깃 타입에 대해 제네릭이라 빈으로 등록하면 인터페이스별 타입이 엔진 설정에 새어 들어온다.
 * 상태가 없어 인스턴스를 공유해 얻을 이득도 없으므로, 타입을 아는 자리
 * (각 인터페이스의 flow config)에서 직접 생성한다.
 */
@Configuration
public class EngineConfig {

    /**
     * 실행 이력 로거.
     *
     * <p>{@code @Component} 로 자동 스캔하지 않고 명시적으로 등록한다.
     * 이 프로젝트의 조립은 전부 config 에 모여 있고, 그래야 "무엇이 무엇에 의존하는가" 를
     * 클래스들을 뒤지지 않고 한곳에서 읽을 수 있다.
     */
    @Bean
    public InterfaceLogger interfaceLogger() {
        return new FileInterfaceLogger();
    }
}

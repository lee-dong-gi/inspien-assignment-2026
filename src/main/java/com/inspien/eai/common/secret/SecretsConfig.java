package com.inspien.eai.common.secret;

import com.inspien.eai.bootstrap.BootstrapProperties;
import com.inspien.eai.bootstrap.store.BootstrapArtifactStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * BOOT-000 산출물 접근 빈 조립.
 *
 * <h2>산출물 위치는 한 곳에만 정의한다</h2>
 * {@code inspien.secrets.dir} 같은 별도 설정을 새로 만들지 않고
 * {@link BootstrapProperties#outputDir()} 를 그대로 쓴다. 같은 디렉터리를 가리키는 설정이
 * 둘이 되면 한쪽만 바꿨을 때 <b>기록하는 곳과 읽는 곳이 갈라진다.</b>
 * 산출물을 만드는 쪽이 위치의 주인이고, 읽는 쪽은 그것을 따른다.
 *
 * <h2>{@code @Lazy} 인 이유</h2>
 * {@link ApplicantKey} 는 <b>BOOT-000 이 만들어 내는 파일</b>을 읽는다. 즉 최초 실행
 * ({@code gradlew bootstrapRun --args="--inspien.bootstrap.source=api"}) 시점에는 아직 없다.
 * 빈을 즉시 생성하면 <b>산출물을 만들러 가는 실행이 산출물이 없어서 기동에 실패</b>하는
 * 순환에 빠진다. 지연 생성으로 두면 실제로 필요한 실행 경로(probe · 데이터 평면)에서만
 * 만들어지고, 그때는 파일이 이미 존재한다.
 */
@Configuration
public class SecretsConfig {

    @Bean
    public SecretsLoader secretsLoader(BootstrapProperties properties) {
        return new SecretsLoader(properties.outputDir());
    }

    @Bean
    @Lazy
    public ApplicantKey applicantKey(SecretsLoader secretsLoader) {
        return new ApplicantKey(secretsLoader.readText(BootstrapArtifactStore.APPLICANT_KEY));
    }

    /**
     * 참여자명은 산출물이 아니라 설정에서 온다.
     *
     * <p>BOOT-000 요청 본문에 실었던 것과 <b>같은 값</b>을 써야 한다.
     * 영수증 파일명에 들어가는 이름과 제출한 이름이 갈라지면
     * 채점 측이 둘을 대조할 때 맞지 않는다.
     */
    @Bean
    @Lazy
    public ApplicantName applicantName(BootstrapProperties properties) {
        return new ApplicantName(properties.applicant().name());
    }
}

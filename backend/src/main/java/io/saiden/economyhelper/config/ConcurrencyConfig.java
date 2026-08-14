package io.saiden.economyhelper.config;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 웹훅 응답을 만들 자리.
 *
 * <p><b>왜 요청 스레드에서 하면 안 되는가.</b> 텔레그램은 웹훅 응답을 기다리고, 늦으면 같은
 * 업데이트를 다시 보낸다. {@code /news}는 피드 수집과 Gemini 번역을 거쳐 수 초가 걸리므로
 * 그 자리에서 답을 만들면 <b>같은 명령에 답이 두 번 나갈 수 있다.</b>
 *
 * <p>가상 스레드라 풀 크기를 정하지 않는다 — 이 일들은 계산이 아니라 기다림이고, 상대 API
 * 보호는 스레드 수가 아니라 {@code resilience4j} 레이트리미터가 맡는다.
 *
 * <p>빈을 따로 두는 이유는 <b>테스트가 갈아 끼울 수 있어야</b> 해서다. 단위 테스트는 같은
 * 스레드로 도는 {@code Runnable::run}을 넣어 "보냈다"를 곧바로 단언한다.
 */
@Configuration
public class ConcurrencyConfig {

    @Bean
    Executor replyExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}

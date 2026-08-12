package io.saiden.economyhelper.market;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@code CLAUDE.md}의 "TOSS 증권 API, 수출입은행 API로 이중화"를 구현한다.
 *
 * <p>순서대로 시도하고 <b>처음 성공한 것</b>을 쓴다. 둘 다 <b>인증도 IP 제한도 없어</b>
 * 로컬과 배포가 같은 구성으로 돈다 — 토스증권을 떠난 이유가 정확히 그 IP 허용목록이었다.
 * 출처가 유럽중앙은행과 한국 정부로 완전히 독립적이라 동시에 죽을 이유도 없다.
 *
 * <p><b>서킷브레이커는 출처별로 따로 있다</b>({@code fxFrankfurter}, {@code fxKexim}).
 * 각 클라이언트에 붙어 있으며 하나로 묶으면 토스 장애가 수출입은행까지 끊어
 * 이중화가 성립하지 않는다 — Phase 1에서 피드 브레이커를 매체별로 나눈 것과 같은 이유다.
 *
 * <p>클라이언트 순서는 Spring이 주입하는 목록 순서가 아니라 <b>이 클래스가 정한다</b>.
 * 빈 등록 순서에 이중화 순서가 딸려 가면 클래스 이름을 바꾸다 순서가 뒤집힐 수 있다.
 */
@Service
public class FxService {

    private static final Logger log = LoggerFactory.getLogger(FxService.class);

    /** 시도 순서. 앞이 1순위다. */
    private static final List<FxSource> ORDER = List.of(FxSource.FRANKFURTER, FxSource.KEXIM);

    private final List<FxRateClient> clients;

    public FxService(List<FxRateClient> clients) {
        this.clients = ORDER.stream()
                .flatMap(source -> clients.stream().filter(client -> client.source() == source))
                .toList();
    }

    /**
     * @return 처음 성공한 출처의 환율. 전부 실패하면 {@link Optional#empty()} —
     *         사용자에게는 "가져오지 못했다"로 나간다
     */
    public Optional<FxRate> usdToKrw() {
        for (FxRateClient client : clients) {
            try {
                return Optional.of(client.usdToKrw());
            } catch (RuntimeException e) {
                // 다음 출처가 있으면 조용히 넘어간다. 이게 이중화가 하는 일이다
                log.warn("[fx] {} 조회 실패 — 다음 출처로 넘어갑니다: {}",
                        client.source().displayName(), e.toString());
            }
        }
        log.error("[fx] 모든 출처에서 환율을 가져오지 못했습니다");
        return Optional.empty();
    }
}

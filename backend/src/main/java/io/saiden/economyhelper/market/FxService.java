package io.saiden.economyhelper.market;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@code CLAUDE.md}가 요구하는 환율 이중화 — <b>수출입은행이 1순위, 유럽중앙은행이 폴백</b>이다.
 *
 * <p>순서대로 시도하고 <b>처음 성공한 것</b>을 쓴다. 둘 다 <b>인증도 IP 제한도 없어</b>
 * 로컬과 배포가 같은 구성으로 돈다 — 어느 쪽도 호출 IP를 등록하라고 요구하지 않는다.
 * 출처가 유럽중앙은행과 한국 정부로 완전히 독립적이라 동시에 죽을 이유도 없다.
 *
 * <p><b>서킷브레이커는 출처별로 따로 있다</b>({@code fxFrankfurter}, {@code fxKexim}).
 * 각 클라이언트에 붙어 있다. 하나로 묶으면 1순위 장애가 폴백까지 끊어
 * 이중화가 무의미해진다.
 *
 * <p>클라이언트 순서는 Spring이 주입하는 목록 순서가 아니라 <b>이 클래스가 정한다</b>.
 * 빈 등록 순서에 이중화 순서가 딸려 가면 클래스 이름을 바꾸다 순서가 뒤집힐 수 있다.
 */
@Service
public class FxService {

    private static final Logger log = LoggerFactory.getLogger(FxService.class);

    /**
     * 시도 순서. 앞이 1순위다.
     *
     * <p><b>수출입은행이 먼저다.</b> 원/달러는 한국 공식 고시환율이 기준이고, 유럽중앙은행 값은
     * 참고용이다 — 국내 사용자가 은행 창구에서 마주치는 숫자와 맞아야 한다.
     *
     * <p>수출입은행은 영업일·고시 시각에만 값이 있어(주말·공휴일·이른 아침에는 비어 온다)
     * 그때는 프랑크푸르터로 넘어간다. 1순위가 자주 비는 것은 문제가 아니다 —
     * 그러라고 있는 것이 이중화다.
     */
    private static final List<FxSource> ORDER = List.of(FxSource.KEXIM, FxSource.FRANKFURTER);

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

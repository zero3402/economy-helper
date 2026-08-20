package io.saiden.economyhelper.market;

import io.saiden.economyhelper.support.Failover;
import io.saiden.economyhelper.support.FailureReason;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * {@code CLAUDE.md}가 요구하는 환율 이중화 — <b>신선한 순서로 셋을 세운다.</b>
 *
 * <p>순서대로 시도하고 <b>처음 성공한 것</b>을 쓴다. <b>1순위(KIS)만 앱키를 쓰고</b> 받쳐 주는
 * 둘은 <b>인증도 IP 제한도 없어</b>
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
     * 시도 순서. 앞이 1순위다. <b>{@link FxSource}의 선언 순서와 같아야 한다.</b>
     *
     * <p><b>사용자가 받는 값이 신선한 순서다.</b> KIS만 하루 중에도 움직이고 나머지 둘은 하루 한 번
     * 고시다. 그 둘 사이에서는 오전 9시 브리핑 기준으로 유럽중앙은행이 더 최근이다 — 어제 16시
     * CET(≈ 어제 23시 KST)와 수출입은행의 어제 11시 고시를 견주면 그렇다.
     *
     * <p><b>제약이 적은 쪽이 뒤에 선다.</b> KIS는 앱키와 초당 한도가 있고, 수출입은행은 하루
     * 1,000회에 비영업일·이른 아침이면 비어 온다. 가운데의 유럽중앙은행만 키도 한도도 없다.
     *
     * <p>매번 셋을 다 불러 가장 신선한 것을 고르지는 않는다. 그러면 이중화가 아니라 선택이 되고
     * 요청마다 수출입은행 한도를 태운다. 폴백이 일어나면 화면이 출처와 날짜로 밝힌다.
     */
    private static final List<FxSource> ORDER =
            List.of(FxSource.KIS, FxSource.FRANKFURTER, FxSource.KEXIM);

    private final List<FxRateClient> clients;

    public FxService(List<FxRateClient> clients) {
        this.clients = Failover.order(clients, ORDER, FxRateClient::source);
    }

    /**
     * @return 처음 성공한 출처의 환율. 전부 실패하면 {@link Optional#empty()} —
     *         사용자에게는 "가져오지 못했다"로 나간다
     */
    public Optional<FxRate> usdToKrw() {
        Optional<FxRate> found = Failover.first(clients, FxRateClient::usdToKrw,
                // 다음 출처가 있으면 조용히 넘어간다. 이게 이중화가 하는 일이다
                (client, e) -> log.warn("[fx] {} 조회 실패 — 다음 출처로 넘어갑니다: {}",
                        client.source().displayName(), FailureReason.of(e)));
        if (found.isEmpty()) {
            log.error("[fx] 모든 출처에서 환율을 가져오지 못했습니다");
        }
        return found;
    }

    /**
     * 환율을 못 구해도 <b>부르는 쪽은 계속 가야 한다.</b>
     *
     * <p>환율은 원화 환산과 김프에만 쓰인다 — 못 구했다고 시세나 브리핑을 통째로 막는 것은
     * 과하다. 그래서 예외를 여기서 삼키고 {@code null}로 떨어뜨린다. 받는 쪽은 이미
     * {@code null}이면 환산 줄을 빼도록 만들어져 있다({@code StockFormatter.convertible}).
     *
     * <p><b>이 메서드가 있는 이유는 같은 결정이 두 곳에서 서로 다르게 내려져 있었기 때문이다.</b>
     * 브리핑은 {@code Optional}로 받아 {@code error}로 남기고 곧바로 {@code orElse(null)}로
     * 풀었고, 웹훅은 {@code null}로 받아 {@code warn}으로 남겼는데 <b>태그가 {@code [stock]}이라
     * {@code /crypto} 요청의 실패까지 증시 실패로 기록됐다.</b> 판단이 하나면 자리도 하나여야 한다.
     *
     * <p>두 곳 다 {@code try/catch}로 감싸고 있었는데 <b>그 catch는 도달할 수 없었다</b> —
     * {@link #usdToKrw()}가 출처마다 예외를 삼키고 전부 실패하면 빈 {@code Optional}을 주기
     * 때문이다. 그래서 여기에는 {@code catch}가 없다. 전부 실패한 사실은 그쪽이 이미 남긴다.
     */
    public FxRate orNull() {
        return usdToKrw().orElse(null);
    }
}

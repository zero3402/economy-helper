package io.saiden.economyhelper.crypto.domain;

import io.saiden.economyhelper.shared.support.QueryNormalizer;
import java.util.List;

/**
 * 검색어에 해당하는 마켓 후보를 고른다 — <b>I/O를 모르는 순수 클래스</b>다
 * ({@code PopularityScorer}와 같은 자리). Spring 컨텍스트 없이 단위 테스트한다.
 *
 * <p>여기서 확정하지 않고 <b>후보 목록</b>을 돌려주는 게 핵심이다. 실제 데이터로 확인해 보니
 * 좁히기만으로는 자주 틀린다:
 *
 * <pre>
 *   비트 → 비트코인 · 아비트럼 · 비트텐서 · 비트코인캐시 …  (6개)
 *   이더 → 이더리움 · 메가이더 · 이더리움클래식 …           (6개)
 *   리플 → 엑스알피(리플) · 리플유에스디                    (2개)
 * </pre>
 *
 * 셋 다 <b>가장 앞에 오는 게 오답</b>이었다. 누가 정답인지는 이름으로 알 수 없고
 * 24시간 거래대금이 가른다({@code CryptoService} 참조) — 그래서 여기서는 고르지 않는다.
 */
public final class UpbitMarketIndex {

    /** 사용자가 티커를 쳤을 모양 — 영소문자·숫자 1~5자. 정규화 뒤라 소문자다. */
    private static boolean tickerShaped(String query) {
        return !query.isEmpty() && query.length() <= 5
                && query.chars().allMatch(c -> (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'));
    }

    private UpbitMarketIndex() {
    }

    /**
     * 단계별로 좁혀 <b>가장 먼저 걸리는 단계의 후보 전부</b>를 돌려준다.
     *
     * <p>정확 일치가 하나라도 있으면 부분 일치는 보지 않는다 — {@code 비트코인}을 쳤는데
     * {@code 비트코인캐시}가 후보로 끼면 거래대금 비교가 의미 없어진다.
     *
     * <p><b>접두와 부분을 한 단계로 묶는다.</b> 나누면 {@code 리플}이 접두 단계에서
     * {@code 리플유에스디}만 잡고 멈춰, 정답인 {@code 엑스알피(리플)}이 거래대금 비교에
     * 올라가지도 못한다(부분 일치라서).
     *
     * @param queryForms {@link QueryNormalizer#forLookup}이 준 형태들. 순서대로 시도한다
     * @return 후보. 없으면 빈 목록
     */
    public static List<UpbitMarket> candidates(List<String> queryForms, List<UpbitMarket> markets) {
        for (String query : queryForms) {
            if (query.isEmpty()) {
                continue;
            }
            List<UpbitMarket> exact = markets.stream().filter(m -> m.matchesExactly(query)).toList();
            if (!exact.isEmpty()) {
                return exact;
            }
            // ⚠️ 티커 모양(영숫자 1~5자)은 부분일치로 받지 않는다 — 「ai」는 영문명에 ai가 든 아무 코인
            //    (체인링크…)에 걸려 거래대금 1위가 답이 된다. 업비트에 그 티커가 없으면 LLM에게 넘긴다
            //    (업비트에 없는 코인만 LLM에게 묻는다는 규칙이 그 자리다)
            if (tickerShaped(query)) {
                continue;
            }
            List<UpbitMarket> partial = markets.stream()
                    .filter(m -> m.startsWith(query) || m.contains(query))
                    .toList();
            if (!partial.isEmpty()) {
                return partial;
            }
        }
        return List.of();
    }
}

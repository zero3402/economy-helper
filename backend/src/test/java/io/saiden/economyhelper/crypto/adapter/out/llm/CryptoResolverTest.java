package io.saiden.economyhelper.crypto.adapter.out.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.saiden.economyhelper.crypto.domain.ResolvedCoin;
import io.saiden.economyhelper.infrastructure.llm.LlmJson;
import io.saiden.economyhelper.testsupport.TestGemini;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * LLM이 준 티커를 <b>쓸 수 있는 값인지</b> 가르는 순수 계산.
 *
 * <p>세 해석기가 같은 함정을 공유한다 — LLM이 {@code null}을 <b>리터럴 문자열</b>로 준다.
 * {@code ResolvedStock}·{@code ResolvedPlace}(weather.domain)와 같은 방어다.
 */
class CryptoResolverTest {

    @Test
    @DisplayName("「null」 문자열은 티커가 아니다 — 그대로 두면 KRW-NULL을 바이낸스에 묻는다")
    void rejectsTheLiteralNullString() {
        // ⚠️ 답은 어차피 「찾지 못했다」로 같다(둘 다 미상장). 문제는 그 헛호출 하나가
        //    바이낸스로 간다는 것이다 — 한도가 IP 단위이고 Render는 공용 이그레스라
        //    「우리가 할 수 있는 것은 밴을 늘리지 않는 것뿐」인 자리다
        assertThat(new CryptoResolver.Parsed("null").ticker()).isNull();
        assertThat(new CryptoResolver.Parsed("NULL").ticker()).isNull();
        assertThat(new CryptoResolver.Parsed(" Null ").ticker()).isNull();
    }

    @Test
    @DisplayName("빈 값도 티커가 아니다")
    void rejectsBlanks() {
        assertThat(new CryptoResolver.Parsed(null).ticker()).isNull();
        assertThat(new CryptoResolver.Parsed("").ticker()).isNull();
        assertThat(new CryptoResolver.Parsed("   ").ticker()).isNull();
    }

    @Test
    @DisplayName("진짜 티커는 앞뒤만 다듬어 그대로 준다 — 「NULL」을 막느라 멀쩡한 것을 막으면 안 된다")
    void keepsRealTickers() {
        assertThat(new CryptoResolver.Parsed(" BNB ").ticker()).isEqualTo("BNB");
        assertThat(new CryptoResolver.Parsed("BTC").ticker()).isEqualTo("BTC");
        // 이름에 null이 들어간 멀쩡한 티커까지 막지 않는다
        assertThat(new CryptoResolver.Parsed("NULLX").ticker()).isEqualTo("NULLX");
    }

    @Test
    @DisplayName("LLM이 죽으면 빈손이 아니라 고장으로 알린다 — 빈손이면 「찾지 못했습니다」가 나간다")
    void reportsTheModelFailure() {
        assertThatThrownBy(() -> new CryptoResolver(TestGemini.failing(), new ObjectMapper()).resolve("페페"))
                .isInstanceOf(LlmJson.Failed.class);
    }

    @Test
    @DisplayName("티커 모양이 아니면 못 가린 것이다 — 「BTC/USDT」에 USDT를 붙여 바이낸스에 묻고 7일 캐시하던 것")
    void rejectsRepliesThatAreNotATicker() {
        // 쓰레기 심볼 하나가 바이낸스로 가면 IP 가중치를 쓴다(ADR-0008: 자동 밴). 빈손은 캐시에 안 담기지만 값은 7일 남는다
        for (String reply : List.of("BTC/USDT", "BTC USDT", "비트코인", "BTCUSDT,ETHUSDT")) {
            assertThat(resolve(reply)).as("답 '%s'", reply).isEmpty();
        }
    }

    @Test
    @DisplayName("소문자는 올려 쓰지 않고 못 가린 것으로 본다 — 프롬프트가 대문자를 요구하므로 소문자는 자유 글이 온 신호다")
    void doesNotUpperCaseALowercaseReply() {
        assertThat(resolve("btc")).isEmpty();
    }

    @Test
    @DisplayName("대문자 영숫자 티커는 그대로 쓴다 — 쓰레기를 막느라 멀쩡한 것을 막으면 안 된다")
    void keepsAWellFormedTicker() {
        assertThat(resolve("BNB")).contains(new ResolvedCoin("BNB"));
        assertThat(resolve(" BNB ")).as("앞뒤 공백은 JSON 노이즈다").contains(new ResolvedCoin("BNB"));
        assertThat(resolve("1INCH")).contains(new ResolvedCoin("1INCH"));
    }

    /** Gemini가 {@code {"symbol": …}}로 이 값을 준 상태에서 해석한 결과. */
    private static Optional<ResolvedCoin> resolve(String symbolReply) {
        return new CryptoResolver(TestGemini.answering("{\"symbol\":\"" + symbolReply + "\"}"), new ObjectMapper())
                .resolve("아무거나");
    }
}

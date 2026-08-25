package io.saiden.economyhelper.market;

import static org.assertj.core.api.Assertions.assertThat;

import io.saiden.economyhelper.market.CryptoResolver.ResolvedCoin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * LLM이 준 티커를 <b>쓸 수 있는 값인지</b> 가르는 순수 계산.
 *
 * <p>세 해석기가 같은 함정을 공유한다 — LLM이 {@code null}을 <b>리터럴 문자열</b>로 준다.
 * {@code ResolvedStock.blank}와 {@code ResolvedPlace}는 그것을 막고 있었는데 이것만 빠져 있었다.
 */
class CryptoResolverTest {

    @Test
    @DisplayName("「null」 문자열은 티커가 아니다 — 그대로 두면 KRW-NULL을 바이낸스에 묻는다")
    void rejectsTheLiteralNullString() {
        // ⚠️ 답은 어차피 「찾지 못했다」로 같다(둘 다 미상장). 문제는 그 헛호출 하나가
        //    바이낸스로 간다는 것이다 — 한도가 IP 단위이고 Render는 공용 이그레스라
        //    「우리가 할 수 있는 것은 밴을 늘리지 않는 것뿐」인 자리다
        assertThat(new ResolvedCoin("null").upperSymbol()).isNull();
        assertThat(new ResolvedCoin("NULL").upperSymbol()).isNull();
        assertThat(new ResolvedCoin(" Null ").upperSymbol()).isNull();
    }

    @Test
    @DisplayName("빈 값도 티커가 아니다")
    void rejectsBlanks() {
        assertThat(new ResolvedCoin(null).upperSymbol()).isNull();
        assertThat(new ResolvedCoin("").upperSymbol()).isNull();
        assertThat(new ResolvedCoin("   ").upperSymbol()).isNull();
    }

    @Test
    @DisplayName("진짜 티커는 대문자로 다듬어 그대로 준다 — 「NULL」을 막느라 멀쩡한 것을 막으면 안 된다")
    void keepsRealTickers() {
        assertThat(new ResolvedCoin(" bnb ").upperSymbol()).isEqualTo("BNB");
        assertThat(new ResolvedCoin("btc").upperSymbol()).isEqualTo("BTC");
        // 이름에 null이 들어간 멀쩡한 티커까지 막지 않는다
        assertThat(new ResolvedCoin("nullx").upperSymbol()).isEqualTo("NULLX");
    }
}

package io.saiden.economyhelper.text;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * 실제 종목 데이터(KOSPI 804 + NASDAQ 3,071)에 돌려 보고 <b>실패했던 입력</b>들을 회귀로 고정한다.
 * 아래 넷은 전부 한 번씩 틀렸던 것이고, 다시 틀리면 여기서 잡힌다.
 */
class QueryNormalizerTest {

    @Nested
    @DisplayName("실측에서 실패했던 입력")
    class RegressionsFromRealData {

        @Test
        @DisplayName("접미사를 더 이상 줄지 않을 때까지 뗀다 — 한 번만 떼면 applestock에서 멈춘다")
        void stripsSuffixesRepeatedly() {
            assertThat(QueryNormalizer.forLookup("apple stock price")).contains("apple");
            assertThat(QueryNormalizer.forLookup("삼성전자 주가 알려줘")).contains("삼성전자");
        }

        @Test
        @DisplayName("조사를 떼지 않는다 — 삼성전자주가가 삼성전자주로 망가졌던 사고")
        void neverStripsSingleCharParticlesForLookup() {
            assertThat(QueryNormalizer.forLookup("삼성전자 주가")).contains("삼성전자");
            assertThat(QueryNormalizer.forLookup("삼성전자 주가")).doesNotContain("삼성전자주");
        }

        @Test
        @DisplayName("접두어도 뗀다 — 접미사만 보면 '오늘'이 남아 매칭이 통째로 실패한다")
        void stripsPrefixes() {
            assertThat(QueryNormalizer.forLookup("오늘 테슬라 주가")).contains("테슬라");
            assertThat(QueryNormalizer.forLookup("지금 비트코인 얼마")).contains("비트코인");
        }

        @Test
        @DisplayName("etf도 군더더기다 — 상장명에 그 낱말이 없어 붙어 있으면 색인이 못 찾는다")
        void stripsEtf() {
            assertThat(QueryNormalizer.forLookup("타임나스닥100 etf")).contains("타임나스닥100");
            assertThat(QueryNormalizer.forLookup("KODEX 200 ETF 주가")).contains("kodex200");
        }

        @Test
        @DisplayName("긴 접미사를 먼저 본다 — '가'가 먼저 걸리면 '현재가'를 못 뗀다")
        void prefersLongerAffixes() {
            assertThat(QueryNormalizer.forLookup("엔비디아 현재가")).contains("엔비디아");
        }
    }

    @Nested
    @DisplayName("조회용 정규화")
    class ForLookup {

        @Test
        @DisplayName("원형과 다듬은 형태를 함께 준다 — 어느 쪽이 맞을지 미리 알 수 없다")
        void keepsBothForms() {
            assertThat(QueryNormalizer.forLookup("애플 주가")).containsExactly("애플주가", "애플");
            // 뗄 게 없으면 하나만
            assertThat(QueryNormalizer.forLookup("삼성전자")).containsExactly("삼성전자");
        }

        @Test
        @DisplayName("대소문자·공백·구두점·전각을 통일한다")
        void unifiesCasingAndWidth() {
            assertThat(QueryNormalizer.forLookup("  AAPL  ")).containsExactly("aapl");
            assertThat(QueryNormalizer.forLookup("SK 하이닉스!")).containsExactly("sk하이닉스");
            assertThat(QueryNormalizer.forLookup("ＡＡＰＬ")).containsExactly("aapl");
        }

        @Test
        @DisplayName("종목 코드는 손대지 않는다")
        void keepsNumericCodes() {
            assertThat(QueryNormalizer.forLookup("005930")).containsExactly("005930");
        }

        @Test
        @DisplayName("빈 입력은 빈 목록 — 호출자가 분기 하나로 끝낼 수 있게")
        void returnsEmptyForBlank() {
            assertThat(QueryNormalizer.forLookup(null)).isEmpty();
            assertThat(QueryNormalizer.forLookup("   ")).isEmpty();
            assertThat(QueryNormalizer.forLookup("???")).isEmpty();
        }

        @Test
        @DisplayName("군더더기만 남으면 통째로 지우지 않는다 — 지우면 무엇을 찾을지가 사라진다")
        void neverStripsEverything() {
            assertThat(QueryNormalizer.forLookup("주가")).containsExactly("주가");
        }
    }

    @Nested
    @DisplayName("/news 토큰 정규화 — 여기서만 조사를 뗀다")
    class ForSearchToken {

        @Test
        @DisplayName("조사를 떼어 같은 개념이 한 캐시 키로 모인다")
        void stripsParticlesSoCacheHits() {
            assertThat(QueryNormalizer.forSearchToken("금리는")).isEqualTo("금리");
            assertThat(QueryNormalizer.forSearchToken("금리가")).isEqualTo("금리");
            assertThat(QueryNormalizer.forSearchToken("환율의")).isEqualTo("환율");
            assertThat(QueryNormalizer.forSearchToken("금리")).isEqualTo("금리");
        }

        @Test
        @DisplayName("두 글자가 안 남으면 떼지 않는다 — '인도'를 '인'으로 만들면 다른 단어가 된다")
        void keepsShortWordsIntact() {
            assertThat(QueryNormalizer.forSearchToken("인도")).isEqualTo("인도");
            assertThat(QueryNormalizer.forSearchToken("제도")).isEqualTo("제도");
        }
    }
}

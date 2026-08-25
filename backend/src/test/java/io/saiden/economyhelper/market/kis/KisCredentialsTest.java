package io.saiden.economyhelper.market.kis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * <b>붙여 넣기가 남긴 개행 한 글자가 KIS 셋을 함께 죽인다.</b>
 *
 * <p>{@code CLAUDE.md}가 {@code EGW00105}(「유효하지 않은 AppSecret」, 토큰 발급 403)를
 * 「키가 틀린 것이 아닐 수 있다 — <b>끝의 줄바꿈</b>부터 뗀다」로 적어 두고 있다. 실측 중에
 * 그것을 「모의/실전 도메인이 안 맞는다」로 오진해 설정을 바꿨다가 되돌린 기록도 함께 있다.
 *
 * <p>⚠️ <b>그런데 그 remedy가 코드에 없었다.</b> 문서에는 사람이 디버깅할 때의 힌트로만 남아
 * 있었고, 대시보드에 개행이 붙은 값을 넣으면 <b>환율·국내 주식·미국 주식의 1순위가 한꺼번에</b>
 * 죽는다. {@code TelegramWebhookController}는 웹훅 secret에 같은 것을 이미 하고 있었다 —
 * 규칙이 한쪽에만 걸려 있던 자리다.
 */
class KisCredentialsTest {

    @Test
    @DisplayName("헤더에 실리는 앱키·앱시크릿에서 개행을 뗀다 — 헤더가 깨지면 조회가 통째로 실패한다")
    void trimsCredentialsBeforePuttingThemInHeaders() {
        HttpHeaders headers = new HttpHeaders();

        new KisHeaders("key-with-newline\n", "secret-with-spaces  \n")
                .of("token", "TR0001").accept(headers);

        assertThat(headers.getFirst("appkey")).isEqualTo("key-with-newline");
        assertThat(headers.getFirst("appsecret")).isEqualTo("secret-with-spaces");
        assertThat(headers.getFirst("authorization")).isEqualTo("Bearer token");
    }

    @Test
    @DisplayName("키가 없으면 빈 문자열이다 — null로 두면 헤더 설정에서 터진다")
    void treatsMissingCredentialsAsEmpty() {
        HttpHeaders headers = new HttpHeaders();

        new KisHeaders(null, null).of("token", "TR0001").accept(headers);

        assertThat(headers.getFirst("appkey")).isEmpty();
        assertThat(headers.getFirst("appsecret")).isEmpty();
    }

}

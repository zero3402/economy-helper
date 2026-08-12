package io.saiden.economyhelper.translate;

import io.saiden.economyhelper.news.Article;
import org.springframework.stereotype.Component;

/**
 * 번역하지 않고 원문을 그대로 돌려주는 폴백.
 *
 * <p>무료 티어가 429를 뱉거나 서킷브레이커가 열렸을 때 쓴다. 번역이라는 태스크 특성상
 * 원문을 그대로 내보내도 <b>정보 손실이 없다</b> — 읽는 사람이 영어로 읽을 뿐이다.
 * 요약이었다면 이런 깔끔한 강등이 불가능했다.
 */
@Component
public class PassthroughTranslator implements Translator {

    @Override
    public Translation translate(Article article) {
        return Translation.untranslated(article);
    }
}

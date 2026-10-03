package io.saiden.economyhelper.translate.application.port.out;

import io.saiden.economyhelper.translate.domain.Translation;
import io.saiden.economyhelper.translate.domain.TranslationRequest;
import java.util.List;

/**
 * 글 번역기. 실패를 삼키지 않고 던진다 — 원문 강등은 {@code TranslationService}가 한 곳에서 한다.
 */
public interface Translator {

    /** @return 입력과 같은 순서, 같은 개수. 짝이 어긋나면 던진다 */
    List<Translation> translateAll(List<TranslationRequest> requests);
}

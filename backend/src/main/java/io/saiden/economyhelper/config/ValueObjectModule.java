package io.saiden.economyhelper.config;

import io.saiden.economyhelper.shared.domain.PercentChange;
import io.saiden.economyhelper.shared.domain.Price;
import java.math.BigDecimal;
import java.util.function.Function;
import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.deser.std.StdDeserializer;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.StdSerializer;

/**
 * 값 객체({@link Price}·{@link PercentChange})를 <b>맨 숫자</b>로 쓰고 맨 숫자에서 읽는다.
 *
 * <p><b>캐시 JSON이 값 객체 도입 전과 바이트 수준으로 같게</b> 하려는 것이다 — 기본 매핑이면
 * {@code "price":{"value":239500}}이 되어 이미 담긴 항목을 못 읽는다. 그러면 판 번호를 올려야 하고
 * 그 사이 캐시가 통째로 식는다. 도메인은 Jackson을 모르므로(ArchitectureTest) 여기 config에 둔다.
 *
 * <p>읽을 때 불변식이 그대로 걸린다 — {@code 0}이 담겨 있으면 {@link Price}가 거절한다.
 */
final class ValueObjectModule extends SimpleModule {

    ValueObjectModule() {
        super("value-objects");
        bare(Price.class, Price::value, Price::new);
        bare(PercentChange.class, PercentChange::percent, PercentChange::new);
    }

    private <T> void bare(Class<T> type, Function<T, BigDecimal> unwrap, Function<BigDecimal, T> wrap) {
        addSerializer(type, new StdSerializer<T>(type) {
            @Override
            public void serialize(T value, JsonGenerator gen, SerializationContext ctxt) {
                gen.writeNumber(unwrap.apply(value));
            }
        });
        addDeserializer(type, new StdDeserializer<T>(type) {
            @Override
            public T deserialize(JsonParser p, DeserializationContext ctxt) {
                return wrap.apply(ctxt.readValue(p, BigDecimal.class));
            }
        });
    }
}

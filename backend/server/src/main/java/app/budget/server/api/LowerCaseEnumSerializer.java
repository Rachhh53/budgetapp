package app.budget.server.api;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;

/**
 * Writes every enum the way the database and the request bodies spell it: lower case
 * ({@code card_payment}, not {@code CARD_PAYMENT}), so a value reads the same in both directions.
 */
public final class LowerCaseEnumSerializer extends StdSerializer<Enum<?>> {

    @SuppressWarnings("unchecked")
    public LowerCaseEnumSerializer() {
        // Enum.class is the raw type; the cast only tells the compiler what it already holds.
        super((Class<Enum<?>>) (Class<?>) Enum.class);
    }

    @Override
    public void serialize(Enum<?> value, JsonGenerator json, SerializerProvider provider) throws IOException {
        json.writeString(Inputs.code(value));
    }
}

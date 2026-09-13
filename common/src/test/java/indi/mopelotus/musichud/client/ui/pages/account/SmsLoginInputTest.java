package indi.mopelotus.musichud.client.ui.pages.account;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SmsLoginInputTest {
    @Test void preservesLeadingZeroesAndDoesNotDependOnIntegerRange() {
        assertEquals("012345", SmsLoginInput.code("012345"));
        assertEquals("9999999999", SmsLoginInput.code("9999999999"));
        assertEquals("0123456789", new SmsLoginInput("0123456789", "44").phone());
        assertEquals("123456789012345", new SmsLoginInput("123456789012345", "86").phone());
    }
    @Test void rejectsMalformedAndOversizedFields() {
        for (String value : List.of("", "123", "12345678901", "+1234", "12 34", "１２３４", "1234\n"))
            assertThrows(IllegalArgumentException.class, () -> SmsLoginInput.code(value));
        for (String phone : List.of("", "123", "1234567890123456", "-12345", "1e8"))
            assertThrows(IllegalArgumentException.class, () -> new SmsLoginInput(phone, "86"));
        for (String region : List.of("", "0", "086", "1234", "+86"))
            assertThrows(IllegalArgumentException.class, () -> new SmsLoginInput("0123456789", region));
    }
}

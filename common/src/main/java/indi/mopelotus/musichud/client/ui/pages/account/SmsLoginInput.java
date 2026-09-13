package indi.mopelotus.musichud.client.ui.pages.account;

/** Numeric-looking identifiers remain strings: leading zeroes are significant. */
public record SmsLoginInput(String phone, String countryCode) {
    public SmsLoginInput {
        if (phone == null || !phone.matches("[0-9]{4,15}")) throw new IllegalArgumentException("Invalid phone");
        if (countryCode == null || !countryCode.matches("[1-9][0-9]{0,2}")) throw new IllegalArgumentException("Invalid country code");
    }
    public static String code(String value) {
        if (value == null || !value.matches("[0-9]{4,10}")) throw new IllegalArgumentException("Invalid verification code");
        return value;
    }
}

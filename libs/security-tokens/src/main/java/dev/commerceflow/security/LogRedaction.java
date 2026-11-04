package dev.commerceflow.security;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes secrets and personal data from text that is about to be logged (INV-8). The code never
 * logs these on purpose; this is the net under it, for values that arrive inside exception messages
 * and stack traces (a duplicate-email constraint quoting the email, a client error echoing a
 * header). Applied to every string in every structured log line by {@link RedactingLogCustomizer}.
 */
public final class LogRedaction {

  private static final Pattern JWT =
      Pattern.compile("eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*");
  private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/=-]+");
  private static final Pattern SECRET_FIELD =
      Pattern.compile(
          "(?i)((?:password|passwd|secret|api[_-]?key|refresh[_-]?token|access[_-]?token)"
              + "[\"']?\\s*[:=]\\s*[\"']?)[^\\s\"',;&}]+");
  private static final Pattern EMAIL =
      Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}");
  private static final Pattern PAYMENT_TOKEN = Pattern.compile("\\btok_[a-z0-9_]+");
  private static final Pattern DIGITS = Pattern.compile("\\b\\d(?:[ -]?\\d){12,18}\\b");

  private LogRedaction() {}

  public static String redact(String text) {
    if (text == null || text.isEmpty()) {
      return text;
    }
    String out = JWT.matcher(text).replaceAll("[jwt]");
    out = BEARER.matcher(out).replaceAll("$1[redacted]");
    out = SECRET_FIELD.matcher(out).replaceAll("$1[redacted]");
    out = EMAIL.matcher(out).replaceAll("[email]");
    out = PAYMENT_TOKEN.matcher(out).replaceAll("tok_[redacted]");
    return redactCardNumbers(out);
  }

  /** Only digit runs that pass the Luhn check: a 13-digit epoch timestamp is not a card number. */
  private static String redactCardNumbers(String text) {
    Matcher m = DIGITS.matcher(text);
    StringBuilder out = new StringBuilder();
    while (m.find()) {
      String digits = m.group().replaceAll("[ -]", "");
      m.appendReplacement(out, luhn(digits) ? "[pan]" : Matcher.quoteReplacement(m.group()));
    }
    m.appendTail(out);
    return out.toString();
  }

  static boolean luhn(String digits) {
    int sum = 0;
    boolean twice = false;
    for (int i = digits.length() - 1; i >= 0; i--) {
      int d = digits.charAt(i) - '0';
      if (twice) {
        d *= 2;
        if (d > 9) {
          d -= 9;
        }
      }
      sum += d;
      twice = !twice;
    }
    return sum % 10 == 0;
  }
}

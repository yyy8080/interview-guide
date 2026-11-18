package interview.guide.modules.mail.service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 生成线下面试日历邀请（RFC 5545 iCalendar）
 */
public final class IcsCalendarBuilder {

  private static final DateTimeFormatter UTC_FORMAT =
      DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
  private static final int MAX_LINE_OCTETS = 75;

  private IcsCalendarBuilder() {
  }

  /**
   * @param uid         稳定唯一标识（同一投递改期时保持不变，日历客户端会更新原事件）
   * @param sequence    修订序号，改期时递增
   * @param start       开始时间（服务器本地时区）
   * @param durationMin 时长（分钟）
   */
  public static String build(String uid, int sequence, LocalDateTime start, int durationMin, String summary,
                             String location, String description, String organizerEmail, String organizerName,
                             ZoneId zone) {
    Instant startInstant = start.atZone(zone).toInstant();
    Instant endInstant = startInstant.plusSeconds(Math.max(durationMin, 1) * 60L);
    StringBuilder sb = new StringBuilder();
    line(sb, "BEGIN:VCALENDAR");
    line(sb, "VERSION:2.0");
    line(sb, "PRODID:-//interview-guide//recruitment//CN");
    line(sb, "CALSCALE:GREGORIAN");
    line(sb, "METHOD:REQUEST");
    line(sb, "BEGIN:VEVENT");
    line(sb, "UID:" + escape(uid));
    line(sb, "SEQUENCE:" + Math.max(sequence, 0));
    line(sb, "DTSTAMP:" + UTC_FORMAT.format(Instant.now()));
    line(sb, "DTSTART:" + UTC_FORMAT.format(startInstant));
    line(sb, "DTEND:" + UTC_FORMAT.format(endInstant));
    line(sb, "SUMMARY:" + escape(summary));
    if (location != null && !location.isBlank()) {
      line(sb, "LOCATION:" + escape(location));
    }
    if (description != null && !description.isBlank()) {
      line(sb, "DESCRIPTION:" + escape(description));
    }
    if (organizerEmail != null && !organizerEmail.isBlank()) {
      String cn = organizerName != null && !organizerName.isBlank()
          ? ";CN=" + quoteParam(organizerName) : "";
      line(sb, "ORGANIZER" + cn + ":mailto:" + organizerEmail);
    }
    line(sb, "STATUS:CONFIRMED");
    line(sb, "BEGIN:VALARM");
    line(sb, "TRIGGER:-PT1H");
    line(sb, "ACTION:DISPLAY");
    line(sb, "DESCRIPTION:" + escape(summary));
    line(sb, "END:VALARM");
    line(sb, "END:VEVENT");
    line(sb, "END:VCALENDAR");
    return sb.toString();
  }

  static String escape(String text) {
    if (text == null) {
      return "";
    }
    return text.replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\r\n", "\\n")
        .replace("\n", "\\n")
        .replace("\r", "\\n");
  }

  private static String quoteParam(String value) {
    return "\"" + value.replace("\"", "'") + "\"";
  }

  /** 按 75 字节折行（不拆分多字节字符），行尾 CRLF */
  private static void line(StringBuilder sb, String content) {
    int octets = 0;
    int lineLimit = MAX_LINE_OCTETS;
    for (int i = 0; i < content.length(); ) {
      int codePoint = content.codePointAt(i);
      String ch = new String(Character.toChars(codePoint));
      int size = ch.getBytes(StandardCharsets.UTF_8).length;
      if (octets + size > lineLimit) {
        sb.append("\r\n ");
        octets = 0;
        lineLimit = MAX_LINE_OCTETS - 1;
      }
      sb.append(ch);
      octets += size;
      i += Character.charCount(codePoint);
    }
    sb.append("\r\n");
  }
}

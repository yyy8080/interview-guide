package interview.guide.modules.mail.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RFC 5545 线下面试日历邀请构建器测试")
class IcsCalendarBuilderTest {

  @Test
  @DisplayName("生成的 iCalendar 包含标准事件字段、UTC 时间、提醒器与特殊字符转义")
  void buildsValidRfc5545CalendarInvite() {
    LocalDateTime start = LocalDateTime.of(2026, 12, 1, 14, 30, 0);
    String ics = IcsCalendarBuilder.build(
        "application-88@interview-guide",
        2,
        start,
        60,
        "线下面试 - 高级后端工程师, 二面; 现场",
        "上海市浦东新区张江高科园区 1 号楼\n3 层会议室",
        "面试官：李工；请提前 10 分钟签到，携带简历",
        "hr@example.com",
        "星辰科技 \"HR\"",
        ZoneId.of("Asia/Shanghai")
    );

    assertThat(ics)
        .startsWith("BEGIN:VCALENDAR\r\n")
        .endsWith("END:VCALENDAR\r\n")
        .contains("METHOD:REQUEST\r\n")
        .contains("UID:application-88@interview-guide\r\n")
        .contains("SEQUENCE:2\r\n")
        .contains("DTSTART:20261201T063000Z\r\n")
        .contains("DTEND:20261201T073000Z\r\n")
        .contains("ORGANIZER;CN=\"星辰科技 'HR'\":mailto:hr@example.com\r\n")
        .contains("BEGIN:VALARM\r\n")
        .contains("TRIGGER:-PT1H\r\n");

    String unfolded = ics.replace("\r\n ", "");
    assertThat(unfolded)
        .contains("SUMMARY:线下面试 - 高级后端工程师\\, 二面\\; 现场\r\n")
        .contains("LOCATION:上海市浦东新区张江高科园区 1 号楼\\n3 层会议室\r\n");

    for (String line : ics.split("\r\n")) {
      assertThat(line.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(75);
    }
  }
}

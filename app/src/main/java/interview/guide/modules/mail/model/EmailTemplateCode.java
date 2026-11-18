package interview.guide.modules.mail.model;

import java.util.List;

/**
 * 邮件模板编码及内置默认内容。
 * <p>
 * 模板正文使用 {变量名} 占位符，所有模板都可使用公共变量：
 * {companyName}、{contactInfo}、{portalUrl}。
 */
public enum EmailTemplateCode {

  VERIFICATION_CODE(
      "邮箱验证码",
      "【{companyName}】邮箱验证码",
      """
          您好：

          您正在登录 {companyName} 招聘门户，本次验证码为：{code}
          验证码 {ttlMinutes} 分钟内有效，请勿泄露给他人。如非本人操作请忽略本邮件。

          {companyName} 招聘团队
          """,
      List.of("code", "ttlMinutes")),

  APPLICATION_RECEIVED(
      "投递成功通知",
      "【{companyName}】已收到您的投递 - {jobTitle}",
      """
          {candidateName}，您好：

          感谢您投递 {companyName} 的「{jobTitle}」岗位，我们已收到您的简历。
          后续进展会通过邮件通知您，您也可以随时登录招聘门户查看：{portalUrl}/careers/me

          {companyName} 招聘团队
          {contactInfo}
          """,
      List.of("candidateName", "jobTitle")),

  INTERVIEW_INVITE(
      "在线面试邀请",
      "【{companyName}】在线面试邀请 - {jobTitle}",
      """
          {candidateName}，您好：

          您投递的「{jobTitle}」岗位已进入在线 AI 面试环节。
          请在 {expireAt} 前登录招聘门户完成面试：{portalUrl}/careers/me
          面试形式：{interviewMode}；{timeLimit}
          请确保网络稳定、环境安静，面试开始后请独立完成作答。

          {companyName} 招聘团队
          {contactInfo}
          """,
      List.of("candidateName", "jobTitle", "expireAt", "interviewMode", "timeLimit")),

  REJECTED(
      "未通过通知",
      "【{companyName}】投递结果通知 - {jobTitle}",
      """
          {candidateName}，您好：

          感谢您对 {companyName}「{jobTitle}」岗位的关注与投入。
          经过综合评估，很遗憾您本次未能进入下一轮。{rejectReason}
          您也可登录招聘门户查看投递详情：{portalUrl}/careers/me
          我们已将您的信息纳入人才库，如有合适机会将再与您联系。祝您求职顺利！

          {companyName} 招聘团队
          {contactInfo}
          """,
      List.of("candidateName", "jobTitle", "rejectReason")),

  OFFLINE_INVITE(
      "线下面试邀请",
      "【{companyName}】线下面试邀请 - {jobTitle}",
      """
          {candidateName}，您好：

          恭喜您通过「{jobTitle}」岗位的线上面试，诚邀您参加线下面试：

          时间：{interviewTime}（预计 {durationMinutes} 分钟）
          地点：{location}
          面试官：{interviewer}
          备注：{notes}

          请登录招聘门户确认参加或申请改期：{portalUrl}/careers/me
          附件为日历邀请，可直接添加到您的日历。

          {companyName} 招聘团队
          {contactInfo}
          """,
      List.of("candidateName", "jobTitle", "interviewTime", "durationMinutes", "location",
          "interviewer", "notes"));

  public static final List<String> COMMON_VARIABLES = List.of("companyName", "contactInfo", "portalUrl");

  private final String displayName;
  private final String defaultSubject;
  private final String defaultBody;
  private final List<String> variables;

  EmailTemplateCode(String displayName, String defaultSubject, String defaultBody, List<String> variables) {
    this.displayName = displayName;
    this.defaultSubject = defaultSubject;
    this.defaultBody = defaultBody;
    this.variables = variables;
  }

  public String displayName() {
    return displayName;
  }

  public String defaultSubject() {
    return defaultSubject;
  }

  public String defaultBody() {
    return defaultBody;
  }

  public List<String> variables() {
    return variables;
  }
}

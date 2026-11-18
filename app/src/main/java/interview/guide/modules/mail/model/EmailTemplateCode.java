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

  CANDIDATE_INVITATION(
      "岗位投递与面试邀请",
      "【{companyName}】诚邀您参加「{jobTitle}」岗位投递与在线面试",
      """
          {candidateGreeting}：

          诚邀您参加 {companyName}「{jobTitle}」岗位的简历投递与在线面试考核。
          本次面试仅对受邀邮箱（{candidateEmail}）开放，非受邀邮箱无法投递简历或进入面试。

          请在 {expireAt} 前点击专属链接完成邮箱验证、投递简历并参加在线面试：
          {inviteUrl}

          考核形式：{interviewMode}
          {hrNote}

          【重要诚信须知】
          1. 请勿乱提交简历：每个岗位提交简历仅有两次机会（首次提交 + 最多 1 次初筛未通过修改重提），简历不允许造假，否则会直接不通过。
          2. 答题严禁搜题：在线笔试与在线面试答题期间禁止搜题、切屏查资料或借助外部工具代答，后台设有全程提交审查并与简历真实性联动核验。

          {companyName} 招聘团队
          {contactInfo}
          """,
      List.of("candidateGreeting", "candidateEmail", "jobTitle", "inviteUrl", "expireAt",
          "interviewMode", "hrNote")),

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

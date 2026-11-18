import { expect, test, type Route } from '@playwright/test';
import { seedAdminAuth } from './helpers/auth';

const ok = (route: Route, data: unknown) =>
  route.fulfill({ json: { code: 200, message: 'success', data } });

test.describe('企业招聘全流程（候选人门户 + HR 后台审核与邮件配置）', () => {
  test('候选人浏览岗位、邮箱验证码登录、上传简历、自选形式完成在线面试并确认线下面试', async ({ page }) => {
    let stage: 'SCREENING' | 'INTERVIEW_PENDING' | 'UNDER_REVIEW' | 'OFFLINE_INVITED' | 'OFFLINE_CONFIRMED' =
      'SCREENING';
    let started = false;
    let confirmStatus: 'PENDING' | 'CONFIRMED' | 'RESCHEDULE_REQUESTED' = 'PENDING';
    let capturedCandidateToken: string | undefined;

    await page.route('**/api/public/jobs', route =>
      ok(route, [
        {
          id: 9,
          title: '高级 Java 后端工程师',
          department: '基础架构部',
          location: '上海',
          employmentType: 'FULL_TIME',
          headcount: 2,
          salaryRange: '30K-45K',
          interviewMode: 'BOTH',
          deadline: '2026-12-31T23:59:59',
          publishedAt: '2026-11-01T10:00:00',
        },
      ]),
    );

    let selectedLevel: 'junior' | 'mid' | 'senior' = 'mid';

    await page.route('**/api/public/jobs/9', route =>
      ok(route, {
        id: 9,
        title: '高级 Java 后端工程师',
        department: '基础架构部',
        location: '上海',
        employmentType: 'FULL_TIME',
        headcount: 2,
        salaryRange: '30K-45K',
        jdText: '负责核心交易系统高可用架构设计与性能优化。',
        difficulty: 'mid',
        interviewMode: 'BOTH',
        questionCount: 2,
        timeLimitMinutes: 30,
        deadline: '2026-12-31T23:59:59',
        publishedAt: '2026-11-01T10:00:00',
      }),
    );

    await page.route('**/api/public/candidate-auth/send-code', route => ok(route, null));
    await page.route('**/api/public/candidate-auth/verify', route =>
      ok(route, {
        token: 'cand-jwt-token-001',
        expiresAt: '2099-12-31T23:59:59Z',
        candidate: {
          id: 77,
          email: 'candidate@example.com',
          name: null,
          phone: null,
        },
      }),
    );

    const buildApp = () => ({
      id: 501,
      jobId: 9,
      jobTitle: '高级 Java 后端工程师',
      department: '基础架构部',
      location: '上海',
      candidateLevel: selectedLevel,
      stage,
      availableModes: ['TEXT', 'VOICE'],
      interviewMode: started ? 'TEXT' : null,
      questionCount: 2,
      timeLimitMinutes: 30,
      submittedAt: '2026-11-17T10:00:00',
      inviteExpiresAt: '2026-11-24T10:00:00',
      interviewStartedAt: started ? '2026-11-17T10:05:00' : null,
      interviewDeadlineAt: started ? '2026-11-17T10:35:00' : null,
      interviewCompletedAt: stage === 'UNDER_REVIEW' ? '2026-11-17T10:15:00' : null,
      offlineInterview:
        stage === 'OFFLINE_INVITED' || stage === 'OFFLINE_CONFIRMED'
          ? {
              interviewTime: '2026-11-25T14:30:00',
              durationMinutes: 60,
              location: '上海市浦东新区张江科技大厦 12F',
              interviewer: '陈总监',
              notes: '请携带纸质简历',
              confirmStatus,
              candidateNote: null,
            }
          : null,
    });

    await page.route('**/api/candidate/jobs/9/apply', route => {
      capturedCandidateToken = route.request().headers()['x-candidate-token'];
      const postBody = route.request().postData() ?? '';
      expect(postBody).toContain('name="candidateLevel"');
      expect(postBody).toContain('senior');
      selectedLevel = 'senior';
      stage = 'SCREENING';
      return ok(route, buildApp());
    });

    await page.route('**/api/candidate/applications', route => {
      capturedCandidateToken = route.request().headers()['x-candidate-token'];
      return ok(route, [buildApp()]);
    });

    await page.route('**/api/candidate/applications/501', route => ok(route, buildApp()));

    await page.route('**/api/candidate/applications/501/interview', route =>
      ok(route, {
        applicationId: 501,
        jobTitle: '高级 Java 后端工程师',
        candidateLevel: selectedLevel,
        mode: started ? 'TEXT' : null,
        stage: started ? 'INTERVIEWING' : 'INTERVIEW_PENDING',
        startedAt: started ? '2026-11-17T10:05:00' : null,
        deadlineAt: started ? '2026-11-17T10:35:00' : null,
        remainingSeconds: started ? 1800 : null,
        totalQuestions: started ? 2 : 0,
        currentQuestionIndex: 0,
        currentQuestion: started
          ? { questionIndex: 0, question: '请说明分布式锁续期原理', category: 'Redis', followUp: false }
          : null,
        finished: false,
        voiceSessionId: null,
        voiceWsPath: null,
        plannedDurationMinutes: null,
      }),
    );

    await page.route('**/api/candidate/applications/501/interview/start', async route => {
      const body = route.request().postDataJSON() as { mode: string; candidateLevel?: string };
      expect(body.mode).toBe('TEXT');
      expect(body.candidateLevel).toBe('senior');
      started = true;
      await ok(route, {
        applicationId: 501,
        jobTitle: '高级 Java 后端工程师',
        candidateLevel: 'senior',
        mode: 'TEXT',
        stage: 'INTERVIEWING',
        startedAt: '2026-11-17T10:05:00',
        deadlineAt: '2026-11-17T10:35:00',
        remainingSeconds: 1800,
        totalQuestions: 2,
        currentQuestionIndex: 0,
        currentQuestion: {
          questionIndex: 0,
          question: '请说明分布式锁续期原理',
          category: 'Redis',
          followUp: false,
        },
        finished: false,
        voiceSessionId: null,
        voiceWsPath: null,
        plannedDurationMinutes: null,
      });
    });

    await page.route('**/api/candidate/applications/501/interview/answer', async route => {
      const body = route.request().postDataJSON() as { questionIndex: number; answer: string };
      if (body.questionIndex === 0) {
        await ok(route, {
          finished: false,
          nextQuestion: {
            questionIndex: 1,
            question: '如何解决高并发缓存击穿？',
            category: '缓存设计',
            followUp: false,
          },
          currentQuestionIndex: 1,
          totalQuestions: 2,
        });
        return;
      }
      stage = 'UNDER_REVIEW';
      await ok(route, {
        finished: true,
        nextQuestion: null,
        currentQuestionIndex: 2,
        totalQuestions: 2,
      });
    });

    await page.route('**/api/candidate/applications/501/proctor-events', route => ok(route, null));

    await page.route('**/api/candidate/applications/501/offline/confirm', route => {
      stage = 'OFFLINE_CONFIRMED';
      confirmStatus = 'CONFIRMED';
      return ok(route, buildApp());
    });

    // 1. 浏览热招岗位
    await page.goto('/careers');
    await expect(page.getByRole('heading', { name: '开放职位' })).toBeVisible();
    await page.getByText('高级 Java 后端工程师').click();

    // 2. 未登录提示邮箱验证 → 完成 OTP 登录并跳回岗位页
    await page.getByRole('link', { name: '验证邮箱并投递' }).click();
    await expect(page.getByRole('heading', { name: '候选人邮箱验证' })).toBeVisible();
    await page.getByPlaceholder('name@example.com').fill('candidate@example.com');
    await page.getByRole('button', { name: '获取验证码' }).click();
    await expect(page.getByText('验证码已发送至您的邮箱')).toBeVisible();
    await page.getByPlaceholder('请输入验证码').fill('886622');
    await page.getByRole('button', { name: '验证并继续' }).click();

    // 3. 填写信息、自主选择「高级」应聘定位、上传简历并勾选同意后提交
    await expect(page.getByText('当前验证邮箱：candidate@example.com')).toBeVisible();
    await expect(page.getByText('建议定位：中级')).toBeVisible();
    await page.getByPlaceholder('请输入真实姓名').fill('王小明');
    await page.getByPlaceholder('便于 HR 联系线下面试').fill('13912345678');
    await page.getByTestId('apply-level-senior').click();
    await page.locator('input[type="file"]').setInputFiles({
      name: 'wangxiaoming-cv.pdf',
      mimeType: 'application/pdf',
      buffer: Buffer.from('%PDF-1.4 mock resume'),
    });
    await page.locator('input[type="checkbox"]').check();
    await page.getByRole('button', { name: '提交简历' }).click();

    // 4. 跳转我的投递（默认开启初筛 → 简历筛选中），确认携带 X-Candidate-Token 与自选定位徽章
    await expect(page.getByRole('heading', { name: '我的投递' })).toBeVisible();
    await expect(page.getByText('简历筛选中')).toBeVisible();
    await expect(page.getByTestId('my-app-level-501')).toContainText('定位：高级');
    expect(capturedCandidateToken).toBe('cand-jwt-token-001');

    // 5. 模拟 AI 初筛通过后刷新 → 进入在线面试并同时展示定位与文字/语音自选卡片
    stage = 'INTERVIEW_PENDING';
    await page.getByRole('button', { name: '刷新进度' }).click();
    await page.getByRole('link', { name: '开始在线面试' }).click();
    await expect(page.getByTestId('interview-level-senior')).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByText('文字问答面试')).toBeVisible();
    await expect(page.getByText('实时语音面试')).toBeVisible();
    await page.getByRole('button', { name: '开始在线面试' }).click();

    // 6. 回答两道脱敏题目完成在线面试
    await expect(page.getByTestId('interview-active-level')).toContainText('定位：高级');
    await expect(page.getByText('请说明分布式锁续期原理')).toBeVisible();
    await page.getByPlaceholder(/请输入您的回答/).fill('通过 WatchDog 定时刷新锁过期时间。');
    await page.getByRole('button', { name: '提交本题，进入下一题' }).click();

    await expect(page.getByText('如何解决高并发缓存击穿？')).toBeVisible();
    await page.getByPlaceholder(/请输入您的回答/).fill('使用互斥锁重建与逻辑过期兜底。');
    await page.getByRole('button', { name: '提交并完成面试' }).click();

    await expect(page.getByRole('heading', { name: '在线面试已完成' })).toBeVisible();
    await page.getByRole('link', { name: '返回我的投递' }).click();

    // 7. 模拟 HR 发送线下面试邀约后，候选人一键确认按时参加
    stage = 'OFFLINE_INVITED';
    await page.getByRole('button', { name: '刷新进度' }).click();
    await expect(page.getByText('上海市浦东新区张江科技大厦 12F')).toBeVisible();
    await page.getByRole('button', { name: '确认按时参加' }).click();
    await expect(page.getByText('已确认按时参加线下面试')).toBeVisible();
  });

  test('HR 后台审核工作台执行人工通过、安排线下面试并配置 SMTP 邮件服务', async ({ page }) => {
    await seedAdminAuth(page, 'ADMIN');

    let currentStatus: 'AI_PASSED' | 'HR_APPROVED' | 'OFFLINE_SCHEDULED' = 'AI_PASSED';
    let offlineInfo: Record<string, unknown> | null = null;
    let savedSmtpHost: string | null = null;

    const buildDetail = () => ({
      id: 101,
      status: currentStatus,
      candidateId: 77,
      candidateName: '王小明',
      candidateEmail: 'candidate@example.com',
      candidatePhone: '13912345678',
      jobId: 9,
      jobTitle: '高级 Java 后端工程师',
      aiPassThreshold: 70,
      screeningThreshold: 60,
      resumeId: 300,
      resumeFilename: 'wangxiaoming-cv.pdf',
      resumeText: '8 年 Java 分布式交易研发经验',
      screeningScore: 88,
      screeningResult: {
        score: 88,
        summary: '高度匹配岗位高并发交易经验要求',
        strengths: ['熟悉 Redis 与分布式事务'],
        risks: [],
        matchedRequirements: ['高并发架构经验'],
        missingRequirements: [],
      },
      interviewMode: 'TEXT',
      interviewSessionId: 'sess-101',
      voiceSessionId: null,
      interviewStartedAt: '2026-11-17T10:05:00',
      interviewCompletedAt: '2026-11-17T10:25:00',
      aiScore: 86,
      aiDecision: 'PASS',
      aiReview: {
        finalDecision: 'PASS',
        interviewScore: 86,
        passThreshold: 70,
        llm: {
          decision: 'PASS',
          confidence: 0.92,
          summary: '技术功底扎实，建议进入线下面试深挖系统容灾方案',
          reasons: ['回答逻辑严密'],
          risks: [],
          suggestedQuestions: ['请介绍一次真实的线上故障复盘'],
        },
        fallback: false,
        note: null,
        proctorCounts: { PASTE: 1 },
      },
      taskError: null,
      hrReviewerId: currentStatus !== 'AI_PASSED' ? 1 : null,
      hrReviewerName: currentStatus !== 'AI_PASSED' ? '系统管理员' : null,
      hrComment: currentStatus !== 'AI_PASSED' ? '同意安排线下面试' : null,
      hrReviewedAt: currentStatus !== 'AI_PASSED' ? '2026-11-17T11:00:00' : null,
      proctorCounts: { PASTE: 1 },
      proctorEvents: [
        { id: 1, type: 'PASTE', detail: 'Q1', occurredAt: '2026-11-17T10:08:00' },
      ],
      proctorRisk: {
        score: 30,
        level: 'MEDIUM',
        findings: ['第 1 题粘贴 260 字（占 93%），作答期间离开 40 秒'],
        flaggedQuestions: [0],
      },
      answerTelemetry: [
        {
          questionIndex: 0,
          durationMs: 95000,
          firstInputMs: 42000,
          typedChars: 20,
          pastedChars: 260,
          maxBurstChars: 0,
          awayMs: 40000,
          answerChars: 280,
          pasteRatio: 0.93,
          flagged: true,
        },
      ],
      logs: [
        {
          id: 1,
          fromStatus: 'AI_REVIEWING',
          toStatus: 'AI_PASSED',
          operatorType: 'AI',
          operatorId: null,
          operatorName: 'AI',
          remark: 'AI 综合审核通过',
          createdAt: '2026-11-17T10:26:00',
        },
      ],
      offlineInterview: offlineInfo,
      emails: [],
      submittedAt: '2026-11-17T10:00:00',
      inviteExpiresAt: '2026-11-24T10:00:00',
      updatedAt: '2026-11-17T10:26:00',
    });

    await page.route('**/api/admin/jobs', route => ok(route, []));
    await page.route('**/api/admin/applications?*', route =>
      ok(route, {
        items: [
          {
            id: 101,
            jobId: 9,
            jobTitle: '高级 Java 后端工程师',
            candidateName: '王小明',
            candidateEmail: 'candidate@example.com',
            candidatePhone: '13912345678',
            status: currentStatus,
            interviewMode: 'TEXT',
            screeningScore: 88,
            aiScore: 86,
            aiDecision: 'PASS',
            proctorRiskScore: 30,
            proctorRiskLevel: 'MEDIUM',
            submittedAt: '2026-11-17T10:00:00',
            updatedAt: '2026-11-17T10:26:00',
          },
        ],
        total: 1,
        page: 1,
        size: 20,
      }),
    );

    await page.route('**/api/admin/applications/101', route => ok(route, buildDetail()));
    await page.route('**/api/admin/applications/101/approve', route => {
      currentStatus = 'HR_APPROVED';
      return ok(route, buildDetail());
    });
    await page.route('**/api/admin/applications/101/offline-interview', async route => {
      const payload = route.request().postDataJSON() as {
        interviewTime: string;
        durationMinutes: number;
        location: string;
        interviewer: string | null;
      };
      currentStatus = 'OFFLINE_SCHEDULED';
      offlineInfo = {
        scheduleId: 33,
        interviewTime: payload.interviewTime,
        durationMinutes: payload.durationMinutes,
        location: payload.location,
        interviewType: 'ONSITE',
        interviewer: payload.interviewer,
        roundNumber: 1,
        notes: null,
        confirmStatus: 'PENDING',
        candidateNote: null,
        confirmedAt: null,
      };
      await ok(route, buildDetail());
    });

    let savedWecomCorpId: string | null = null;

    await page.route('**/api/admin/mail/settings', async route => {
      if (route.request().method() === 'PUT') {
        const body = route.request().postDataJSON() as {
          channel: 'SMTP' | 'WECOM_API';
          host: string | null;
          wecomCorpId: string | null;
        };
        if (body.channel === 'WECOM_API') {
          savedWecomCorpId = body.wecomCorpId;
        } else {
          savedSmtpHost = body.host;
        }
        await ok(route, {
          enabled: true,
          channel: body.channel,
          host: body.host,
          port: 465,
          username: 'hr@company.com',
          passwordSet: true,
          security: 'SSL',
          fromAddress: body.channel === 'WECOM_API' ? null : 'hr@company.com',
          fromName: '星辰招聘团队',
          wecomCorpId: body.wecomCorpId,
          wecomSecretSet: body.channel === 'WECOM_API',
          wecomApiBaseUrl: null,
          companyName: '星辰科技',
          portalBaseUrl: 'http://localhost:5173',
          contactInfo: 'hr@company.com',
          updatedAt: '2026-11-17T11:10:00',
        });
        return;
      }
      await ok(route, {
        enabled: false,
        channel: 'SMTP',
        host: null,
        port: 465,
        username: null,
        passwordSet: false,
        security: 'SSL',
        fromAddress: null,
        fromName: null,
        wecomCorpId: null,
        wecomSecretSet: false,
        wecomApiBaseUrl: null,
        companyName: '星辰科技',
        portalBaseUrl: 'http://localhost:5173',
        contactInfo: null,
        updatedAt: null,
      });
    });

    await page.route('**/api/admin/mail/wecom/account', route =>
      ok(route, {
        email: 'recruit-app@star.wecom.work',
        aliasList: ['hr@star.com'],
      }),
    );

    await page.route('**/api/admin/mail/templates', route =>
      ok(route, [
        {
          code: 'OFFLINE_INVITE',
          name: '线下面试邀请',
          subject: '【{companyName}】线下面试邀请 - {jobTitle}',
          body: '{candidateName}，诚邀您参加线下面试',
          enabled: true,
          customized: false,
          variables: ['companyName', 'candidateName', 'jobTitle'],
          updatedAt: null,
        },
      ]),
    );

    // 1. 进入投递管理列表并跳转审核工作台
    await page.goto('/admin/applications');
    await expect(page.getByRole('heading', { name: '投递管理' })).toBeVisible();
    await expect(page.getByTestId('risk-cell-101')).toHaveText('中风险 · 30');
    await page.getByRole('link', { name: '去审核' }).click();

    // 2. 审核工作台查看 AI 评估结论与行为监测并点击人工通过
    await expect(page.getByText('技术功底扎实，建议进入线下面试深挖系统容灾方案')).toBeVisible();
    await expect(page.getByText('粘贴内容：1 次')).toBeVisible();
    await expect(page.getByTestId('proctor-risk-badge')).toHaveText('作答行为：中风险（30 分）');
    await expect(page.getByTestId('proctor-findings')).toContainText('第 1 题粘贴 260 字');
    await page.getByPlaceholder(/填写审核意见/).fill('同意安排线下面试');
    await page.getByRole('button', { name: '审核通过（进入线下邀约）' }).click();
    await expect(page.getByText('已审核通过，请继续安排线下面试')).toBeVisible();

    // 3. 填写线下面试时间与地点并发送日历邀约邮件
    await page.locator('input[type="datetime-local"]').fill('2026-12-01T14:30');
    await page.getByPlaceholder(/北京市朝阳区/).fill('上海市浦东新区张江科技大厦 12F');
    await page.getByPlaceholder('如：张工').fill('陈总监');
    await page.getByRole('button', { name: '保存并发送线下面试邀请' }).click();
    await expect(page.getByText('线下面试邀约已保存，邀请邮件与日历附件已加入发送队列')).toBeVisible();

    // 4. 访问后台邮件设置页配置 SMTP 并可切换至企业微信邮件 API
    await page.goto('/admin/mail');
    await expect(page.getByRole('heading', { name: '邮件设置' })).toBeVisible();
    await page.locator('input[type="checkbox"]').first().check();
    await page.getByPlaceholder('如：smtp.exmail.qq.com').fill('smtp.exmail.qq.com');
    await page.getByPlaceholder('如：hr@company.com').first().fill('hr@company.com');
    await page.getByPlaceholder('请输入 SMTP 密码或授权码').fill('smtp-secret-token');
    await page.locator('input[type="email"]').first().fill('hr@company.com');
    await page.getByRole('button', { name: '保存 SMTP 配置' }).click();
    await expect(page.getByText('SMTP 邮件设置已保存')).toBeVisible();
    expect(savedSmtpHost).toBe('smtp.exmail.qq.com');

    // 5. 切换为企业微信邮件 API 通道，保存 CorpID + Secret 并校验应用发信邮箱查询
    await page.getByRole('button', { name: /企业微信邮件 API/ }).click();
    await expect(page.getByText('企业微信邮件 API 接入指引')).toBeVisible();
    await page.getByPlaceholder('如：ww1234567890abcdef').fill('ww9876543210');
    await page.getByPlaceholder('请输入具备邮件权限的应用 Secret').fill('wecom-mail-secret');
    await page.getByRole('button', { name: '保存企业微信配置' }).click();
    await expect(page.getByText('企业微信邮件 API 配置已保存')).toBeVisible();
    expect(savedWecomCorpId).toBe('ww9876543210');

    await page.getByRole('button', { name: '查询应用发信邮箱' }).click();
    await expect(page.getByText('当前应用主发信邮箱：recruit-app@star.wecom.work（别名：hr@star.com）')).toBeVisible();
  });

  test('岗位启用在线笔试时，候选人先完成单选/多选/填空笔试再进入 AI 面试，HR 后台查看逐题判分与加权分', async ({ page }) => {
    await page.addInitScript(() => {
      localStorage.setItem('ig_candidate_token', 'cand-jwt-written-002');
      localStorage.setItem(
        'ig_candidate_profile',
        JSON.stringify({ id: 88, email: 'written@example.com', name: '李华', phone: null }),
      );
    });

    let writtenStatus: 'IN_PROGRESS' | 'GRADED' | null = null;
    let submittedAnswers: Record<string, string[]> | null = null;

    let writtenLevel: 'junior' | 'mid' | 'senior' = 'mid';

    await page.route('**/api/candidate/applications/602', route =>
      ok(route, {
        id: 602,
        jobId: 12,
        jobTitle: '后端研发工程师',
        department: '技术部',
        location: '杭州',
        candidateLevel: writtenLevel,
        stage: 'INTERVIEW_PENDING',
        availableModes: ['TEXT', 'VOICE'],
        interviewMode: null,
        questionCount: 5,
        timeLimitMinutes: 30,
        submittedAt: '2026-11-18T09:00:00',
        inviteExpiresAt: '2026-11-25T09:00:00',
        interviewStartedAt: null,
        interviewDeadlineAt: null,
        interviewCompletedAt: null,
        offlineInterview: null,
      }),
    );

    await page.route('**/api/candidate/applications/602/interview', route =>
      ok(route, {
        applicationId: 602,
        jobTitle: '后端研发工程师',
        candidateLevel: writtenLevel,
        mode: null,
        stage: 'INTERVIEW_PENDING',
        startedAt: null,
        deadlineAt: null,
        remainingSeconds: null,
        totalQuestions: 0,
        currentQuestionIndex: 0,
        currentQuestion: null,
        finished: false,
        voiceSessionId: null,
        voiceWsPath: null,
        plannedDurationMinutes: null,
        proctorAwayReminder: true,
        watermarkText: '李华 · #602',
        writtenTestEnabled: true,
        writtenTestStatus: writtenStatus,
        writtenQuestionCount: 3,
        writtenTimeLimitMinutes: 15,
      }),
    );

    await page.route('**/api/candidate/applications/602/written-test/start', route => {
      const body = route.request().postDataJSON() as { candidateLevel?: string };
      expect(body.candidateLevel).toBe('junior');
      writtenLevel = 'junior';
      writtenStatus = 'IN_PROGRESS';
      return ok(route, {
        applicationId: 602,
        candidateLevel: 'junior',
        status: 'IN_PROGRESS',
        totalQuestions: 3,
        questions: [
          {
            index: 0,
            type: 'SINGLE_CHOICE',
            question: 'MySQL InnoDB 默认事务隔离级别是什么？',
            options: ['READ COMMITTED', 'REPEATABLE READ', 'SERIALIZABLE'],
            category: 'MySQL',
          },
          {
            index: 1,
            type: 'MULTIPLE_CHOICE',
            question: '以下属于 Java 并发包常用同步工具类的有哪些？',
            options: ['CountDownLatch', 'ArrayList', 'CyclicBarrier', 'HashMap'],
            category: 'JUC',
          },
          {
            index: 2,
            type: 'FILL_BLANK',
            question: 'Redis 默认监听的 TCP 端口号是____',
            options: [],
            category: 'Redis',
          },
        ],
        answers: {},
        startedAt: '2026-11-18T09:05:00',
        deadlineAt: '2026-11-18T09:20:00',
        remainingSeconds: 900,
        watermarkText: '李华 · #602',
        proctorAwayReminder: true,
      });
    });

    await page.route('**/api/candidate/applications/602/written-test/answers', route => ok(route, null));

    await page.route('**/api/candidate/applications/602/written-test/submit', async route => {
      const body = route.request().postDataJSON() as { answers: Record<string, string[]> };
      submittedAnswers = body.answers;
      writtenStatus = 'GRADED';
      await ok(route, {
        applicationId: 602,
        candidateLevel: writtenLevel,
        status: 'GRADED',
        totalQuestions: 3,
        questions: [],
        answers: {},
        startedAt: '2026-11-18T09:05:00',
        deadlineAt: '2026-11-18T09:20:00',
        remainingSeconds: null,
        watermarkText: '李华 · #602',
        proctorAwayReminder: true,
      });
    });

    await page.route('**/api/candidate/applications/602/proctor-events', route => ok(route, null));

    // 1. 候选人进入面试链接，首先看到独立「在线笔试」环节与定位选择
    await page.goto('/careers/applications/602/interview');
    await expect(page.getByTestId('written-intro-card')).toBeVisible();
    await expect(page.getByText('环节 1 / 2 · 在线笔试')).toBeVisible();
    await page.getByTestId('written-level-junior').click();

    // 2. 开始笔试，依次作答单选、多选与填空题并交卷
    await page.getByTestId('start-written-btn').click();
    await expect(page.getByTestId('written-active-panel')).toBeVisible();
    await expect(page.getByTestId('written-active-level')).toContainText('定位：初级');
    await page.getByTestId('written-opt-0-B').click();
    await page.getByTestId('written-opt-1-A').click();
    await page.getByTestId('written-opt-1-C').click();
    await page.getByTestId('written-fill-2').fill('TCP 6379');
    await page.getByTestId('submit-written-btn').click();

    expect(submittedAnswers).toEqual({
      '0': ['B'],
      '1': ['A', 'C'],
      '2': ['TCP 6379'],
    });

    // 3. 交卷后自动进入第 2 环节：定位继承为「初级」，可选择形式开始 AI 面试
    await expect(page.getByTestId('written-finished-banner')).toBeVisible();
    await expect(page.getByTestId('interview-level-junior')).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByText('文字问答面试')).toBeVisible();
    await expect(page.getByText('实时语音面试')).toBeVisible();

    // 4. HR 后台查看候选人自选定位、笔试逐题判分（含 AI 语义判分标记）与综合加权分
    await seedAdminAuth(page, 'ADMIN');
    await page.route('**/api/admin/applications/602', route =>
      ok(route, {
        id: 602,
        status: 'AI_PASSED',
        candidateId: 88,
        candidateName: '李华',
        candidateEmail: 'written@example.com',
        candidatePhone: null,
        candidateLevel: 'junior',
        jobId: 12,
        jobTitle: '后端研发工程师',
        jobDifficulty: 'mid',
        aiPassThreshold: 70,
        screeningThreshold: 60,
        resumeId: 301,
        resumeFilename: 'lihua.pdf',
        resumeText: 'Java 研发经验',
        screeningScore: 85,
        screeningResult: null,
        writtenTest: {
          status: 'GRADED',
          score: 100,
          correctCount: 3,
          totalCount: 3,
          weight: 30,
          startedAt: '2026-11-18T09:05:00',
          deadlineAt: '2026-11-18T09:20:00',
          submittedAt: '2026-11-18T09:10:00',
          gradedAt: '2026-11-18T09:10:02',
          items: [
            {
              index: 0,
              type: 'SINGLE_CHOICE',
              question: 'MySQL InnoDB 默认事务隔离级别是什么？',
              options: ['READ COMMITTED', 'REPEATABLE READ', 'SERIALIZABLE'],
              correctAnswers: ['B'],
              explanation: '默认可重复读',
              category: 'MySQL',
              candidateAnswer: ['B'],
              correct: true,
              judgedBy: 'RULE',
              reason: null,
            },
            {
              index: 2,
              type: 'FILL_BLANK',
              question: 'Redis 默认监听的 TCP 端口号是____',
              options: [],
              correctAnswers: ['6379'],
              explanation: null,
              category: 'Redis',
              candidateAnswer: ['TCP 6379'],
              correct: true,
              judgedBy: 'AI',
              reason: '指明默认端口 6379，语义一致',
            },
          ],
        },
        interviewMode: 'TEXT',
        interviewSessionId: 'sess-602',
        voiceSessionId: null,
        interviewStartedAt: '2026-11-18T09:12:00',
        interviewCompletedAt: '2026-11-18T09:30:00',
        aiScore: 86,
        aiDecision: 'PASS',
        aiReview: {
          finalDecision: 'PASS',
          interviewScore: 80,
          passThreshold: 70,
          llm: null,
          fallback: false,
          note: null,
          proctorCounts: {},
          written: {
            writtenScore: 100,
            weight: 30,
            interviewScore: 80,
            finalScore: 86,
            correctCount: 3,
            totalCount: 3,
          },
        },
        taskError: null,
        hrReviewerId: null,
        hrReviewerName: null,
        hrComment: null,
        hrReviewedAt: null,
        proctorCounts: {},
        proctorEvents: [],
        logs: [],
        offlineInterview: null,
        emails: [],
        submittedAt: '2026-11-18T09:00:00',
        inviteExpiresAt: '2026-11-25T09:00:00',
        updatedAt: '2026-11-18T09:31:00',
      }),
    );

    await page.goto('/admin/applications/602');
    await expect(page.getByTestId('candidate-level-badge')).toContainText('自选定位：初级（岗位建议：中级）');
    await expect(page.getByTestId('written-weighted-summary')).toContainText('综合分 86 分 = 笔试 100 分 × 30% + 面试 80 分 × 70%');
    await expect(page.getByTestId('written-detail-card')).toContainText('100 分（答对 3 / 3 题）');
    await expect(page.getByTestId('written-detail-card')).toContainText('AI 语义判分');
  });
});

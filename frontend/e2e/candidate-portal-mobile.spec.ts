import { expect, test, type Page, type Route } from '@playwright/test';

const ok = (route: Route, data: unknown) =>
  route.fulfill({ json: { code: 200, message: 'success', data } });

const JOB_SUMMARY = {
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
};

async function mockPublicJobs(page: Page) {
  await page.route('**/api/public/jobs', route => ok(route, [JOB_SUMMARY]));
  await page.route('**/api/public/jobs/9', route =>
    ok(route, {
      ...JOB_SUMMARY,
      jdText: '负责核心交易系统高可用架构设计与性能优化。',
      difficulty: 'mid',
      questionCount: 2,
      timeLimitMinutes: 30,
    }),
  );
}

async function expectNoHorizontalOverflow(page: Page) {
  const overflow = await page.evaluate(
    () => document.documentElement.scrollWidth - window.innerWidth,
  );
  expect(overflow).toBeLessThanOrEqual(1);
}

test.describe('默认入口与面试者端移动适配', () => {
  test('默认入口 / 指向面试者门户，后台需通过 /admin 访问并跳转登录', async ({ page }) => {
    await mockPublicJobs(page);

    await page.goto('/');
    await expect(page).toHaveURL(/\/careers$/);
    await expect(page.getByRole('heading', { name: '开放职位' })).toBeVisible();

    // 未知路径同样回到面试者门户，而不是后台
    await page.goto('/some-unknown-path');
    await expect(page).toHaveURL(/\/careers$/);

    // 后台入口：未登录时跳转后台登录页
    await page.goto('/admin');
    await expect(page).toHaveURL(/\/admin\/login/);
  });

  test.describe('手机视口（390×844）', () => {
    test.use({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true });

    test('职位列表在手机端无横向溢出，投递简历时展示 AI 等待提示与小游戏，并在我的投递展示初筛等待卡片', async ({
      page,
    }) => {
      await mockPublicJobs(page);
      await page.addInitScript(() => {
        localStorage.setItem('ig_candidate_token', 'cand-jwt-token-mobile');
        localStorage.setItem('ig_candidate_token_expires_at', '2099-12-31T23:59:59Z');
        localStorage.setItem(
          'ig_candidate_profile',
          JSON.stringify({ id: 77, email: 'candidate@example.com', name: null, phone: null }),
        );
      });

      await page.route('**/api/candidate/jobs/9/invitation', route =>
        ok(route, {
          inviteOnly: true,
          canApply: true,
          invited: true,
          expired: false,
          revoked: false,
          skipScreening: false,
          expiresAt: '2026-12-31T23:59:59',
          message: '已获邀请',
        }),
      );

      const app = {
        id: 501,
        jobId: 9,
        jobTitle: '高级 Java 后端工程师',
        department: '基础架构部',
        location: '上海',
        candidateLevel: 'mid',
        stage: 'SCREENING',
        availableModes: ['TEXT', 'VOICE'],
        interviewMode: null,
        questionCount: 2,
        timeLimitMinutes: 30,
        submittedAt: '2026-11-17T10:00:00',
        inviteExpiresAt: null,
        offlineInterview: null,
        screeningFeedback: null,
        assessmentFeedback: null,
      };

      let releaseApply: () => void = () => {};
      const applyGate = new Promise<void>(resolve => {
        releaseApply = resolve;
      });
      await page.route('**/api/candidate/jobs/9/apply', async route => {
        await applyGate;
        await ok(route, app);
      });
      await page.route('**/api/candidate/applications', route => ok(route, [app]));

      await page.goto('/');
      await expect(page).toHaveURL(/\/careers$/);
      await expect(page.getByText('高级 Java 后端工程师')).toBeVisible();
      await expect(page.getByRole('link', { name: '我的投递' })).toBeVisible();
      await expectNoHorizontalOverflow(page);

      await page.getByText('高级 Java 后端工程师').click();
      await expect(page).toHaveURL(/\/careers\/9$/);
      await expect(page.getByRole('heading', { name: '投递该职位' })).toBeVisible();
      await expectNoHorizontalOverflow(page);

      await page.getByPlaceholder('请输入真实姓名').fill('张三');
      await page.locator('input[type="file"]').setInputFiles({
        name: 'resume.pdf',
        mimeType: 'application/pdf',
        buffer: Buffer.from('%PDF-1.4 mock resume'),
      });
      await page.getByRole('checkbox').check();
      await page.getByRole('button', { name: '提交简历' }).click();
      await page.getByTestId('confirm-apply-dialog-btn').click();

      // 上传解析较慢时展示等待浮层（提示 + 可选小游戏）
      const overlay = page.getByTestId('apply-uploading-overlay');
      await expect(overlay).toBeVisible();
      await expect(overlay.getByText('正在上传并解析您的简历')).toBeVisible();
      await page.getByTestId('apply-uploading-overlay-game-toggle').click();
      await expect(page.getByTestId('waiting-memory-game')).toBeVisible();

      releaseApply();

      await expect(page).toHaveURL(/\/careers\/me/);
      await expect(page.getByTestId('screening-waiting-501')).toBeVisible();
      await expect(page.getByText('AI 正在智能初筛您的简历')).toBeVisible();
      await expectNoHorizontalOverflow(page);
    });
  });
});

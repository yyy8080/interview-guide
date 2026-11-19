# InterviewGuide 生产环境部署与升级运维指南

本文档面向企业自托管与云服务器生产环境，涵盖 **Docker Compose 一键部署**、**HTTPS + WebSocket 反向代理**、**企业微信邮件 API / SMTP 发信配置**、**在线笔试与防作弊初始化** 以及 **后续平滑升级** 全流程。

---

## 一、部署架构与端口规划

| 服务组件 | 容器名（Compose） | 默认端口 | 对外暴露建议 | 说明 |
| :--- | :--- | :--- | :--- | :--- |
| **前端 + Nginx 网关** | `interview-frontend` | `80`（或前置 HTTPS `443`） | **允许公网访问** | 托管候选人门户 `/careers` 与 HR 后台 `/admin`，反向代理 `/api/` 与 `/ws/` |
| **Spring Boot 后端** | `interview-app` | `8080` | 仅内网 / 本机回环 | 核心业务、AI 初筛/笔试/面试、WebSocket 实时语音、定时任务 |
| **PostgreSQL 16 (pgvector)** | `interview-postgres` | `5432` | **禁止公网直连** | 业务关系数据 + RAG 向量检索 + Flyway 自动迁移 |
| **Redis 7** | `interview-redis` | `6379` | **禁止公网直连** | 会话缓存、分布式限流、Redis Stream 异步分析与评估队列 |
| **MinIO (S3 兼容存储)** | `interview-minio` | `9000` / `9001` | 建议仅内网访问 | 存储候选人简历原文（PDF/Word）、知识库文档 |

> **重要安全提示**：
> 1. **在线语音面试依赖浏览器麦克风权限（`getUserMedia`）**。除 `http://localhost` 外，现代浏览器要求**必须通过 `https://` 访问页面**才能开启麦克风，生产环境请务必在最外层配置 HTTPS 证书。
> 2. 云服务器安全组 / 防火墙建议仅放行 `80`、`443`（以及运维 SSH），避免将 `5432`、`6379`、`9000`、`9001` 直接暴露到公网（或在 `docker-compose.yml` 中改为 `127.0.0.1:5432:5432` 仅绑定本机）。

---

## 二、方式一：Docker Compose 一键生产部署（推荐）

### 1. 服务器建议配置

- **CPU / 内存**：最低 2 核 4 GB（推荐 **4 核 8 GB**，保障 Java 25、PostgreSQL 向量检索与构建过程流畅）
- **磁盘**：40 GB+ SSD
- **软件环境**：Docker 24+ 与 Docker Compose v2（`docker compose version`）

### 2. 拉取代码并生成生产密钥

```bash
git clone https://github.com/yyy8080/interview-guide.git
cd interview-guide
cp .env.example .env
```

使用以下命令生成两个高强度随机密钥，准备填入 `.env`：

```bash
# 1) 生成敏感配置加密主密钥（加密大模型 API Key、SMTP 密码、企业微信 Secret）
openssl rand -base64 32

# 2) 生成后台与候选人 JWT 签名密钥（至少 32 字节）
openssl rand -base64 48
```

### 3. 编辑 `.env` 生产配置

编辑项目根目录下的 `.env`（将下方域名、密码与密钥替换为实际值）：

```dotenv
# === 1. AI 服务必填 ===
# 阿里云百炼 DashScope API Key（同时驱动默认 LLM、Qwen3 实时语音识别 ASR 与语音合成 TTS）
AI_BAILIAN_API_KEY=sk-xxxxxxxxxxxxxxxxxxxxxxxx
AI_MODEL=qwen3.5-flash

# 敏感配置 AES-GCM 加密主密钥（必填！部署后切勿随意修改，否则已保存的邮件密钥与模型 Key 无法解密）
APP_AI_CONFIG_ENCRYPTION_KEY=替换为上面生成的32字节随机串

# === 2. HR 后台安全与初始管理员 ===
APP_SECURITY_JWT_SECRET=替换为上面生成的48字节随机串
APP_SECURITY_TOKEN_TTL=8h
APP_SECURITY_INIT_ADMIN_USERNAME=admin
APP_SECURITY_INIT_ADMIN_PASSWORD=替换为强密码_首次启动自动创建
APP_SECURITY_PUBLIC_API_DOCS=false

# === 3. 招聘门户域名与跨域白名单 ===
# 候选人邮件中跳转链接的基准地址（末尾不要带 /）
APP_RECRUITMENT_PORTAL_BASE_URL=https://careers.yourcompany.com
APP_RECRUITMENT_COMPANY_NAME=你的公司名称
APP_RECRUITMENT_INVITE_ONLY=true
APP_RECRUITMENT_INVITE_EXPIRE_DAYS=7
APP_RECRUITMENT_OTP_LOG_CODE=false
# 可选：指定后台批量上传简历图片识别专用的 Provider / 多模态模型（默认留空沿用 AI_MODEL=qwen3.5-flash；若主模型纯文本不支持识图会自动回退 dashscope qwen-vl-plus）
# APP_RECRUITMENT_VISION_PROVIDER=dashscope
# APP_RECRUITMENT_VISION_MODEL=qwen-vl-plus

# 允许的前端 Origin（包含协议和域名，多个用英文逗号分隔；语音 WebSocket 握手会校验此项）
CORS_ALLOWED_ORIGINS=https://careers.yourcompany.com

# === 4. 数据库与对象存储密码 ===
POSTGRES_PASSWORD=替换为数据库强密码
MINIO_ROOT_USER=minio_prod_admin
MINIO_ROOT_PASSWORD=替换为MinIO强密码
```

### 4. 构建并启动服务

```bash
docker compose up -d --build
```

查看启动状态与健康检查：

```bash
docker compose ps
docker compose logs -f app
```

当 `interview-app` 日志输出 `Started App in ... seconds` 且访问 `http://127.0.0.1:8080/actuator/health` 返回 `{"status":"UP"}` 时，表示后端与 Flyway 数据库迁移均已就绪。

---

## 三、HTTPS 域名反向代理配置（Nginx / Caddy）

如果服务器最外层使用宿主机 Nginx 或云负载均衡承接 HTTPS 并转发到 `interview-frontend`（或前后端分离部署时直接转发到后端 `8080`），请确保满足以下三点：

1. **`/api/` 读取超时不少于 `300s`**：候选人开启在线笔试或 AI 面试时，大模型实时生成定制单选/多选/填空题与面试题可能需要 15–90 秒，默认 60 秒会导致 `504 Gateway Time-out`。
2. **`/ws/` 开启 WebSocket Upgrade 并透传 `Host $http_host`**：覆盖候选人语音面试 `/ws/candidate/voice-interview/{sessionId}` 与模拟语音面试 `/ws/voice-interview/{sessionId}`。
3. **`client_max_body_size 100M`**：支持简历、知识库文档及后台批量简历图片上传识别。

### 外部 HTTPS Nginx 配置示例（转发至 Compose `frontend` 容器 `127.0.0.1:80`）

> 若宿主机 80 端口被外层 Nginx 占用，可将 `docker-compose.yml` 中 `frontend` 的端口映射改为 `"127.0.0.1:8090:80"`，下方 `proxy_pass` 对应指向 `http://127.0.0.1:8090`。

```nginx
server {
    listen 80;
    server_name careers.yourcompany.com;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl http2;
    server_name careers.yourcompany.com;

    ssl_certificate     /etc/nginx/ssl/careers.yourcompany.com.pem;
    ssl_certificate_key /etc/nginx/ssl/careers.yourcompany.com.key;

    client_max_body_size 50M;

    # WebSocket 实时语音面试通道
    location /ws/ {
        proxy_pass http://127.0.0.1:8090;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $http_host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
        proxy_buffering off;
    }

    # 后端 API（AI 出题/判分/评估长耗时请求）
    location /api/ {
        proxy_pass http://127.0.0.1:8090;
        proxy_set_header Host $http_host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 300s;
        proxy_connect_timeout 300s;
        proxy_send_timeout 300s;
    }

    # 前端静态资源与 SPA 路由
    location / {
        proxy_pass http://127.0.0.1:8090;
        proxy_set_header Host $http_host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

---

## 四、方式二：宿主机独立部署（不使用应用容器）

若已有现成的 PostgreSQL 14+（需安装 `CREATE EXTENSION IF NOT EXISTS vector;`）、Redis 6+ 与 S3/MinIO 对象存储，可直接编译运行：

```bash
# 1. 构建后端可执行 Jar（需 JDK 25）
./gradlew :app:bootJar --no-daemon -x test
# 产物路径：app/build/libs/app-*.jar

# 2. 构建前端静态资源（需 Node.js 18+ 与 pnpm 10+）
corepack enable
pnpm --dir frontend install --frozen-lockfile
pnpm --dir frontend run build
# 产物路径：frontend/dist/
```

- 将 `frontend/dist` 部署至 Nginx `root` 目录，参考 [`frontend/nginx.conf`](./frontend/nginx.conf) 配置 `try_files $uri $uri/ /index.html;`、`/api/` 与 `/ws/` 转发至 `http://127.0.0.1:8080`。
- 使用 `systemd` 托管后端进程，通过 `EnvironmentFile=/opt/interview-guide/.env` 注入环境变量并执行 `/usr/bin/java -jar /opt/interview-guide/app.jar`。

---

## 五、首次上线初始化清单

部署启动后，按以下顺序完成业务初始化即可正式对外招聘：

### 0. 访问入口说明（默认入口为面试者门户）
- **默认入口指向面试者页面**：直接访问根域名 `https://careers.yourcompany.com/`（以及任何未知路径）会自动跳转到面试者门户 `/careers`（热招职位 → 邮箱验证 → 投递简历 → 在线笔试/面试 → 我的投递），**不会再直接进入 HR 管理后台**，可放心将根域名作为对外招聘链接投放。
- **HR 管理后台入口**：通过 `https://careers.yourcompany.com/admin`（自动跳转后台首页「投递管理」`/admin/applications`）或 `https://careers.yourcompany.com/admin/login` 访问；未登录时统一跳转后台登录页，登录后回到原目标页面。建议将后台地址仅在内部分发（如需更强隔离，可在外层 Nginx 对 `/admin` 前缀加 IP 白名单，注意 `/api/admin` 接口本身已有 JWT 鉴权）。
- **面试者端手机适配**：面试者门户全部页面（职位列表/详情与投递、邮箱验证登录、我的投递、在线笔试、文字/语音面试、结果与解析页）已适配手机端（≈375px 宽起）：紧凑顶栏与安全区（刘海屏/底部横条）适配、按钮在手机端整行展示便于点按、选项触控区域 ≥44px、输入框 16px 字号避免 iOS 自动缩放、文字面试作答框吸底便于软键盘弹出时继续作答。无需额外部署配置（`index.html` 已设置 `viewport-fit=cover`）。
- **AI 生成等待提示与小游戏**：所有需要等待 AI 处理的环节（上传并解析简历、修改重提简历、AI 简历初筛、生成笔试试卷、生成面试题目、笔试判分、面试结束后的综合评估）均展示等待面板：已等待时长与预计耗时、动态进度条、轮播的面试小贴士，以及可选的「记忆翻牌」小游戏（最佳成绩保存在浏览器本地，不上报、不影响评分与防作弊记录）；答题计时中的等待（如提交本题后等待下一题）仅展示轻量提示，不提供游戏以免分散注意力。「我的投递」在 AI 初筛/评估进行中会每 6 秒静默刷新进度（最长约 3 分钟），结果出来后自动更新。
- **AI 出题等待不计入作答时长**：在线笔试与文字/语音面试均在 AI 题目生成完成后才开始计时，网络或模型较慢时不会占用候选人的作答时间。

### 1. 登录 HR 管理后台与分配账号
- 访问 `https://careers.yourcompany.com/admin/login`（或 `https://careers.yourcompany.com/admin`），使用 `.env` 中配置的初始管理员账号登录。
- 在后台「团队账号管理」中按需创建：
  - `ADMIN`（系统管理员：全权限，含邮件设置、账号管理）
  - `HR`（招聘专员：岗位管理、简历与笔试面试复审、安排线下面试、重发邮件）
  - `INTERVIEWER`（面试官：只读查看候选人简历、笔试明细、面试评估报告与日程）

### 2. 配置邮件发送服务（`/admin/mail`）
系统支持 **企业微信邮件 API** 与 **标准 SMTP** 双通道随时切换，密钥采用 AES-GCM 加密入库：

- **通道 A：企业微信邮件 API（推荐企业微信用户使用）**
  1. 登录 [企业微信管理后台](https://work.weixin.qq.com/) → **应用管理** → 创建或选择一个自建应用，记录 `AgentId` 与 `Secret`，在「我的企业」底部记录 `企业 ID (CorpID)`。
  2. 在该应用详情页底部配置 **企业可信 IP**，填入部署服务器的**公网出口 IP**（未配置会返回 `errcode=60020`）。
  3. 在 **协作 → 邮件 → API / 应用发信权限**（或应用接口权限）中，为该自建应用开启「**发送邮件**」权限并分配应用主发信邮箱。
  4. 回到本系统 `/admin/mail`，通道选择 **企业微信 API**，填入 `CorpID` 与应用 `Secret` 并保存，可点击「**查询应用发信邮箱**」校验连通性并自动填入发件邮箱。
  5. 确认下方的「候选人门户地址（`portalBaseUrl`）」为公网 HTTPS 地址（如 `https://careers.yourcompany.com`），点击「发送测试邮件」验证收信（含 `.ics` 日历邀请支持）。

- **通道 B：标准 SMTP 服务器**
  1. 通道选择 **SMTP**，填写 SMTP 服务器地址（如 `smtp.exmail.qq.com` / `smtp.feishu.cn`）、端口（SSL 常用 `465`，STARTTLS 常用 `587`）、发件账号与客户端专用授权码。
  2. 保存后点击「发送测试邮件」验证连通性。

### 3. 创建招聘岗位（`/admin/jobs`）
在岗位新建/编辑页按需开启全链路能力：
- **AI 简历初筛（默认 60 分 + 1 次修改重提机会）与未通过原因透明反馈**：新建岗位默认初筛分数线为 **60 分**（可通过环境变量 `APP_RECRUITMENT_DEFAULT_SCREENING_THRESHOLD` 调整系统缺省值，或在单个岗位中自定义分数线）；首次未通过初筛时，求职者可在 `/careers/me`「我的投递」及结果通知邮件（`{rejectReason}` 变量）中查看脱敏的具体未通过原因与主要差距，并获得 **1 次修改完善并重新上传简历** 重新触发初筛的机会（可通过环境变量 `APP_RECRUITMENT_MAX_RESUME_REVISIONS` 配置次数，默认 `1`；机会用完仍未达线则不可再重提，且 HR 内部人工复审评语 `hrComment` 始终保密）。
- **答题终态未通过原因与错题/未达标题目解析透明展示**：候选人提交笔试与面试后，一旦进入终态未通过（`AI_REJECTED` 或 `HR_REJECTED`），可在交卷结果页与 `/careers/me`「我的投递」直接查看脱敏的**未通过原因**（含综合分/笔试分/面试分概览、AI 评估总结与主要薄弱项，严禁泄露 HR 内部评语）以及**没通过的题目解析**（含在线笔试错题/未答题的标准答案与解析、文字/语音面试未达标题目的失分点评、核心要点与参考解析；答题中与审核中严格隐藏标准答案防泄题）。
- **投递时选择前后端技术方向（偏向前端 / 偏向后端），后续初筛与出题全链路自动沿用**：候选人在首次投递简历（及初筛未通过修改重提简历）时可直接选择**偏向前端（`FRONTEND`）**或**偏向后端（`BACKEND`）**（系统会根据岗位名称智能预设默认项），**进入在线笔试与文字/语音面试答题时无需再次选择**；AI 简历初筛、在线笔试实时出题、文字面试与实时语音面试题目生成（含预设后端/前端 Skill 自动切换与考察分类过滤）以及 AI 最终综合决策均自动围绕投递时选定的技术方向执行，HR 可在投递列表与详情页直观查看。
- **默认定位与候选人自选定位（初级 / 中级 / 高级）及考前特长补充**：岗位可设置建议定位（默认 `中级`），候选人在投递简历、初筛未通过重提简历及进入在线笔试 / AI 面试开考前均可自主选择 `初级`、`中级` 或 `高级`；同时在笔试或面试开考前可选填**有无 AI 相关经验**（`暂无 AI 相关经验` / `日常使用 AI 工具辅助提效` / `有 AI / 大模型项目实战经验`）及**个人其他特长与技术亮点**（面试前自动回显笔试前已填内容并允许补充修改），AI 将结合投递时选定的前后端方向、自选定位与个人特长生成差异化笔试题、面试追问与综合评估建议，HR 可在投递详情页对照查看。
- **AI 在线笔试（选择题 / 填空题，仅限手动点击按钮二次确认提交）**：
  - 开启后候选人进入考场时将**先完成在线笔试，手动点击「提交笔试」并在确认弹窗中二次确认交卷后无缝进入 AI 面试**（系统不会因倒计时归零或按回车自动交卷，无论是否答完全部题目点击提交均弹出二次确认提示以避免误提交；无需人工维护题库，AI 根据岗位 JD、候选人投递时所选前后端方向与定位实时出题并打乱选项）。
  - 可自定义**单选题数、多选题数、填空题数**（总题数 1–30 题）、**参考笔试时长**（5–120 分钟）以及**笔试得分占最终综合分的权重**（0%–100%，默认 30%）。
- **AI 在线面试模式与简历真实性核验**：支持 `BOTH`（默认，候选人开考时自主选择文字或实时语音作答）、`TEXT`（仅文字）、`VOICE`（仅实时语音）；实时语音面试在开场与多轮互动中自动锚定候选人自身简历中的项目、负责模块与量化成果追问实现细节，并在语音评估与 AI 综合复审中交叉核验简历真实性（对夸大或无法自圆其说的经历自动降分并标记“简历真实性存疑”）。
- **低打扰防作弊、前置诚信审查与实际粘贴内容文本展示**：候选人在首次投递简历、初筛未通过重提简历前均会收到醒目警示与二次确认弹窗（明确告知**请勿乱提交简历、仅有两次提交机会、简历不允许造假否则直接不通过**，AI 综合决策发现简历虚构/造假时直接判 `REJECT`）；在进入在线笔试与文字/语音面试开考前均弹出**答题时禁止搜题、后台会有提交审查**提示与二次确认；默认无摄像头、无强制全屏，静默采集切屏时长、复制题目、语音文本篡改以及**候选人在笔试填空、文字面试、语音作答框中实际粘贴的原始文本内容**——HR 在后台投递详情页直接审查每次粘贴的**实际文本内容、对应题号、发生环节与时间**（不再仅显示模糊的粘贴次数），辅助精准判定搜题或外部代答风险。

### 4. 定向发送面试邀请邮件与批量上传简历图片识别邀请（`/admin/invitations`）
系统默认开启**受邀邮箱准入制**（`APP_RECRUITMENT_INVITE_ONLY=true`）：
- **批量上传简历图片自动识别邀请**：HR 或管理员在后台 `/admin/invitations` 点击右上角「**批量传图识别邀请**」（或在邀请弹窗中切换至「批量上传简历图片识别」标签页），支持多选拖拽、文件选择或直接 `Ctrl+V` 粘贴多张简历截图/照片（支持 `PNG / JPG / WEBP / GIF / BMP`，单批最多 20 张、单张 ≤ 10MB）；系统并发调用多模态视觉模型自动提取每张图片中的**候选人姓名、电子邮箱地址与所在城市/联系地址**（支持点击缩略图放大对照原图、在线编辑核对姓名或补填缺失邮箱），一键批量发送携带每位候选人真实姓名专属称呼（如 `张三，您好`）的面试邀请邮件。
- **直接填写邮箱投递邀请**：也可直接填写单个或批量多个面试者邮箱（支持换行、逗号、分号分隔），系统将通过已配置的 SMTP / 企业微信邮件通道直接向候选人邮箱投递邀请邮件（模板编码 `CANDIDATE_INVITATION`，附带自动填入受邀邮箱的专属进入链接 `{portalBaseUrl}/careers/login?email=...&redirect=/careers/{jobId}` 与诚信须知），并可选开启「上传简历后免 AI 初筛直通面试」。
- **实时查看是否参加面试**：后台 `/admin/invitations` 实时统计并展示每位受邀候选人的邮件送达状态与**是否参加面试进度**（`已参加 · 已完成面试` / `已参加 · 答题进行中` / `未参加 · 待开考` / `未参加 · 简历初筛中` / `未参加 · 尚未投递简历` / `未参加 · 初筛未通过` / `未参加 · 邀请已过期` / `已撤销邀请`），支持按参面状态一键筛选、重发邀请邮件、撤销邀请与直达投递审查详情。
- **非受邀邮箱全链路拦截**：开启 `APP_RECRUITMENT_INVITE_ONLY=true`（默认开启）时，未收到有效邀请的邮箱无法获取登录验证码，亦无法向未受邀岗位投递/重提简历或进入笔试、文字面试与语音 WebSocket 会话（统一拦截提示：`当前邮箱未收到面试邀请，非受邀邮箱不允许投递简历与参加面试`）。若企业后续需临时开放公开自主投递，可将环境变量设为 `APP_RECRUITMENT_INVITE_ONLY=false`。

---

## 六、日常更新升级与数据备份（零手动 SQL）

本项目使用 **Flyway** 自动管理全部数据库迁移（位于 `app/src/main/resources/db/migration/`，当前已演进至 `V20261127__candidate_track.sql`，涵盖后台权限、投递闭环、双通道邮件、低打扰防作弊与实际粘贴文本 `interview_proctor_events.pasted_text`、在线笔试表、候选人前后端方向偏好 `applications.candidate_track`、自选定位与考前 AI 经验/特长 `applications.ai_experience` & `specialties`、简历修改重提次数以及定向邮箱面试邀请表 `recruitment_invitations`）。**本次批量简历图片识别邀请功能无新增数据库表结构变更，升级版本时无需手动执行任何 SQL 脚本**。

### 标准升级步骤

```bash
cd /path/to/interview-guide

# 1. 【推荐】升级前备份 PostgreSQL 数据库（约数秒完成）
docker compose exec -T postgres pg_dump -U postgres -d interview_guide > "backup_$(date +%Y%m%d_%H%M%S).sql"

# 2. 拉取最新代码
git pull origin master

# 3. 重新构建并平滑滚动重启变更的服务（命名卷中的数据完整保留）
docker compose up -d --build

# 4. 确认启动与迁移成功，并清理旧构建缓存镜像
docker compose ps
docker compose logs --tail=100 app
docker image prune -f
```

> **注意**：升级时切勿执行 `docker compose down -v`（`-v` 参数会删除数据库与文件存储卷）！同时请保持 `.env` 中的 `APP_AI_CONFIG_ENCRYPTION_KEY` 不变。

---

## 七、常见排障速查

| 现象 | 原因定位 | 解决方案 |
| :--- | :--- | :--- |
| 候选人登录或投递提示“当前邮箱未收到面试邀请，非受邀邮箱不允许投递简历与参加面试” | 系统默认启用受邀邮箱准入制（`APP_RECRUITMENT_INVITE_ONLY=true`），该邮箱未被邀请或邀请已过期/已撤销 | HR 在后台 `/admin/invitations` 向该候选人邮箱发送对应岗位的面试邀请（或对已过期记录点击「重发邮件」自动续期） |
| 批量上传简历图片提示“AI 识别失败” | 当前默认 Provider 模型不支持图片输入，且未配置有效的百炼 `AI_BAILIAN_API_KEY` 回退 | 确认 `AI_BAILIAN_API_KEY` 有效（默认 `qwen3.5-flash` 或自动回退 `qwen-vl-plus` 均原生支持识图），或显式设置 `APP_RECRUITMENT_VISION_PROVIDER` 与 `APP_RECRUITMENT_VISION_MODEL` 指向多模态模型 |
| 候选人收不到邀请邮件、验证码或通知邮件 | 邮件通道未启用、企业微信未加可信 IP 或授权码错误 | 进入 `/admin/mail` 查看「发件箱记录」列的详细报错；企业微信报错 `60020` 请将服务器公网出口 IP 加入应用「企业可信 IP」 |
| 开启笔试或面试时报错 `504 Gateway Time-out` | 外层反向代理默认 60s 超时，AI 生成整套题目耗时较长 | 将外层 Nginx/SLB 对 `/api/` 的 `proxy_read_timeout` 与 `proxy_send_timeout` 调至 `300s` |
| 语音面试提示“无法访问麦克风” | 浏览器安全策略限制非安全上下文调用麦克风 | 必须使用 `https://` 域名访问（仅本机调试允许 `http://localhost`） |
| 语音面试提示“连接已断开”且控制台 `/ws/` 返回 `403` | WebSocket Origin 校验未通过或反向代理丢失端口/协议头 | 1) 在 `.env` 的 `CORS_ALLOWED_ORIGINS` 中加入前端完整 Origin（如 `https://careers.yourcompany.com`）并重启 `app`；<br>2) 确认 Nginx `/ws/` 配置了 `Upgrade`、`Connection "upgrade"` 与 `Host $http_host` |
| 重启容器后邮件发送报解密失败（`AEADBadTagException`） | 更换或丢失了 `APP_AI_CONFIG_ENCRYPTION_KEY` | 恢复原来的 `APP_AI_CONFIG_ENCRYPTION_KEY`；若已遗失，在 `/admin/mail` 重新输入一次 SMTP 密码或企业微信 Secret 保存即可覆盖刷新 |

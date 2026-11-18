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
APP_RECRUITMENT_INVITE_EXPIRE_DAYS=7
APP_RECRUITMENT_OTP_LOG_CODE=false

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
3. **`client_max_body_size 50M`**：支持简历与知识库文档上传。

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

### 1. 登录 HR 管理后台与分配账号
- 访问 `https://careers.yourcompany.com/admin/login`，使用 `.env` 中配置的初始管理员账号登录。
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
- **AI 简历初筛**：设置初筛通过分数线（低于阈值自动淘汰并发送拒信，通过自动发放笔试/面试邀请）。
- **默认定位与候选人自选定位（初级 / 中级 / 高级）**：岗位可设置建议定位（默认 `中级`），候选人在投递简历及进入在线笔试 / AI 面试开考前均可自主选择 `初级`、`中级` 或 `高级`，AI 将按候选人自选定位实时生成对应考核深度的笔试题、文字面试题与实时语音追问，HR 可在投递列表与详情页对照查看。
- **AI 在线笔试（选择题 / 填空题）**：
  - 开启后候选人进入考场时将**先完成限时笔试，交卷后无缝进入 AI 面试**（无需人工维护题库，AI 根据岗位 JD、考察点与候选人定位实时出题并打乱选项）。
  - 可自定义**单选题数、多选题数、填空题数**（总题数 1–30 题）、**笔试时长**（5–120 分钟）以及**笔试得分占最终综合分的权重**（0%–100%，默认 30%）。
- **AI 在线面试模式**：支持 `BOTH`（默认，候选人开考时自主选择文字或实时语音作答）、`TEXT`（仅文字）、`VOICE`（仅实时语音）。
- **低打扰防作弊设置**：默认无摄像头、无强制全屏，静默采集切屏时长、粘贴突增、复制题目与语音文本篡改并生成 0–100 风险研判分；可按岗位开关「长时间离开返回轻提示」与「页面极淡候选人水印」。

---

## 六、日常更新升级与数据备份（零手动 SQL）

本项目使用 **Flyway** 自动管理全部数据库迁移（位于 `app/src/main/resources/db/migration/`，当前已演进至 `V20261123__candidate_level.sql`，涵盖后台权限、投递闭环、双通道邮件、低打扰防作弊遥测、在线笔试表与候选人自选定位字段）。**升级版本时无需手动执行任何 SQL 脚本**，后端容器启动时会自动检测并按序执行增量迁移。

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
| 候选人收不到验证码或通知邮件 | 邮件通道未启用、企业微信未加可信 IP 或授权码错误 | 进入 `/admin/mail` 查看「发件箱记录」列的详细报错；企业微信报错 `60020` 请将服务器公网出口 IP 加入应用「企业可信 IP」 |
| 开启笔试或面试时报错 `504 Gateway Time-out` | 外层反向代理默认 60s 超时，AI 生成整套题目耗时较长 | 将外层 Nginx/SLB 对 `/api/` 的 `proxy_read_timeout` 与 `proxy_send_timeout` 调至 `300s` |
| 语音面试提示“无法访问麦克风” | 浏览器安全策略限制非安全上下文调用麦克风 | 必须使用 `https://` 域名访问（仅本机调试允许 `http://localhost`） |
| 语音面试提示“连接已断开”且控制台 `/ws/` 返回 `403` | WebSocket Origin 校验未通过或反向代理丢失端口/协议头 | 1) 在 `.env` 的 `CORS_ALLOWED_ORIGINS` 中加入前端完整 Origin（如 `https://careers.yourcompany.com`）并重启 `app`；<br>2) 确认 Nginx `/ws/` 配置了 `Upgrade`、`Connection "upgrade"` 与 `Host $http_host` |
| 重启容器后邮件发送报解密失败（`AEADBadTagException`） | 更换或丢失了 `APP_AI_CONFIG_ENCRYPTION_KEY` | 恢复原来的 `APP_AI_CONFIG_ENCRYPTION_KEY`；若已遗失，在 `/admin/mail` 重新输入一次 SMTP 密码或企业微信 Secret 保存即可覆盖刷新 |

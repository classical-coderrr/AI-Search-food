<h1 align="center">AI 智能菜谱推荐系统</h1>

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-orange" alt="Java 17">
  <img src="https://img.shields.io/badge/Spring%20Boot-3.x-green" alt="Spring Boot 3.x">
  <img src="https://img.shields.io/badge/Vue-3-brightgreen" alt="Vue 3">
  <img src="https://img.shields.io/badge/Docker-Compose-blue" alt="Docker Compose">
  <img src="https://img.shields.io/badge/license-MIT-blue" alt="MIT License">
</p>

<p align="center">
  <strong>基于多模态大模型的食材识别与个性化菜谱工作台</strong>
</p>

<p align="center">
  <a href="README.md"><strong>中文</strong></a>
  &nbsp;·&nbsp;
  <a href="README.en.md">English</a>
</p>

项目将食材输入、图像识别、AI 菜谱生成、库存管理、采购准备和烹饪反馈串成一个完整闭环，面向家庭用户提供可执行的饮食决策辅助。系统同时提供管理员运营概览、模型配置和日志审计能力。

> 本项目是一个 Java 17 + Spring Boot 3 + Vue 3 的毕业设计项目，支持本地开发、MySQL 持久化和 Docker Compose 部署。

## 功能概览

| 模块 | 已实现能力 |
| --- | --- |
| 智能输入 | 文字输入、上传图片识别食材、拍照识别食材 |
| AI 菜谱 | 个性化推荐、流式生成、功效标签、营养估算、烹饪步骤和视频关键词 |
| 食材准备 | 所需食材、库存匹配、缺少食材分析、采购清单、临期提醒 |
| 用户空间 | 菜谱收藏、搜索历史、推荐反馈、健康档案、饮食偏好 |
| 库存与菜单 | 入库、烹饪消耗、撤销操作、一周菜单、AI 自动安排 21 个餐次 |
| 成品评价 | 上传成品图片，由 AI 评价菜品完成度并保存记录 |
| 用户认证 | 手机号验证码注册/登录、手机号密码登录、密码重置和错误锁定 |
| 管理员端 | 运营数据、热门食材、AI 模型配置、操作日志、异常日志 |

## 产品亮点

- 生成菜谱时采用 Server-Sent Events 流式输出，已返回内容会在原结果页面中逐步展示。
- 生成页面保持“结果页接管输入页”的交互，流式期间不使用遮挡正文的全屏加载层。
- 推荐会综合食材、餐次、饮食偏好、健康档案和用户库存，减少只根据单一食材生成的无效结果。
- 普通用户可以在验证码登录和密码登录之间切换，密码使用 BCrypt 哈希保存。
- 管理员操作和系统异常独立记录，并在展示和日志写入前脱敏敏感信息。

## 技术架构

```text
┌──────────────────────────────────────────────────────────────┐
│                         Vue 3 前端                           │
│ Element Plus · Pinia · Vue Router · Nginx · SSE 流式消费      │
└──────────────────────────────┬───────────────────────────────┘
                               │ /api 反向代理
┌──────────────────────────────▼───────────────────────────────┐
│                     Spring Boot 3 后端                        │
│ 认证 · AI 编排 · 菜谱 · 库存 · 周菜单 · 评价 · 管理员运营       │
└───────────────┬───────────────────────────┬───────────────────┘
                │                           │
        ┌───────▼────────┐          ┌───────▼────────────────┐
        │ MySQL / H2     │          │ 千问兼容 API / 阿里云短信 │
        │ Flyway 迁移     │          │ 文本、视觉、验证码服务     │
        └────────────────┘          └────────────────────────┘
```

## 项目结构

```text
AI-Search-food/
├─ backend/
│  ├─ src/main/java/com/example/food/
│  │  ├─ ai/             # 菜谱生成、流式输出、图像识别和 AI 配置
│  │  ├─ auth/           # 用户/管理员认证、验证码、密码策略
│  │  ├─ admin/          # 管理员概览、操作日志和异常日志
│  │  ├─ recipe/         # 菜谱保存、搜索历史和推荐反馈
│  │  ├─ pantry/         # 用户库存、入库、消耗和准备度
│  │  ├─ weekly/         # 一周菜单和采购状态
│  │  ├─ user/           # 健康档案和饮食偏好
│  │  ├─ review/         # 成品图片评价
│  │  └─ security/       # JWT、角色和 Spring Security 配置
│  ├─ src/main/resources/db/migration/  # Flyway 数据库迁移
│  ├─ src/test/                         # 后端单元、控制器和集成测试
│  ├─ Dockerfile
│  └─ pom.xml
├─ frontend/
│  ├─ src/
│  │  ├─ api/            # 后端 API 封装
│  │  ├─ components/     # 可复用业务组件
│  │  ├─ stores/         # Pinia 状态
│  │  ├─ utils/          # 流式解析、业务计算和测试
│  │  └─ views/          # 首页、登录、用户和管理员页面
│  ├─ nginx/             # 本地和容器 Nginx 配置
│  ├─ scripts/           # Nginx 启停脚本
│  ├─ Dockerfile
│  └─ package.json
├─ docs/                 # 设计文档、阶段计划和本地接入说明
├─ docker-compose.yml
├─ docker-compose.debug.yml
├─ .env.example
├─ README.md
└─ README.en.md
```

## 环境要求

- Java 17 或更高版本
- Maven 3.8 或更高版本
- Node.js 20 或更高版本
- MySQL 8 或更高版本（本地默认可使用 H2）
- Docker Desktop（仅 Docker Compose 部署需要）

## 快速开始

### 启动后端

默认启动使用 H2 临时数据库，适合本地开发和功能演示：

```powershell
mvn "-Dmaven.repo.local=D:\AI-Search-food\.m2" -f backend/pom.xml spring-boot:run
```

后端默认运行在 `http://localhost:7068`。

### 启动前端

正式本地演示使用 Nginx 托管构建产物，并代理 `/api` 请求：

```powershell
cd frontend
npm install
npm start
```

默认访问地址为 `http://localhost:5173`。源码热更新使用 `npm run dev`。停止由本项目启动的 Nginx：

```powershell
npm run stop
```

Windows 用户如未安装 Nginx，可执行 `winget install -e --id nginxinc.nginx`。

## 配置说明

### AI 与本地开发

```env
JWT_SECRET=change-this-secret-change-this-secret-32
SMS_PROVIDER=mock
DASHSCOPE_API_KEY=你的千问 API Key
DASHSCOPE_MODEL=qwen-plus
DASHSCOPE_VISION_MODEL=qwen-vl-plus
```

### Agent 状态与重启恢复

Agent 运行状态、步骤和 checkpoint 默认写入 Redis。Docker Compose 会自动启动并持久化 Redis；本地运行时请先启动 Redis，并设置 `REDIS_HOST`、`REDIS_PORT` 和可选的 `REDIS_PASSWORD`。服务重启后，超过 `AGENT_RECOVERY_STALE_AFTER` 仍未完成的无图片任务会由后台恢复扫描器从最近 checkpoint 继续执行；写操作仍需要用户确认。开发测试可将 `AGENT_STATE_STORE=memory` 且关闭 `AGENT_RECOVERY_ENABLED`。

每次流式响应的 `conversation.ready` 事件会返回 `runId`。如果客户端连接中断，可通过 `GET /api/agent/runs/{runId}` 查询运行状态（`RUNNING`、`RECOVERING`、`WAITING_CONFIRMATION`、`COMPLETED` 或 `FAILED`）。

需要确认的 Agent 写操作会在 `agent_confirmations` 中以幂等键登记。确认执行会先原子抢占为 `PROCESSING`，完成后写入 `CONFIRMED` 和结果消息；重复提交会直接返回已完成结果，不会再次执行。客户端或运维侧可通过 `GET /api/agent/confirmations/{confirmationId}` 查询 `PENDING`、`PROCESSING`、`CONFIRMED`、`UNKNOWN_REVIEW` 等状态。`PROCESSING` 超过 20 分钟会转为 `UNKNOWN_REVIEW`，系统不会自动重试可能已经产生副作用的写操作。

小厨灵客户端会在打开时从服务端恢复最近会话；如果发现未完成的运行，会根据 `runId` 轮询 Redis 状态。服务重启或网络中断后，页面会显示恢复提示，并在运行完成后自动拉取最新消息。

会话恢复接口为 `GET /api/agent/conversations/latest` 和 `GET /api/agent/conversations/{conversationId}/messages`，均按当前登录用户隔离。

`DASHSCOPE_API_KEY` 仅作为兜底配置；管理员端保存的 AI 接入配置优先级更高。生产环境必须替换 `JWT_SECRET`，并使用至少 32 字节的随机值。

### H2 与 MySQL

H2 数据会在应用重启后清空。如需持久化数据，可以复制本地配置模板并设置 `SPRING_PROFILES_ACTIVE=local`：

```powershell
Copy-Item backend/src/main/resources/application-local.example.yml backend/src/main/resources/application-local.yml
```

也可以启用 `mysql` profile：

```env
SPRING_PROFILES_ACTIVE=mysql
MYSQL_URL=jdbc:mysql://localhost:3306/ai_smart_recipe?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true
MYSQL_USERNAME=root
MYSQL_PASSWORD=你的 MySQL 密码
```

Flyway 会在启动时自动执行数据库迁移。不要修改已经执行过的迁移文件，应新增版本迁移。

### 短信验证码

本地默认使用 `mock` 模式，验证码会在接口响应中返回并由前端自动填入，仅用于开发。验证码有效期为 5 分钟，60 秒内不能重复发送，连续输错 5 次后失效。

真实短信使用阿里云 PNVS：

```env
SMS_PROVIDER=aliyun-pnvs
ALIBABA_CLOUD_ACCESS_KEY_ID=你的 AccessKey ID
ALIBABA_CLOUD_ACCESS_KEY_SECRET=你的 AccessKey Secret
ALIYUN_PNVS_SIGN_NAME=你的系统签名
ALIYUN_PNVS_TEMPLATE_CODE=你的模板编号
ALIYUN_PNVS_ENDPOINT=dypnsapi.aliyuncs.com
```

生产部署必须启用 `mysql,prod` profile。生产模式禁用模拟发送器，缺少真实短信配置时会拒绝启动，避免公共接口回显验证码。敏感配置只应放在环境变量或未提交的本地配置中。

## 认证说明

普通用户支持手机号验证码登录和手机号密码登录。新用户注册时需要设置密码；已有历史账号如果尚未设置密码，仍可使用验证码登录，并通过“忘记密码”流程设置密码。

密码规则为 8–64 个字符，且同时包含字母和数字。连续输错 5 次后，密码登录锁定 15 分钟；验证码登录和密码重置不受影响。密码不会保存在浏览器本地存储中。

管理员初始账号：

```text
账号：admin
密码：Admin@123456
```

正式部署后必须修改初始管理员密码。

## 主要 API

### AI 与菜谱

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/ai/recipes/generate` | 一次性生成菜谱 |
| `POST` | `/api/ai/recipes/generate/stream` | SSE 流式生成菜谱 |
| `POST` | `/api/ai/ingredients/recognize` | 上传 JPG、PNG 或 WebP 图片识别食材，最大 5MB |
| `POST` | `/api/ai/finished-dish-reviews` | 上传成品图片并请求 AI 评价 |

### 认证

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| `POST` | `/api/auth/user/register/code` | 获取注册验证码 |
| `POST` | `/api/auth/user/register` | 用户注册并登录 |
| `POST` | `/api/auth/user/code` | 获取登录验证码 |
| `POST` | `/api/auth/user/login` | 验证码登录 |
| `POST` | `/api/auth/user/password-login` | 密码登录 |
| `POST` | `/api/auth/user/password/reset/code` | 获取密码重置验证码 |
| `POST` | `/api/auth/user/password/reset` | 重置用户密码 |
| `POST` | `/api/auth/admin/login` | 管理员登录 |
| `GET` | `/api/auth/me` | 获取当前登录身份 |

### 用户数据与管理员

- `/api/users/me/pantry`：库存、临期提醒、库存准备度、入库、烹饪消耗和操作撤销。
- `/api/users/me/health-profile`：健康档案。
- `/api/users/me/diet-preferences`：饮食偏好。
- `/api/users/me/weekly-menu`：一周菜单、AI 自动生成和采购状态。
- `/api/recipes/saved`：已保存菜谱。
- `/api/search-history/recent`：最近搜索记录。
- `/api/recommendation-feedbacks/{searchLogId}`：推荐反馈和已烹饪标记。
- `/api/admin/dashboard/overview`：运营概览、趋势、输入来源和热门食材。
- `/api/admin/ai-config/text-recipe`：文本菜谱 AI 配置。
- `/api/admin/operation-logs`：管理员操作日志。
- `/api/admin/error-logs`：系统异常日志。
- `/api/stats/hot-ingredients`：热门食材统计。

受保护接口使用 JWT：

```http
Authorization: Bearer <token>
```

完整的本地启动、API 调用、千问和阿里云 PNVS 配置说明见：[本地启动、API 与阿里云短信配置](docs/local-run-api-and-aliyun-sms.md)。

## 测试与构建

后端测试：

```powershell
mvn "-Dmaven.repo.local=D:\AI-Search-food\.m2" -f backend/pom.xml test
```

前端构建：

```powershell
cd frontend
npm run build
```

后端测试覆盖认证、密码策略、错误锁定、验证码、AI 客户端、控制器、权限和主要业务服务；前端业务工具测试与源码放在同目录，并通过生产构建检查 Vue、路由和静态资源。

## Docker Compose 部署

在项目根目录执行：

```powershell
Copy-Item .env.example .env
# 编辑 .env，填写数据库、JWT、千问和阿里云 PNVS 配置
docker compose up -d --build
docker compose ps
```

前端默认入口为 `http://localhost`。端口冲突时，在 `.env` 中设置 `FRONTEND_PORT=8080`，然后访问 `http://localhost:8080`。

常用命令：

```powershell
docker compose logs -f backend
docker compose logs -f frontend
docker compose stop
docker compose start
docker compose down
```

普通 `docker compose down` 会保留 `mysql_data` 和 `review_uploads` 数据卷。只有明确需要清空数据时才使用 `docker compose down -v`。

生产服务器推荐直接拉取 TCR 版本镜像，不在服务器现场构建。完整的腾讯云 TCR 发布、4C4G 资源限制、CDN 缓存/压缩配置及回滚流程见 [腾讯云 TCR 镜像发布与 CDN 部署](docs/tencent-cloud-tcr-cdn-deployment.md)。

## 安全与开发约定

- 不提交 `.env`、API Key、短信密钥、数据库密码或真实用户数据。
- 用户密码只保存为 BCrypt 哈希，密码和验证码不得写入日志。
- 生产环境使用真实短信服务、随机 JWT 密钥和 MySQL，不使用 `mock` 验证码。
- 管理员接口由角色权限保护；用户资源按当前登录用户隔离。
- 数据库结构通过 Flyway 版本迁移管理，不修改已执行的历史迁移。
- 前端页面以中文为主，新增用户可见文案应保持现有产品语气和交互习惯。

## 项目文档

- [本地启动、API 与阿里云短信配置](docs/local-run-api-and-aliyun-sms.md)
- [设计文档与阶段计划](docs/superpowers/)
- [English README](README.en.md)

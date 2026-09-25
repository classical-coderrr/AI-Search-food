<h1 align="center">Little Kitchen Spirit | Multimodal AI Cooking Assistant</h1>

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-orange" alt="Java 17">
  <img src="https://img.shields.io/badge/Spring%20Boot-3.x-green" alt="Spring Boot 3.x">
  <img src="https://img.shields.io/badge/Vue-3-brightgreen" alt="Vue 3">
  <img src="https://img.shields.io/badge/Docker-Compose-blue" alt="Docker Compose">
  <img src="https://img.shields.io/badge/Vite-6-646CFF" alt="Vite 6">
  <img src="https://img.shields.io/badge/MyBatis--Plus-3.5.7-007ACC" alt="MyBatis Plus 3.5.7">
  <img src="https://img.shields.io/badge/MySQL-8.4-4479A1" alt="MySQL 8.4">
  <img src="https://img.shields.io/badge/Redis-7.4-DC382D" alt="Redis 7.4">
  <img src="https://img.shields.io/badge/Flyway-11-CC0200" alt="Flyway 11">
  <img src="https://img.shields.io/badge/Nginx-1.27-009639" alt="Nginx 1.27">
</p>

<p align="center">
  <a href="README.md">中文</a>
  &nbsp;·&nbsp;
  <a href="README.en.md"><strong>English</strong></a>
</p>

Little Kitchen Spirit is a multimodal AI cooking assistant for everyday home cooking. It helps users go from “what is already in the fridge?” to a meal they can cook. Enter ingredients manually, upload a photo, or take one with a camera. After you review the recognized items, the app combines the meal occasion, taste preferences, dietary restrictions, health profile, and current pantry stock to suggest several recipes, with ingredient quantities, step-by-step instructions, missing-item notes, and estimated nutrition.

**Typical flow:** enter or photograph ingredients → review recognition results → set a meal and dietary goal → compare recipes and cooking steps → save a recipe or add it to a weekly menu.

Beyond one-off recommendations, you can have a multi-turn conversation with the kitchen Agent, “Little Kitchen Spirit,” to find or generate recipes, get pantry-aware suggestions, and manage saved recipes and weekly menus. It asks for confirmation before operations that add, change, or consume personal data. Pantry stock, health information, recipes, and menus can also be managed from dedicated areas of the workspace.

This graduation project is built with Java 17, Spring Boot 3, and Vue 3. It includes a local H2 setup and a full Docker Compose deployment. Recipe generation and image recognition require an API key for a DashScope/Qwen-compatible endpoint. See “Quick Start” below for setup instructions.

## Product Preview

![Little Kitchen Spirit workspace](docs/images/readme/main-scene.png)

| Little Kitchen Spirit Agent | Ingredient input and recognition | Generated recipe |
| --- | --- | --- |
| ![Agent chat](docs/images/readme/kitchen-agent.png) | ![Ingredient input](docs/images/readme/ingredient-input.png) | ![Generated recipe](docs/images/readme/recipe-result.png) |

The screenshots show the Chinese-language interface.

## Quick Start

Use the local setup for a first run: it uses an in-memory H2 database and mock SMS, so MySQL and real SMS credentials are not required. Docker Compose runs the full container setup in production mode and requires Aliyun PNVS credentials.

### Run Locally

Requirements: Java 17, Maven 3.8+, and Node.js 20+. Open two terminals in the project root.

Terminal 1: start the backend with H2. Agent state is stored in memory for this local demo.

PowerShell:

```powershell
$env:AGENT_STATE_STORE = 'memory'
$env:AGENT_RECOVERY_ENABLED = 'false'
mvn -f backend/pom.xml spring-boot:run
```

macOS/Linux:

```bash
AGENT_STATE_STORE=memory AGENT_RECOVERY_ENABLED=false mvn -f backend/pom.xml spring-boot:run
```

Terminal 2: start the frontend.

```bash
cd frontend
npm install
npm run dev
```

Open `http://localhost:5173`. The backend listens on `http://localhost:7068`; the Vite development server proxies `/api` requests to it.

The local H2 database is temporary, and mock SMS is for development only. Configure a compatible model API key before using recipe generation, ingredient recognition, or the Agent. Set `DASHSCOPE_API_KEY` or configure the model in the admin workspace; saved admin settings take precedence.

### Run with Docker Compose

Requirements: Docker Desktop (or Docker Engine) and Docker Compose v2. From the project root, create the environment file:

```powershell
Copy-Item .env.example .env
```

On macOS/Linux:

```bash
cp .env.example .env
```

Edit `.env`. Replace the database passwords, set a random `JWT_SECRET` of at least 32 bytes, and provide Aliyun PNVS AccessKey credentials, signature, and template code. Set `DASHSCOPE_API_KEY` to use AI features.

Start the services:

```bash
docker compose up -d --build
docker compose ps
```

Open `http://localhost`. If port 80 is unavailable, set `FRONTEND_PORT=8080` in `.env` and visit `http://localhost:8080`.

> Compose enables the production profile and requires real PNVS SMS configuration. The backend will not start without valid PNVS settings. Use the local setup above to explore the interface with mock SMS.

## Core Features

- **Ingredient recognition:** enter ingredients, upload an image, or use the camera.
- **Personalized recipes:** recommendations consider ingredients, meal type, diet preferences, health profile, and pantry stock; results include cooking steps, estimated nutrition, and missing ingredients.
- **Little Kitchen Spirit Agent:** chat to find or generate recipes and manage saved recipes, pantry stock, and weekly menus. Actions that change user data require confirmation.
- **Cooking organization:** manage pantry items, shopping lists, weekly menus, and cooking feedback.

## Technology

| Area | Stack |
| --- | --- |
| Frontend | Vue 3, Vite 6, Element Plus, Pinia, Vue Router, Axios, ECharts, PixiJS |
| Backend | Java 17, Spring Boot 3.3, Spring Security, JWT, MyBatis-Plus 3.5.7, Maven |
| Data | H2, MySQL 8.4, Redis 7.4, Flyway 11 |
| AI and messaging | DashScope/Qwen-compatible API, SSE streaming, Aliyun PNVS |
| Deployment and testing | Docker Compose, Nginx, JUnit 5, Mockito, Spring Boot Test |

## Documentation

- [Local setup, API, and Aliyun SMS](docs/local-run-api-and-aliyun-sms.md)
- [Tencent Cloud TCR and CDN deployment](docs/tencent-cloud-tcr-cdn-deployment.md)
- [中文 README](README.md)

## Security

- Never commit `.env`, API keys, SMS credentials, database passwords, or real user data.
- For production, use a unique random JWT secret and change the initial administrator password.

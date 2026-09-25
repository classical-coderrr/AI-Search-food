# Little Kitchen Spirit | Multimodal AI Cooking Assistant

<p align="center">
  <img src="https://img.shields.io/badge/Java-17-orange" alt="Java 17">
  <img src="https://img.shields.io/badge/Spring%20Boot-3.x-green" alt="Spring Boot 3.x">
  <img src="https://img.shields.io/badge/Vue-3-brightgreen" alt="Vue 3">
  <img src="https://img.shields.io/badge/Docker-Compose-blue" alt="Docker Compose">
</p>

<p align="center">
  <a href="README.md">中文</a>
  &nbsp;·&nbsp;
  <a href="README.en.md"><strong>English</strong></a>
</p>

A multimodal AI cooking assistant for home kitchens. Enter or photograph ingredients to get recipes tailored to dietary preferences, or chat with Little Kitchen Spirit to manage recipes, pantry stock, and weekly menus.

A graduation project built with Java 17, Spring Boot 3, and Vue 3. It supports local development and Docker Compose deployment.

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
| Frontend | Vue 3, Vite, Element Plus, Pinia |
| Backend | Java 17, Spring Boot 3, Maven |
| Data | H2 locally; MySQL 8, Flyway, and Redis with Docker Compose |
| AI and SMS | DashScope/Qwen-compatible API; mock SMS locally or Aliyun PNVS |

## Documentation

- [Local setup, API, and Aliyun SMS](docs/local-run-api-and-aliyun-sms.md)
- [Tencent Cloud TCR and CDN deployment](docs/tencent-cloud-tcr-cdn-deployment.md)
- [中文 README](README.md)

## Security

- Never commit `.env`, API keys, SMS credentials, database passwords, or real user data.
- For production, use a unique random JWT secret and change the initial administrator password.

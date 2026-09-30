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
  <img src="https://img.shields.io/badge/Qdrant-Vector%20DB-DC244C" alt="Qdrant">
  <img src="https://img.shields.io/badge/Flyway-11-CC0200" alt="Flyway 11">
  <img src="https://img.shields.io/badge/Nginx-1.27-009639" alt="Nginx 1.27">
</p>

<p align="center">
  <a href="README.md">中文</a>
  &nbsp;·&nbsp;
  <a href="README.en.md"><strong>English</strong></a>
</p>

Little Kitchen Spirit is a multimodal AI cooking assistant for everyday home kitchens. Enter ingredients, upload a photo, or use a camera to identify what is on hand and get recipes tailored to dietary preferences and pantry stock. The Little Kitchen Spirit Agent can also chat with you and help manage dietary goals, recipes, pantry items, and weekly menus over time.

The project connects ingredient recognition, recipe recommendations, cooking feedback, and user-controlled long-term dietary memories. Nutrition values are AI estimates for general reference only; they are not medical diagnoses or professional nutrition advice.

Built with Java 17, Spring Boot 3, and Vue 3, the project offers an H2 local setup and a Docker Compose deployment. Recipe generation, ingredient recognition, and the Agent require an API key for a compatible model endpoint.

## Quick Start

For a first run, use the local setup: it needs neither MySQL nor real SMS credentials, and uses H2 with mock SMS. Docker Compose starts the full container environment but requires valid Aliyun PNVS SMS credentials.

### Run Locally

Requirements: Java 17, Maven 3.8+, and Node.js 20+. Open two terminals in the project root.

Terminal 1: start the backend. To use AI features, set a model API key before startup or configure a model in the admin workspace.

Windows PowerShell:

    $env:AGENT_STATE_STORE = 'memory'
    $env:AGENT_RECOVERY_ENABLED = 'false'
    $env:DASHSCOPE_API_KEY = 'your-api-key'
    mvn -f backend/pom.xml spring-boot:run

macOS/Linux:

    export AGENT_STATE_STORE=memory
    export AGENT_RECOVERY_ENABLED=false
    export DASHSCOPE_API_KEY=your-api-key
    mvn -f backend/pom.xml spring-boot:run

Terminal 2: start the frontend.

    cd frontend
    npm install
    npm run dev

Open http://localhost:5173. The frontend dev server proxies /api requests to the backend at http://localhost:7068.

The local setup uses an in-memory H2 database, so accounts, recipes, pantry items, and long-term memories are cleared when the backend restarts. Agent conversation state is also stored in memory. Mock SMS is for development only and needs no real SMS credentials. DASHSCOPE_API_KEY is optional if you are not using AI features.

### Run with Docker Compose

Requirements: Docker Desktop (or Docker Engine) and Docker Compose v2. Create an environment file in the project root:

Windows PowerShell:

    Copy-Item .env.example .env

macOS/Linux:

    cp .env.example .env

Edit .env. Replace the database passwords, set a random JWT_SECRET of at least 32 bytes, and provide the Aliyun PNVS AccessKey, SMS signature, and template code. To use AI features, set DASHSCOPE_API_KEY or configure a model in the admin workspace.

Start the services:

    docker compose up -d --build
    docker compose ps

Open http://localhost. Compose starts the frontend, backend, MySQL, Redis, and Qdrant. If port 80 is unavailable, set FRONTEND_PORT=8080 in .env and visit http://localhost:8080.

Compose uses the production profile and requires real PNVS SMS settings; the backend will not start with invalid PNVS configuration. Stop the services while keeping their data volumes:

    docker compose down

## Core Features

- **Multimodal ingredient recognition:** enter ingredients, upload an image, or use a camera; review detected items before using them in recommendations.
- **Personalized recipes:** recommendations consider ingredients, meal type, taste, dietary restrictions, health profile, and pantry stock. Results include quantities, step-by-step instructions, missing ingredients, and estimated nutrition.
- **Kitchen Agent:** chat to find or generate recipes and manage saved recipes, pantry stock, and weekly menus. The Agent requests confirmation before actions that add, change, or consume user data.
- **Kitchen and diet management:** maintain a dietary profile, pantry inventory, shopping list, and weekly menu, and record cooking feedback.
- **Long-term dietary memory:** the Agent can remember preferences that you state or confirm and refer to them in relevant conversations and recommendations.

### How Long-Term Memory Works

- **Bounded sources:** memories can include explicitly stated ingredient preferences, dietary goals, and cooking preferences, plus eligible actions such as saved recipes, recipe feedback, and finished-dish reviews. A single search or action does not directly become a long-term preference.
- **Conservative behavior patterns:** ingredient preferences are inferred only from recent, repeated positive behavior supported by multiple distinct recipes. The current rule requires at least three supporting events across at least two different recipes within 90 days.
- **You decide about inferred goals:** when repeated behavior suggests a long-term dietary goal, the Agent presents the supporting records and lets you choose “Remember this long term,” “Don’t remember,” or “Use this time only.” An unconfirmed inference is not stored as a long-term dietary preference.
- **Relevant retrieval:** the Agent retrieves related memories when a question concerns your tastes, dietary goals, or recipe choices, instead of adding your entire history to every response.
- **User controls:** in Account Center → Memory & Personalization, you can disable memory, review and edit individual items, or delete memories. Disabling it pauses new behavior learning and memory retrieval while keeping existing memories. Clearing memories does not delete recipes, saved recipes, or pantry inventory.
- **Feedback:** you can rate whether a personal memory helped with an answer and report whether personalization was useful.

## Product Preview

![Little Kitchen Spirit workspace](docs/images/readme/main-scene.png)

| Little Kitchen Spirit Agent | Ingredient input and recognition | Generated recipe |
| --- | --- | --- |
| ![Agent chat](docs/images/readme/kitchen-agent.png) | ![Ingredient input](docs/images/readme/ingredient-input.png) | ![Generated recipe](docs/images/readme/recipe-result.png) |

The screenshots show the Chinese-language interface.

## Technology

| Area | Stack |
| --- | --- |
| Frontend | Vue 3, Vite 6, Element Plus, Pinia, Vue Router, Axios, ECharts, PixiJS |
| Backend | Java 17, Spring Boot 3.3, Spring Security, JWT, MyBatis-Plus 3.5.7, Maven |
| Data and retrieval | H2, MySQL 8.4, Redis 7.4, Qdrant vector search, Flyway 11 |
| AI and messaging | DashScope/Qwen-compatible model API, text embeddings, SSE streaming, Aliyun PNVS |
| Deployment | Docker Compose, Nginx |

## Documentation

- [Local setup, API, and Aliyun SMS](docs/local-run-api-and-aliyun-sms.md)
- [Tencent Cloud TCR and CDN deployment](docs/tencent-cloud-tcr-cdn-deployment.md)
- [中文 README](README.md)

## Security

- Never commit .env, API keys, SMS credentials, database passwords, or real user data.
- For production, use a unique random JWT secret and change the initial administrator password.

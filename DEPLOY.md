# Deploy to Render + Neon Postgres

A free production deployment in three stages: provision the database, push the repo, configure the service.

## 1. Provision Postgres on Neon

1. Sign up at https://neon.tech (free tier, no card required, doesn't expire).
2. Create a project. The default database (e.g. `neondb`) is fine.
3. From the dashboard, copy the **connection string** for the **direct** connection (the one that looks like `postgresql://USER:PASSWORD@HOST/DB?sslmode=require`).
4. Note the four pieces: full URL, user, password, host. We'll feed them to Render as env vars.

## 2. Push this repo to GitHub

```bash
cd /Users/beratbaran/AndroidStudioProjects/ToDoBackend
git remote add origin git@github.com:YOUR_USER/todo-backend.git
git push -u origin main
```

## 3. Create the Render Web Service

1. Sign up at https://render.com.
2. **New → Web Service** → connect your GitHub → pick `todo-backend`.
3. Settings:
   - **Runtime**: Docker (Render auto-detects the Dockerfile in repo root)
   - **Region**: closest to you / your users
   - **Instance type**: `Free`
   - **Branch**: `main`
4. Add **Environment Variables** (Render dashboard → Environment):

| Key | Value |
|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://HOST/DB?sslmode=require` (rewrite from the Neon URL — replace `postgresql://USER:PASSWORD@` with `jdbc:postgresql://`, move user/password to the next two vars) |
| `SPRING_DATASOURCE_USERNAME` | from Neon |
| `SPRING_DATASOURCE_PASSWORD` | from Neon |
| `JWT_SECRET` | a random 64+ char string (use `openssl rand -base64 64`) |
| `APP_OAUTH_GOOGLE_CLIENT_ID` | from Google Cloud Console → OAuth 2.0 Client (Web) — leave blank to disable |
| `APP_OAUTH_FACEBOOK_APP_ID` | from Facebook Developers → My Apps — leave blank to disable |
| `APP_OAUTH_FACEBOOK_APP_SECRET` | from Facebook Developers → My Apps |
| `APP_FIREBASE_SERVICE_ACCOUNT_PATH` | `/etc/secrets/firebase.json` if you mount the secret file (next step), otherwise leave blank |

5. Optional: **Mount Firebase service account** as a Secret File:
   - Render dashboard → Secret Files → `firebase.json` → paste the JSON downloaded from Firebase Console → Project Settings → Service accounts → Generate new private key.
   - Mount path: `/etc/secrets/firebase.json` (matches the env var above).

6. Click **Deploy**. First build takes ~5 min (downloading Gradle, building the fat jar). Subsequent deploys are faster (Docker layer cache).

## 4. Smoke-test the deployed instance

```bash
curl https://YOUR-SERVICE.onrender.com/actuator/health
# {"status":"UP"}

# Open in browser
open https://YOUR-SERVICE.onrender.com/swagger-ui/index.html
```

## 5. Switch the Android release build at the new URL

In `app/build.gradle.kts` of the Android repo, update the release `BASE_URL`:

```kotlin
release {
    isMinifyEnabled = false
    buildConfigField("String", "BASE_URL", "\"https://YOUR-SERVICE.onrender.com/\"")
    proguardFiles(...)
}
```

Build a release APK (`./gradlew assembleRelease`), sideload, register/login → confirms the Android app talks to the deployed backend over HTTPS.

## Notes

- Render's free tier spins the service down after 15 min of inactivity; first request after wake takes ~30s. Fine for dev/demo, not for production traffic.
- Neon's free tier has a 0.5GB storage limit and pauses idle databases after a while (auto-resumes on connect). Both are fine for a small group app.
- Database migrations: we use `spring.jpa.hibernate.ddl-auto=update` for now (Hibernate auto-syncs schema). For real production, switch to Flyway or Liquibase before you have users.

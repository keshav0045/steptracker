# Stepwise

Stepwise includes a browser-based GPS workout tracker and a Java Swing step dashboard. The browser tracker runs at `http://localhost:8080` alongside the API and uses the device's real browser geolocation.

The backend is organized as two independently runnable Java processes:

- **StepTrackerService** handles HTTP routes, validation, and JSON responses.
- **StepTrackerRepository** owns PostgreSQL connections, schema initialization, migrations, and queries.
- **StepTrackerApp** is the Swing desktop client. It reads and updates data through the API.

The service creates its tables on startup. The app and service communicate over HTTP, so they can be started and restarted independently.

Sign-up and sign-in use PostgreSQL accounts, PBKDF2-HMAC-SHA256 password hashes with unique salts, and seven-day server-side sessions stored as hashed tokens. Session cookies are HttpOnly and SameSite protected. API step totals and goals are scoped to the signed-in account. New accounts require passwords of at least 12 characters. Existing pre-account step rows remain archived as ownerless records and are not shown to accounts.

## Run

Requires Java 17 or newer, Maven, and PostgreSQL. Open two terminals in the project directory.

Create the database once (the script uses the current PostgreSQL user by default):

```sh
./scripts/setup-db.sh
```

The service defaults to `jdbc:postgresql://localhost:5432/steptracker` and the current operating-system username. Configure `DATABASE_URL`, `DATABASE_USER`, and `DATABASE_PASSWORD` if your PostgreSQL setup uses different connection details.

Build the project and resolve the PostgreSQL JDBC driver:

```sh
mvn package dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
```

Terminal 1, start the service:

```sh
./scripts/run-api.sh
```

Terminal 2, start the desktop client:

```sh
java -cp "target/classes:$(cat target/classpath.txt)" StepTrackerApp
```

The API creates `app_users`, `auth_sessions`, `daily_steps`, `tracker_settings`, and `schema_migrations` in the configured database when it starts. Check the service terminal for a successful startup message, then inspect the database with `psql -d steptracker -c '\dt'`.

The API and browser tracker listen on port `8080` by default. Open `http://localhost:8080` in a browser after starting the service. The run script binds only to `127.0.0.1` and disables Secure cookies for local HTTP. If you change the bind host, Secure cookies default back on; do not set `SESSION_COOKIE_SECURE=false` on a public server. The Java server does not terminate TLS; production deployments must keep it on loopback behind an HTTPS reverse proxy. Mobile sign-in requires an HTTPS API address. The Android app asks for that address on its sign-in screen; configure the server's `ANDROID_APP_ORIGIN` to match the Android WebView origin (default `https://appassets.androidplatform.net`).

GPS permission works on `localhost` and secure HTTPS origins. Leaflet loads from its public CDN, and the map requests the standard tiles directly from OpenStreetMap; an internet connection is needed. The map displays OpenStreetMap contributor attribution and follows the [OSM tile usage policy](https://operations.osmfoundation.org/policies/tiles/). Workout history is stored locally in the browser/app and partitioned by account. Set `STEPTRACKER_PORT` to change the service port, and set `STEPTRACKER_API` (for example, `http://localhost:9090`) to point the desktop client at another service address.

The desktop client refreshes its dashboard from the API every 30 seconds. To automatically rebuild and restart the UI when Java source files change, build the project and run the development launcher from the project root:

```sh
mvn package dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
java --add-modules jdk.httpserver -cp "target/classes:$(cat target/classpath.txt)" StepTrackerDevLauncher
```

Keep the API running in its own terminal. The development launcher watches `src/main/java`, recompiles after edits, and restarts the desktop window when compilation succeeds.

## Android app

The `android/` directory contains a native Android WebView app that packages the same tracker files from `src/main/resources/web` and requests Android location permission when GPS tracking starts. Open `android/` in Android Studio, install Android SDK 36 if prompted, then use **Build > Build APK(s)**. Android Studio needs internet access to resolve Gradle and AndroidX dependencies. The debug APK is written under `android/app/build/outputs/apk/debug/`. Map tiles still load from OpenStreetMap over the internet; workout history remains local to the app on that device.

## HTTP API

| Method | Route | Purpose |
| --- | --- | --- |
| `GET` | `/health` | Service liveness check |
| `POST` | `/api/auth/signup` | Create an account with JSON body `{"email":"you@example.com","password":"12+ characters"}` |
| `POST` | `/api/auth/login` | Start a session |
| `POST` | `/api/auth/logout` | Revoke the current session |
| `GET` | `/api/auth/me` | Get the signed-in account |
| `GET` | `/api/summary` | Today's personal steps, goal, streak, and seven-day history (requires sign-in) |
| `POST` | `/api/steps` | Add personal steps with JSON body `{"steps":1200}`; optionally include an activity (requires sign-in) |
| `PUT` | `/api/goal` | Change the personal goal with JSON body `{"goal":8000}` (requires sign-in) |

Write requests require `Content-Type: application/json`. Step logging returns `201 Created`; goal updates and reads return `200 OK`. Validation errors return a consistent JSON body such as `{"error":{"code":"invalid_request","message":"..."}}`.

Try it with curl:

```sh
curl http://localhost:8080/health
curl -c /tmp/stepwise-cookies.txt -X POST http://localhost:8080/api/auth/signup \
  -H 'Content-Type: application/json' \
  -d '{"email":"you@example.com","password":"use-a-long-unique-password"}'
curl -b /tmp/stepwise-cookies.txt http://localhost:8080/api/summary
curl -b /tmp/stepwise-cookies.txt -X POST http://localhost:8080/api/steps \
  -H 'Content-Type: application/json' -H 'Origin: http://localhost:8080' \
  -d '{"steps":1200,"activity":"walking"}'
```
# steptracker

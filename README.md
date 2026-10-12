# Stepwise — GPS Workout & Step Tracker

Stepwise is a personal movement-tracking project with a GPS-powered workout experience, account-based daily step tracking, and clients for the browser, Android, and desktop. The outdoor tracker uses the device's real location, draws the route on an interactive OpenStreetMap, and calculates distance as the workout happens.

## What it does

- Tracks outdoor walking, running, and cycling with live GPS updates.
- Shows distance, target progress, elapsed time, pace, the current location, and the route.
- Lets users pause and resume a workout, change distance units and map behavior, and review workout summaries.
- Saves workout history locally on the device, separated by signed-in account.
- Provides sign-up, sign-in, and sign-out, plus a daily steps dashboard with goals and a seven-day history.
- Runs as a responsive browser app, an Android WebView app, and a Java Swing desktop dashboard.

## What makes it stand out

- **Real GPS tracking:** Location comes from the browser or Android device after permission is granted. The map starts in a neutral world view; it does not invent a sample location or route.
- **A route-aware workout:** GPS points form a live map polyline, and distance is calculated between accepted locations with the Haversine formula. The map can follow the user while tracking.
- **Shared web experience on Android:** The Android app loads the same web assets as the browser app, so the tracker UI and workout logic have one source to maintain. Native Android permission handling connects device location to the web tracker.
- **A small, understandable Java backend:** HTTP handling and PostgreSQL persistence are separated into service and repository classes. The browser, Android, and desktop clients communicate with the API rather than reaching into the database.
- **Account-focused security:** Passwords are stored as salted PBKDF2-HMAC-SHA256 hashes; session tokens are stored as hashes and sent in HttpOnly, SameSite cookies. Auth endpoints are rate-limited, and personal step totals and goals are scoped to the signed-in account.
- **Practical deployment:** The API is packaged with Docker and can run behind an HTTPS hosting platform or reverse proxy. PostgreSQL connection details are supplied through environment variables, not committed credentials.

## Technology

| Area | Technology |
| --- | --- |
| API and desktop client | Java 17, JDK `HttpServer`, Swing |
| Database | PostgreSQL, JDBC |
| Browser tracker | HTML, CSS, JavaScript ES modules, browser Geolocation API |
| Interactive maps | Leaflet and OpenStreetMap tiles |
| Android | Android application with WebView, AndroidX WebKit, native location permissions |
| Packaging | Maven and Docker |

## Architecture

```text
 Browser ─────────┐
 Android WebView ─┼── HTTP + session cookie ──> StepTrackerService ──> StepTrackerRepository ──> PostgreSQL
 Swing desktop ───┘
                         │
                         └── serves the browser tracker assets
```

The backend is a single Java API service organized into separate HTTP/service and persistence/repository layers. The Android app reuses the browser tracker assets from `src/main/resources/web`; workout history is stored locally in browser/app storage, while account data and daily steps are stored in PostgreSQL.

Key code areas:

- `src/main/java/StepTrackerService.java` — HTTP routes, request validation, authentication, and JSON responses.
- `src/main/java/StepTrackerRepository.java` — PostgreSQL access, schema creation/migrations, and account-scoped queries.
- `src/main/java/PasswordHasher.java` — salted password hashing.
- `src/main/java/TrackerApiClient.java` — API client used by the Swing application.
- `src/main/resources/web/` — browser tracker UI and focused modules for GPS, map, distance, timer, and workout history.
- `android/` — Android WebView shell and native location permission bridge.

## Run locally

### Requirements

- Java 17 or newer
- Maven
- PostgreSQL
- An internet connection for Leaflet and OpenStreetMap map assets/tiles

Create the local database (the script uses the current PostgreSQL user by default):

```sh
./scripts/setup-db.sh
```

If your local PostgreSQL configuration needs credentials or a different URL, export these variables before starting the API:

```sh
export DATABASE_URL=jdbc:postgresql://localhost:5432/steptracker
export DATABASE_USER=your_postgres_user
export DATABASE_PASSWORD=your_postgres_password
```

Build and start the API:

```sh
mvn package dependency:build-classpath -Dmdep.outputFile=target/classpath.txt
./scripts/run-api.sh
```

Open [http://localhost:8080](http://localhost:8080). The service creates its tables and applies its schema migration on startup. For local HTTP, `run-api.sh` binds to loopback and disables Secure cookies; public deployments should use HTTPS and keep Secure cookies enabled.

Optional demo rows for the legacy, unauthenticated daily-step schema can be loaded with:

```sh
psql -d steptracker -f scripts/seed-demo.sql
```

### Desktop dashboard

With the API running, open another terminal and launch the Swing client:

```sh
java -cp "target/classes:$(cat target/classpath.txt)" StepTrackerApp
```

To rebuild and restart the desktop window as Java files change, use the development launcher:

```sh
java --add-modules jdk.httpserver -cp "target/classes:$(cat target/classpath.txt)" StepTrackerDevLauncher
```

### Android

Open the `android/` directory in Android Studio, allow Gradle to sync, and build/install the app. The Android shell packages and loads the shared tracker from `src/main/resources/web`. It requests Android location permission when tracking starts. Enter the deployed **HTTPS** API URL on the sign-in screen to connect it to your backend.

## Docker and hosted deployment

The included `Dockerfile` builds and runs the Java API. Configure your hosting service to build from that Dockerfile and provide:

- `DATABASE_URL` — PostgreSQL connection URL. The service accepts PostgreSQL URLs and JDBC PostgreSQL URLs.
- `DATABASE_USER` and `DATABASE_PASSWORD` — database credentials.
- `SESSION_COOKIE_SECURE=true` — keep session cookies secure on public HTTPS deployments.
- `STEPTRACKER_BIND_HOST=0.0.0.0` — listen on the container interface.
- `PORT` — supplied by many hosting platforms; otherwise the service defaults to `8080`.

The service supports `STEPTRACKER_PORT` as an explicit port override. If using the Android app, set `ANDROID_APP_ORIGIN` to its WebView origin (default: `https://appassets.androidplatform.net`). Do not commit database credentials or disable Secure cookies on a public deployment. The Java service does not terminate TLS itself; use the hosting platform's HTTPS or an HTTPS reverse proxy.

## HTTP API

| Method | Route | Purpose |
| --- | --- | --- |
| `GET` | `/health` | Liveness check |
| `POST` | `/api/auth/signup` | Create an account |
| `POST` | `/api/auth/login` | Sign in and create a session |
| `POST` | `/api/auth/logout` | Revoke the current session |
| `GET` | `/api/auth/me` | Get the current account |
| `GET` | `/api/summary` | Today's steps, goal, streak, and seven-day history (signed in) |
| `POST` | `/api/steps` | Add steps (signed in) |
| `PUT` | `/api/goal` | Update the daily step goal (signed in) |

JSON write requests use `Content-Type: application/json`. New account passwords must be at least 12 characters. For example, with the API running locally:

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

## Notes

- Geolocation is available on `localhost` and secure HTTPS origins, subject to browser/device permission and GPS availability.
- Leaflet is loaded from its public CDN. Map tiles are requested from OpenStreetMap and require internet access; OpenStreetMap attribution is shown in the map. See the [OpenStreetMap tile usage policy](https://operations.osmfoundation.org/policies/tiles/).
- Workout history and route data are local to the browser or device; clearing browser/app storage removes that local history.

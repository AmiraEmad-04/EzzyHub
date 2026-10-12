# EzzyHub Auth Service

Handles account creation and login **by email** for the EzzyHub platform.
Issues a JWT on register/login that the frontend stores and sends back on
future requests.

## Where it fits

Same shared MySQL database as `requirement-analyzer` (`ezzyhub_db`) — this
service just adds one new table, `users`. No new database server to stand
up; if `requirement-analyzer` can already reach MySQL, this service can too.

Runs on port **8087**, next in line after `report-service` (8086).

Reachable directly at `http://localhost:8087/api/v1/auth/**`, and through the
gateway at `http://localhost:8085/api/auth/**` once the route is added (see
`gateway-changes/` in the delivered zip for the exact diff).

## Endpoints

| Method | Path                    | Body                                   | Notes                                   |
|--------|-------------------------|-----------------------------------------|------------------------------------------|
| POST   | `/api/v1/auth/register` | `{ fullName, email, password }`         | 201 + JWT. Account is active immediately — no email confirmation step. |
| POST   | `/api/v1/auth/login`    | `{ email, password }`                   | 200 + JWT                                |
| GET    | `/api/v1/auth/me`       | — (`Authorization: Bearer <token>`)     | Returns the logged-in user, or 401       |
| GET    | `/api/v1/auth/health`   | —                                        | `{ status, module, version }`            |

`AuthResponse` shape:
```json
{
  "token": "eyJ...",
  "tokenType": "Bearer",
  "expiresIn": 604800,
  "user": { "id": 1, "fullName": "Buzz Bee", "email": "buzz@ezzyhub.com", "role": "USER", "createdAt": "2026-07-02T10:00:00" }
}
```

## Running it

Same MySQL instance the other services already use:

```
mvn clean package -DskipTests
java -jar target/auth-service-1.0.0.jar
```

Or with Docker (build the jar first, same two-step pattern as report-service):

```
mvn clean package -DskipTests
docker build -t ezzyhub-auth-service .
docker run -p 8087:8087 --network host ezzyhub-auth-service
```

## What's intentionally not here yet

- **Email verification.** None of the existing services have SMTP configured
  (no `spring-boot-starter-mail` anywhere in the codebase), so this mirrors
  that — signup activates the account immediately. Straightforward to add
  later if wanted.
- **Other services verifying the JWT.** Right now, only this service checks
  tokens (on `/me`). To have code-analyzer, matching-service, etc. require a
  logged-in user, they'd each need to validate the same JWT — that's a
  separate, larger change across those services and wasn't part of this
  step.
- **Password reset.** The frontend has a "Forgot password?" link with no
  handler yet; wiring that up needs email sending too.

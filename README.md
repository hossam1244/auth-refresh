# auth-refresh

**Synchronized token refresh for [OkHttp](https://square.github.io/okhttp/).**

A tiny interceptor that attaches your Bearer token and heals 401s: concurrent
failures trigger **one lock-guarded refresh**, each failed request is replayed
**exactly once** with the fresh token, and a dead refresh tears the session down
through a single callback.

Pure JVM — usable from Android apps, Kotlin services, or anywhere OkHttp runs.

[![CI](https://github.com/hossam1244/auth-refresh/actions/workflows/ci.yml/badge.svg)](https://github.com/hossam1244/auth-refresh/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)

## Why

Rotating refresh tokens (the right way to run JWT) can only be used **once**.
When a screen fires five requests and the access token expires, all five come
back 401 — and five naive refresh attempts will burn the rotating token five
times, logging the user out for no reason.

| Guarantee | How |
|---|---|
| **One refresh** | All 401s wait on a `ReentrantLock`; the first thread refreshes, the rest see the newer token and just replay |
| **Replay once** | Each failed request is retried exactly once (tagged); a second 401 surfaces instead of looping |
| **Session teardown** | A failed refresh clears storage and fires one callback for app-level logout |
| **Auth paths excluded** | Login/register/refresh endpoints never trigger refreshes |
| **Storage-agnostic** | A 4-method `TokenStore` interface — wire it to EncryptedSharedPreferences, DataStore, whatever |
| **No recursion** | The refresh POST runs on a bare client without the interceptor |

Extracted from the author's banking clients
([novabank-android](https://github.com/hossam1244/novabank-android) ships the
same contract).

## Install

Via [JitPack](https://jitpack.io/#hossam1244/auth-refresh):

```kotlin
// settings.gradle.kts
maven { url = uri("https://jitpack.io") }

// build.gradle.kts
dependencies {
    implementation("com.github.hossam1244.auth-refresh:library:0.1.0")
}
```

## Usage

```kotlin
val tokenStore = object : TokenStore {
    // back these with EncryptedSharedPreferences / DataStore / your DB
    override fun accessToken(): String? = securePrefs.getString("access", null)
    override fun refreshToken(): String? = securePrefs.getString("refresh", null)
    override fun save(access: String, refresh: String?) { /* persist */ }
    override fun clear() { /* wipe */ }
}

val interceptor = AuthInterceptor(
    tokenStore = tokenStore,
    refreshUrl = "https://api.example.com/auth/token/refresh/",
    authPaths = setOf("/auth/token", "/auth/register"),
    onSessionExpired = { /* exactly once — route to login */ },
)

val client = OkHttpClient.Builder()
    .addInterceptor(interceptor)
    .build()
```

The refresh endpoint is expected to accept `{"refresh": "..."}` and answer
`{"access": "...", "refresh": "..."}` (rotation optional — a missing
`refresh` keeps the old one).

## Testing

Five MockWebServer tests cover the whole contract: bearer attachment,
401 → refresh → replay with the new token, refresh-failure teardown +
callback, auth-path exclusion, and no-retry-loops.

```bash
./gradlew :library:test
```

CI also runs `publishToMavenLocal` and verifies the produced artifacts —
the maven-publish configuration (POM, sources jar) is what Maven Central
publishing will use.

## License

[MIT](LICENSE)

![Mehomo — Material 3 Expressive for your agents](docs/assets/mehomo-overview.svg)

# Mehomo

**A native Android client for Memoh, designed around Material 3 Expressive.**

[简体中文](README.zh-CN.md) · [Memoh](https://github.com/felinics/Memoh) · [Icon assets](design/icon/README.md)

Mehomo brings conversations and an agent's cloud workspace into one Android app. It connects to **official Memoh Cloud** and **self-hosted Memoh**. This is an independent, unofficial client.

## Material 3 Expressive, throughout

The interface uses native **Jetpack Compose Material 3 Expressive** components, shared shapes, motion and color roles, rather than a web interface wrapped in an Android shell.

- **Expressive controls:** grouped cards and list items, button groups, tonal actions and bounded floating menus.
- **Clear hierarchy:** large accent-colored titles collapse into the top bar as you scroll; coordinated loading placeholders soften initial loading.
- **A comfortable composer:** compact reply actions, shared input surfaces, keyboard inset handling and model/provider/reasoning selection.
- **Your layout:** choose and reorder floating navigation destinations; light/dark appearance and selectable color accents.
- **Adaptive artwork:** a purple twin-tail avatar, round adaptive launcher resources and a separate monochrome layer for themed icons.

The app pins `androidx.compose.material3:material3:1.5.0-alpha29`. These expressive APIs are still evolving; the dependency version is intentional.

## What you can do

| Area | Features |
|---|---|
| Conversations | Session history, streaming replies, reasoning/tool blocks, provider-grouped models and reasoning levels |
| Markdown | Tables, code blocks, links, task lists, quotes and strikethrough |
| Reply actions | Copy, regenerate, fork and message timestamps |
| Agent controls | Approval/question UI, attachments, follow-up/steer queues and runtime controls |
| Workspace | File management, port previews, terminal and remote desktop |
| Bot management | Memory, schedules and execution logs, token usage, apps, skills and MCP management |

**Validation:** 162 unit tests cover protocol, reducer and UI helpers. Selected end-to-end checks have been run against official Cloud on an API 36 emulator, including history, streaming replies, workspace terminal/desktop, files and selected management pages. Self-hosted checks currently use local HTTPS fixtures. Approval/question edge cases are fixture-tested; not every server configuration or API has been exercised live.

**Current limits:** push notifications and Android Live Updates are deferred. Raw HTML, math and Mermaid are not rendered; SVG images are unsupported and animated images display their first frame. Provider-specific third-party OAuth/API-key setup forms are not complete. Server capabilities and permissions determine feature availability.

## Build

Android **8.0+** (`minSdk 26`). Development requires **JDK 17+** and Android SDK **platform 37**; `targetSdk` is 36. Use the included Gradle wrapper.

```bash
./gradlew :app:assembleDebug
./gradlew testDebugUnitTest
```

Set `JAVA_HOME` and the SDK location through your Android Studio environment, `ANDROID_HOME`, or an untracked `local.properties`. Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

### Signed release

Create your own signing key, copy `keystore.properties.example` to `keystore.properties`, then enter the absolute key path, alias and passwords. Neither private keys nor local signing properties are committed.

```bash
./gradlew :app:assembleRelease
```

With signing configured, the APK is at `app/build/outputs/apk/release/app-release.apk`. Without signing properties, Gradle produces an unsigned release APK. Back up your signing key securely: future updates must use the same key. A release-signed APK cannot update a debug-signed installation of the same package.

## Architecture

| Module | Responsibility |
|---|---|
| `app` | Navigation, dependency injection and application entry |
| `core:model`, `core:network`, `core:data` | Models, REST/WebSocket protocol, runtime reducer, encrypted credentials and preferences |
| `core:designsystem`, `core:markdown` | Native M3E theme/components and Markdown rendering |
| `feature:login`, `feature:chat`, `feature:sessions` | Authentication, conversations, bot management and cloud workspace |
| `feature:settings`, `feature:bots` | Appearance and reserved bot feature module |

Cloud sessions use encrypted cookie storage, team selection and short-lived WebSocket tickets. Self-hosted connections use bearer authentication. Server addresses require HTTPS. Do not put real credentials in issues, logs or test fixtures.

## License and credits

Source code follows **AGPL-3.0**; see [LICENSE](LICENSE) and [third-party notices](THIRD_PARTY_NOTICES.md). Memoh is the upstream service. Mehomo is not affiliated with Memoh, Google, xAI or Crypton Future Media. The generated avatar is fan artwork inspired by Hatsune Miku and the supplied flat bot-avatar references; character and brand rights remain with their respective owners.

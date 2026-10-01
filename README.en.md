![Mehomo — Material 3 Expressive for your agents](docs/assets/mehomo-overview.en.svg)

# Mehomo

**A native Android client for Memoh, designed around Material 3 Expressive.**

[Download APK](https://github.com/anamaxlec/Mehomo/releases/latest) · [简体中文](README.md) · [Memoh](https://github.com/felinics/Memoh) · [Icon assets](design/icon/README.md)

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

Mehomo connects your phone to an existing Memoh Bot: start a task, follow its execution, respond to decisions, and inspect the same Bot's files, terminal or desktop. Models and agents run on the server, not inside this Android client.

| Area | Implemented actions |
|---|---|
| Bots and sessions | Select a Bot; create, search, rename and delete sessions; grouped history and external-channel labels |
| Streaming | Read replies as they arrive, inspect reasoning/tool activity, stop a run and see running-state indicators |
| Model and reasoning | Search the server catalog, group by provider, choose a model and its supported reasoning levels; ordinary and ACP model paths |
| Execution context | Select server-provided workspace targets, Agent and conversation folder; show selected values in the footer |
| Context usage | Inspect server-reported context usage, refresh it, and compact context when supported |
| Reply actions | Copy, regenerate, fork from a turn and inspect timestamps |
| Attachments and decisions | Pick files/images, send attachments, preview message images; approve/reject tools and answer structured questions |
| Queues and controls | Add, edit, delete and reorder follow-ups; steer supported runs; runtime/permission modes, goals and quick actions |
| Memory | List, create, edit, delete and search memories; status, graph and compaction |
| Schedules | Create/edit, enable/disable, delete and inspect execution logs |
| Usage and resources | 7/30/90-day token totals, provider/model breakdowns, paginated invocation records; CPU/memory/storage when supplied by the server |
| Apps and skills | Search catalogs, inspect details, install apps with SSE progress, inspect installed items, update/removal actions; import/edit/delete skills |
| MCP | List, create, edit, delete and probe connections; inspect tool metadata |
| Files | Browse/filter directories, preview/edit text, create, rename, delete, upload and download |
| Terminal and desktop | PTY input/output, terminal resizing and key controls; official Cloud VNC or self-hosted WebRTC desktop |
| Browser | Open server-provided port previews for cloud apps/sites |

Markdown supports headings, paragraphs, emphasis, lists, blockquotes, rules, links, task lists, strikethrough, tables and syntax-highlighted copyable code blocks. Wide code blocks scroll horizontally and table cells wrap. History/live-output handoff uses stable message identities, and initial loads use shared skeletons.

Available models, reasoning levels, targets and controls depend on the server's capabilities and account permissions. Mehomo does not deploy the Memoh server or run local models.

## Integration and adaptation

| Target | Adaptation |
|---|---|
| Official Memoh Cloud | Email-code login, team selection, restored encrypted cookie sessions, team-scoped requests and ticket-based WebSocket/workspace connections |
| Self-hosted Memoh | HTTPS root or `/api` reverse-proxy paths, username/password login, bearer authentication and refresh while the token remains valid |
| Chat WebSocket | Runtime subscriptions, snapshot/delta merging, acknowledgements, reconnect handling and fresh snapshots after sequence gaps |
| Bot activity SSE | Foreground session/schedule updates; this is not an offline push channel |
| Agent / ACP | Server catalogs/capabilities, ACP model/reasoning changes and runtime controls; the client does not reimplement the agent engine |
| Workspace | Local bundled xterm.js for PTY, noVNC for Cloud desktop and WebRTC for self-hosted desktop |
| External channels | Display server-supplied origins such as Telegram; a complete channel-configuration wizard is not included |

Android adaptation includes Android 8.0+ support (`minSdk 26`, `targetSdk 36`, `compileSdk 37`), native M3E controls, collapsing large titles, IME/system-bar handling, scrollable forms, responsive statistics, constrained reading width, selected font-scale checks, system/light/dark appearance, selectable accents, and 1–4 configurable floating destinations. The default icon uses a lavender background and deep-purple silhouette; night resources retain purple contrast. Android 13+ themed icons use a separate monochrome layer, with actual colors and refresh behavior controlled by a supported launcher.

## Validation and limits

The latest signed release build and APK signature verification passed, with **162 unit tests** across protocol, reducer, authentication and UI helpers. The final icon/night resources and release-signed APK await physical-device testing.

- Official Cloud checks covered restored login, history, DeepSeek V4.1 Flash streaming, model/reasoning selection, selected reply/fork actions, file reading, PTY output and VNC desktop.
- Management APIs/UI have local fixture tests plus selected live Cloud reads; this does not establish that every Cloud mutation works.
- Attachment, decision and queue interfaces are implemented. Approval/question control frames have WebSocket fixture coverage; not all live Cloud branches were exercised.
- Self-hosted authentication/network checks use HTTPS fixtures; a full live self-hosted deployment has not been covered.
- Earlier device checks primarily used an API 36 emulator. Other OEM devices, minimum-version support, final icon switching and release installation still need device coverage.

Push notifications and Android Live Updates are deferred. Raw HTML, math and Mermaid are not rendered; SVG images are unsupported and animations show their first frame. Provider-specific connector OAuth/API-key forms are incomplete. Cloud login currently uses email codes, not every OAuth flow. Full server deployment/container lifecycle, voice/video model configuration and enterprise user administration are outside the current client. Credential storage uses the deprecated but working `EncryptedSharedPreferences`; migration is pending.

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

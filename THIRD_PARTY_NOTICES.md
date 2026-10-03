# Third-party notices

## Upstream project

This is an unofficial client for **Memoh** (<https://github.com/felinics/Memoh>,
<https://app.memoh.net>), which is published under the **AGPLv3**. This client
follows the same license. The Memoh name, wordmark, and brand palette belong to
the upstream project and are used here only to identify the service being
accessed.

## Protocol and design references

The wire protocol, DTO field sets, and design tokens were transcribed from the
upstream repository rather than invented:

- `spec/swagger.json` — REST endpoints and model definitions.
- `apps/web/src/composables/api/useChat.types.ts` and `useChat.ws.ts` — WebSocket
  frame shapes, message types, and the reliable-delivery scheme.
- `apps/web/src/pages/login/components/dot-matrix-bg.vue` — the login backdrop
  (reimplemented natively with Compose `Canvas`; no asset is copied).
- `docker/nginx-app.conf` — the `/api` prefix rule that the URL probing mirrors.

The brand palette in `core/designsystem/.../Color.kt` was derived from the
upstream web client's colours, which are part of the AGPLv3-licensed project.

## Reference clients consulted

Behavioural lessons (retry semantics, token refresh races, scroll anchoring) were
drawn from these third-party clients during planning. No code was copied from
them; the notes live in `research/`:

- `shenmintao/memoh-android`
- `miociallo0721/Thyra`
- `iebb/homem`

## Libraries

Android detail-page and predictive-back motion is adapted from
[DimensionDev/Flare's Router.kt](https://github.com/DimensionDev/Flare/blob/master/app/src/main/java/dev/dimension/flare/ui/route/Router.kt),
licensed under AGPL-3.0. The timing, easing, scale, veil and device-corner behavior
are used in `MemohMotion.kt`, `DetailNavigation.kt` and `SectionNavigation.kt`.
Copyright belongs to the Flare contributors; this client is also AGPLv3.

| Library | License |
|---|---|
| AndroidX / Jetpack Compose / Material 3 | Apache-2.0 |
| Kotlin, kotlinx.coroutines, kotlinx.serialization | Apache-2.0 |
| OkHttp | Apache-2.0 |
| Dagger Hilt | Apache-2.0 |
| Coil | Apache-2.0 |
| commonmark-java (incl. GFM tables, strikethrough) | BSD-2-Clause |
| JUnit 4 | EPL-1.0 |
| xterm.js 6.0.0 and FitAddon 0.11.0 | MIT |
| noVNC 1.7.0 | MPL-2.0 (bundled dependencies carry their original licenses) |
| KaTeX 0.19.0 (including fonts) | MIT |
| Mermaid 11.16.0 | MIT |

The PTY surface bundles the official `@xterm/xterm` and `@xterm/addon-fit`
browser distributions. They are loaded only from App assets; authentication and
the terminal WebSocket remain native. Their complete license texts are included
in `feature/sessions/src/main/assets/workspace/xterm-LICENSE.txt` and
`fit-LICENSE.txt`.

The Cloud desktop surface bundles the unmodified official `@novnc/novnc` 1.7.0
ES modules and their vendor files. Its source and complete license texts are
included in `feature/sessions/src/main/assets/workspace/novnc/`, including
`LICENSE.txt` and `docs/LICENSE*`. The App's native gateway connection supplies
only binary RFB data to the local renderer. Runtime credentials are not passed
to JavaScript. Self-hosted displays retain the upstream WebRTC transport.

Math and diagram previews bundle the official KaTeX and Mermaid browser
distributions in `core/markdown/src/main/assets/rich/`, including their complete
license texts (`LICENSE-katex` and `LICENSE-mermaid`). Renderers load locally;
the application blocks external resources and executable SVG content.

## Mehomo avatar

The launcher avatar is generated fan artwork inspired by Hatsune Miku and the
user-provided flat bot-avatar style references. It is not an official Memoh,
Grok, xAI or Crypton asset. Hatsune Miku and associated character rights belong
to their respective rights holders. The AGPL source-code license does not imply
ownership of third-party character or trademark rights.

# Mehomo icon

Purple Hatsune Miku avatar in the user's supplied flat Grok Bot avatar style: twin-tails, tilted face, solid black capsule eyes, restrained pastel colors. Generated with the built-in imagegen tool using the user's two avatar references.

Assets:

- `mehomo-miku-source.png`: original transparent generated artwork.
- `mehomo-round.png`: 512px round launcher appearance preview, transparent corners.
- `mehomo-play-512.png`: 512 × 512 sRGB RGBA full-square Google Play asset, opaque background, no exterior shadow or baked-in mask.
- `export.swift`: local macOS/AppKit packaging script. Run `swift design/icon/export.swift` from the project root.

The default Android icon uses a full-bleed pale lavender background and a dark purple silhouette on a 108dp canvas. Night resources use a deep purple background and pale lavender foreground. The full visible character is contained within a centered 62dp diameter circle, inside the documented 66dp safe zone. A separate alpha-only layer preserves the twin-tail silhouette and transparent eye cutouts for themed icons. Both normal and round adaptive resources are connected in the manifest. Launcher shape is controlled by the user's device; circular launchers display the round version.

References:
- https://developer.android.com/develop/ui/compose/system/icon_design_adaptive
- https://developer.android.com/distribute/google-play/resources/icon-design-specifications

Generation prompt: Purple Hatsune Miku human avatar, oversized round peach face, black capsule eyes without highlights, lavender/violet twin-tails and graphic bangs, dark purple hair ties, lavender collar and purple tie, gently tilted clockwise, flat Grok Bot avatar shapes matching the supplied references, minimal shadow tones, no robot antennas, no text, transparent foreground with complete silhouette.

Themed previews: `mehomo-white.png`, `mehomo-themed-light.png`, and
`mehomo-themed-dark.png`. Preview colors illustrate the alpha layer; actual
colors are chosen by the launcher from the user's wallpaper and theme. Enable
**Themed icons** in the launcher's Wallpaper & style settings on supported
Android 13+ devices. When themed icons are off, the launcher uses the app's purple light/night
resources; the launcher controls when night resources refresh. The transparent white silhouette is supplied by
`app/src/main/res/drawable-nodpi/ic_launcher_themed.png`.

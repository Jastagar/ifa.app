# Branding

Source artwork for the app icon. The launcher icons in `app/src/main/res/mipmap-*` are generated from these.

| File | What it is |
|---|---|
| `icon-1024.png` | Master icon, full bleed on the dark background (`#020C13`) |
| `adaptive-foreground-1024.png` | The same artwork shrunk into the adaptive-icon safe zone, transparent outside |
| `playstore-512.png` | Play Store listing icon |

How the launcher icon is built:
- **Android 8+:** an adaptive icon (`mipmap-anydpi-v26/ic_launcher.xml`). The background is the colour `ic_launcher_background` (`#020C13`, the artwork's own background, so there's no seam), and the foreground is `ic_launcher_foreground.png`. A white `ic_launcher_monochrome.png` serves Android 13+ themed icons.
- **Android 7:** the plain `ic_launcher.png` and the circle-cropped `ic_launcher_round.png`.

To change the icon, replace these files and regenerate the five mipmap densities: 48 dp legacy, 108 dp adaptive layers.

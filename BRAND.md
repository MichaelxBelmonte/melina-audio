# Melina brand guide

## Name

**Melina** is the public product name. It shortens Michelina into a compact, human name while
keeping the project's origin recognizable. The stable Android application ID and Java/Kotlin
namespace remain `it.michelina.focus` so existing installations, session paths, and JNI symbols do
not break during the rebrand.

The name review was a practical collision search, not legal trademark clearance.

## Positioning

**Bring every voice forward.**

Melina is open-source, on-device software for real-time speech enhancement. It is an experimental
research prototype, not a medical device.

## Visual system

| Token | Value | Use |
|---|---|---|
| Signal | `#C8FF42` | Active signal, logo, primary action |
| Ink | `#080A09` | Primary background |
| Paper | `#EEF2EB` | Primary text |
| Muted | `#9AA398` | Secondary text |

The mark combines an `M`, inward signal focus, and a centered voice waveform. Use the transparent
master mark on dark surfaces and the square app icon for launchers and packaged applications. Keep
clear space around the mark and never add medical, ear, headphone, or microphone imagery to it.

## Assets

- `docs/assets/melina-mark.png` — transparent master mark.
- `docs/assets/melina-app-icon.png` — full-bleed app icon source.
- `desktop/src/main/resources/branding/` — PNG, ICO, and ICNS desktop assets.
- `app/src/main/res/mipmap-nodpi/melina_app_icon.png` — Android launcher/header asset.

## Generation record

The assets were created with the built-in GPT Image workflow.

Master prompt:

```text
Use case: logo-brand
Asset type: master logo mark for a minimal open-source audio technology brand
Primary request: create an original abstract symbol for MELINA, a private on-device real-time
speech enhancement app. Merge the idea of a capital M, a focused human voice waveform, and two
subtle inward-facing signal brackets into one simple geometric mark.
Style/medium: vector-friendly flat logo mark, precise geometric construction, bold negative space,
iconic and highly scalable
Composition/framing: one centered standalone symbol, square canvas, generous even margin
Color palette: single vivid signal-lime color #C8FF42
Constraints: genuinely transparent background with preserved alpha; symbol only; no text, no
letters drawn separately, no gradients, no shadows, no glow, no mockup, no 3D, no fine details,
no border, no watermark; strong silhouette readable at 24px; original design only
Avoid: ears, headphones, medical crosses, brains, microphones, generic soundwave badges, rounded
app-icon container
```

App icon prompt:

```text
Use case: precise-object-edit
Asset type: final cross-platform application icon
Primary request: preserve the exact lime MELINA mark and place it centered on a full-bleed
deep-black #080A09 square background.
Constraints: preserve the symbol geometry and safe-area padding; no text, extra symbols, gradients,
glow, shadow, mockup, 3D, watermark, white pixels, or rounded outer corners.
```

# OrbitScope branding

OrbitScope describes satellite observation and signal inspection. Its turquoise orbital ring and amber satellite/signal accent match the app's existing navy, turquoise, and amber palette.

## Assets

- `orbitscope-logo.png`: original 1254 × 1254 transparent RGBA logo, generated with the built-in image generation tool.
- `../../app/src/main/res/drawable-nodpi/orbitscope_logo.png`: identical image packaged for Android.
- `../../app/src/main/res/drawable/ic_launcher_foreground.xml`: density-independent percentage insets keep the artwork inside the launcher mask.
- `../../app/src/main/res/mipmap-anydpi-v26/`: adaptive launcher and round icons with a navy background.
- `../../app/src/main/res/mipmap-anydpi-v33/`: the same icons with a monochrome layer for themed launchers.

The icon structure follows [Android's adaptive icon guidance](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive). The bitmap is used without artistic edits; Android scales and masks it at runtime. The application ID and signing identity remain stable so an upgrade preserves user data.

## Generation prompt

Built-in image generation was used, without the fallback CLI or an API key. The actual generated image dimensions are recorded above rather than assuming the requested dimensions were returned.

```text
Use case: logo-brand. Asset type: final standalone app logomark and Android adaptive launcher icon foreground for OrbitScope, an approachable satellite-tracking and receive-only public radio app. Create one polished, simple, distinctive symbol with a genuinely transparent background, square 1024 x 1024 canvas. Subject: a bold turquoise open orbital ring crossed diagonally by a clean slim orbital arc, with a small warm amber satellite-like diamond on the arc and a subtle two-line radio-wave accent integrated into the mark. The ring and orbit should read as one coherent exploratory space instrument, with generous negative space and bold rounded strokes that stay legible at 48 pixels. Flat graphic with crisp vector-like edges, elegant geometric proportions, friendly curious scientific character. Palette derived from existing app: turquoise #67DDD7, amber #FFC979, optionally near-white #EAF5F8. This will sit on dark navy #07131F in the app. Center the entire symbol inside the middle 58 percent of the square canvas so every part survives Android circle and squircle masks. No text, letters, wordmark, watermark, background plate, border, photography, 3D, shadows, gradients, lens flare, intricate tiny details, surveillance, ears, weapons, shields or aggressive styling. Deliver only the actual usable transparent logo art, not a mockup, grid of alternatives, or presentation sheet.
```

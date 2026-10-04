# Janus icon (owner's choice, 2026-10-04)

Design B ("two halves"): an arch made of two mirrored halves with a gap at the top, the two sides of Janus
(the god of doorways) turned away from each other. Full-bleed 1024×1024 artwork; each platform applies its own mask.

| File | Use | Colours |
|---|---|---|
| `janus-light.svg` | light appearance (default) | background `#F1F4F9`; halves `#1B2C50` (left) / `#6E8BB8` (right) |
| `janus-dark.svg` | dark appearance | background navy gradient `#1E3158` → `#0D1830` (top-left → bottom-right); halves `#FFFFFF` / `#AFC4E6` |
| `janus-mono.svg` | one-colour: themed/tinted icons, menu bar, status bar | shape only (black, transparent background) |
| `*.png` | 1024 px renders of the above | |

Shape (1024 grid): outer radius 250, inner 150, centre (512, 470), legs down to y 820, a 44 px gap at the top.
Android uses it as adaptive icon layers scaled to 0.78 (inside the 66/108 safe zone); see
`android/src/brand/res/drawable*/ic_launcher_*.xml`. `candidates/` keeps the other designs that were considered.

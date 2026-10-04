# Janus colour system (owner's request, 2026-10-04)

Navy, from the icon: tonal palettes built from `#1B2C50` (navy) and `#6E8BB8` (steel blue). Primary = navy (tone 30
in light mode, a soft blue at tone 80 in dark mode); secondary = steel blue; tertiary = a muted teal (rarely used);
neutrals have a hint of blue, and in dark mode are navy-tinted like the dark icon's background. No indigo/purple
(owner: "looks like Blip"). Android uses these Material 3 roles (`ui/theme/JanusColors.kt`, `res/values{,-night}/colors_janus.xml`).

| Role | Light | Dark |
|---|---|---|
| primary | `#1B4684` | `#A8C8FC` |
| onPrimary | `#FFFFFF` | `#023063` |
| primaryContainer | `#D3E4FF` | `#1B4684` |
| onPrimaryContainer | `#001B3D` | `#D3E4FF` |
| secondary | `#505F78` | `#B8C7E4` |
| secondaryContainer | `#D5E3FF` | `#38475F` |
| onSecondaryContainer | `#0A1C31` | `#D5E3FF` |
| tertiary | `#05696F` | `#81D4DA` |
| tertiaryContainer | `#9DF0F6` | `#034F54` |
| background | `#F6F9FF` | `#0D131F` |
| onBackground | `#191C21` | `#DCE2F3` |
| surface | `#F6F9FF` | `#0D131F` |
| onSurface | `#191C21` | `#DCE2F3` |
| surfaceVariant | `#DAE3F3` | `#3D475B` |
| onSurfaceVariant | `#3F4754` | `#BDC7DE` |
| outline | `#6F7886` | `#8791A7` |
| outlineVariant | `#BEC7D7` | `#3D475B` |
| surfaceContainerLow | `#F0F4FB` | `#151C27` |
| surfaceContainer | `#EAEEF5` | `#19202C` |
| surfaceContainerHigh | `#E4E8F0` | `#242A37` |
| surfaceContainerHighest | `#DFE3EA` | `#2F3542` |
| error | `#BA1A1A` | `#FFB4AB` |

On Android, cards sit on `surfaceContainer` (light) / `surface` (dark) with `surfaceBright` / `surfaceContainerHigh`
for the cards themselves.

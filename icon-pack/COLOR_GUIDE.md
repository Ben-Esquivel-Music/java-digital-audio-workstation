# 🎨 AV Icon Pack — Color Guide

A complementary color palette designed for Audio Visual applications.
All colors are selected from the Material Design color system for vibrancy,
accessibility, and visual harmony across dark and light interfaces.

---

## Color Palette

| Color  | Hex       | RGB              | Complementary Pair | Usage                                 |
|--------|-----------|------------------|--------------------|---------------------------------------|
| Black  | `#000000` | `rgb(0, 0, 0)`     | White              | Backgrounds, outlines, dark elements  |
| White  | `#FFFFFF` | `rgb(255, 255, 255)` | Black            | Details, neutral elements, light UI   |
| Green  | `#00E676` | `rgb(0, 230, 118)`  | Red               | Play, active, positive states         |
| Red    | `#FF1744` | `rgb(255, 23, 68)`  | Green             | Record, stop, alert, destructive      |
| Purple | `#B388FF` | `rgb(179, 136, 255)` | Orange           | Creative, loop, effects, accents      |
| Orange | `#FF9100` | `rgb(255, 145, 0)`  | Purple            | Pause, shuffle, warm accents          |
| Cyan   | `#00E5FF` | `rgb(0, 229, 255)`  | Red               | Info, connectivity, cool accents      |

---

## Complementary Pairs

The palette is built around complementary relationships — colors that sit
opposite each other on the color wheel and create maximum contrast when
placed together.

```
        Green (#00E676)
            |
 Cyan ------+------ Orange
(#00E5FF)   |      (#FF9100)
            |
        Red (#FF1744)

  Purple (#B388FF) ↔ Orange (#FF9100)
  Black  (#000000) ↔ White  (#FFFFFF)
  Red    (#FF1744) ↔ Green  (#00E676)
  Red    (#FF1744) ↔ Cyan   (#00E5FF)
```

| Pair             | Relationship      | Use Case                              |
|------------------|-------------------|---------------------------------------|
| Black ↔ White    | Achromatic        | Text, backgrounds, structural contrast|
| Red ↔ Green      | Complementary     | Stop/Go, Record/Play, Danger/Success  |
| Red ↔ Cyan       | Complementary     | Alert vs. Info, Warm vs. Cool         |
| Purple ↔ Orange  | Split-complement  | Creative accents, effect vs. control  |

---

## Color Swatches

### Primary (Achromatic)

```
 ██████  Black   #000000
 ██████  White   #FFFFFF
```

### Chromatic

```
 ██████  Green   #00E676
 ██████  Red     #FF1744
 ██████  Purple  #B388FF
 ██████  Orange  #FF9100
 ██████  Cyan    #00E5FF
```

---

## Usage Guidelines

### When to Use Each Color

| Color  | Primary Use                     | Avoid                              |
|--------|---------------------------------|------------------------------------|
| Black  | Backgrounds, outlines, shadows  | Large filled areas without contrast|
| White  | Text on dark, details, borders  | Backgrounds without dark elements  |
| Green  | Play, active, success, go       | Warning or error states            |
| Red    | Record, stop, error, delete     | Positive or success states         |
| Purple | Loop, effects, creative tools   | Critical alerts                    |
| Orange | Pause, shuffle, warm highlights | Error or success indicators        |
| Cyan   | Info, connectivity, cool tones  | Destructive actions                |

### Pairing Rules

1. **High contrast pairs** — Use Black/White or Red/Green for maximum
   readability and clear status indication.
2. **Accent pairs** — Combine Purple with Orange for creative/effects UI
   elements that need visual distinction without implying status.
3. **Cool/warm balance** — Pair Cyan with Orange or Red to create visual
   temperature contrast that guides the user's eye.
4. **Avoid adjacent chromatic neighbors** — Do not place Green and Cyan
   side-by-side without a neutral separator (Black or White).

### Accessibility

- All chromatic colors meet **WCAG AA contrast** against Black (`#000000`).
- White text on Green, Red, Purple, Orange, or Cyan backgrounds should be
  tested for sufficient contrast; prefer Black text on lighter colors.
- Use the achromatic pair (Black/White) for all primary text and structural
  elements.

---

## Icon Color Assignments by Category

| Category       | Primary Color | Secondary Color | Accent        |
|----------------|---------------|-----------------|---------------|
| Playback       | Green         | Orange          | Red           |
| Volume & Audio | Green         | Orange          | Purple        |
| Media          | Orange        | Purple          | Green         |
| DAW Tools      | Green         | Purple          | Orange        |
| General AV     | White         | Green           | Orange        |
| Instruments    | Orange        | Red             | Purple        |
| Editing        | Green         | Purple          | Red           |
| Connectivity   | Green         | Purple          | Cyan          |
| Navigation     | White         | Red             | Purple        |
| Notifications  | Orange        | Red             | Green         |
| File Types     | Green         | Purple          | Orange        |
| Social         | Green         | Red             | Orange        |
| Recording      | Red           | Green           | Orange        |
| Metering       | Green         | Orange          | Red           |

# Colour Scheme

CraftPanel uses a **warm charcoal + amber** dark theme. The palette is designed to read clearly as infrastructure tooling — distinct from consumer SaaS dashboards — while keeping semantic colours (
status, alerts, resource pressure) unambiguous at a glance.

---

## Palette

### Base surfaces

| Token              | Hex       |                                   | Usage                                      |
|--------------------|-----------|-----------------------------------|--------------------------------------------|
| `--bg`             | `#0e0d0c` | <swat-ch hex="#0e0d0c"></swat-ch> | Page background                            |
| `--surface`        | `#1c1917` | <swat-ch hex="#1c1917"></swat-ch> | Cards, sidebar, topbar                     |
| `--surface-high`   | `#27241f` | <swat-ch hex="#27241f"></swat-ch> | Hover states, table headers, panel headers |
| `--surface-higher` | `#312d27` | <swat-ch hex="#312d27"></swat-ch> | Active input backgrounds                   |
| `--border`         | `#2e2a24` | <swat-ch hex="#2e2a24"></swat-ch> | Default borders and dividers               |
| `--border-high`    | `#3d3830` | <swat-ch hex="#3d3830"></swat-ch> | Focused or hovered borders                 |

The base palette uses **stone/zinc undertones** (warm, slightly brown-black) rather than cool navy. This is the primary differentiator from most dark-themed server dashboards.

### Accent

| Token             | Hex                    |                                                | Usage                                                 |
|-------------------|------------------------|:-----------------------------------------------|-------------------------------------------------------|
| `--accent`        | `#d97706`              | <swat-ch hex="#d97706"></swat-ch>              | Primary buttons, active nav, normal-range metric bars |
| `--accent-bright` | `#f59e0b`              | <swat-ch hex="#f59e0b"></swat-ch>              | Button hover, highlights                              |
| `--accent-subtle` | `rgba(217,119,6,0.07)` | <swat-ch hex="rgba(217,119,6,0.07)"></swat-ch> | Active nav background tint                            |
| `--accent-glow`   | `rgba(217,119,6,0.15)` | <swat-ch hex="rgba(217,119,6,0.15)"></swat-ch> | Button focus glow                                     |

Amber is used sparingly — active state, primary action, and metric bars within normal range. It should never appear as a background fill on large areas.

### Text

| Token            | Hex       |                                   | Usage                                |
|------------------|-----------|:----------------------------------|--------------------------------------|
| `--text-primary` | `#f5f0e8` | <swat-ch hex="#f5f0e8"></swat-ch> | Headings, server names, values       |
| `--text-dim`     | `#a89880` | <swat-ch hex="#a89880"></swat-ch> | Body text, secondary labels          |
| `--text-muted`   | `#665e52` | <swat-ch hex="#665e52"></swat-ch> | Metadata, timestamps, column headers |

Text uses warm off-white rather than pure white, which complements the warm surface tones and reduces eye strain on long admin sessions.

### Semantic — status

| Token              | Hex                      |   | Usage                                     |
|--------------------|--------------------------|:--|-------------------------------------------|
| `--healthy`        | `#4ade80`                | <swat-ch hex="#4ade80"></swat-ch>  | HEALTHY badge, active node dot            |
| `--healthy-bg`     | `rgba(74,222,128,0.08)`  | <swat-ch hex="rgba(74,222,128,0.08)"></swat-ch>  | HEALTHY badge background                  |
| `--healthy-border` | `rgba(74,222,128,0.18)`  | <swat-ch hex="rgba(74,222,128,0.18)"></swat-ch>  | HEALTHY badge border                      |
| `--error`          | `#f87171`                | <swat-ch hex="#f87171"></swat-ch>  | UNHEALTHY badge, alert bar, critical chip |
| `--error-bg`       | `rgba(248,113,113,0.08)` | <swat-ch hex="rgba(248,113,113,0.08)"></swat-ch>  | UNHEALTHY badge background                |
| `--error-border`   | `rgba(248,113,113,0.2)`  | <swat-ch hex="rgba(248,113,113,0.2)"></swat-ch>  | UNHEALTHY badge border                    |
| `--warning`        | `#fbbf24`                | <swat-ch hex="#fbbf24"></swat-ch>  | Warning alerts, metric bars above 65%     |
| `--warning-bg`     | `rgba(251,191,36,0.08)`  | <swat-ch hex="rgba(251,191,36,0.08)"></swat-ch>  | Warning badge background                  |
| `--warning-border` | `rgba(251,191,36,0.2)`   | <swat-ch hex="rgba(251,191,36,0.2)"></swat-ch>  | Warning badge border                      |

Semantic colours intentionally diverge from the amber accent — amber is brand, yellow/green/red are status. They must never be confused.

---

## Metric bar thresholds

Resource metric bars (CPU, RAM, disk) change colour based on the current value:

| Range     | Colour | Token                 |
|-----------|--------|-----------------------|
| 0 – 65%   | Amber  | `--accent` `#d97706`  |
| 66 – 85%  | Yellow | `--warning` `#fbbf24` |
| 86 – 100% | Red    | `--error` `#f87171`   |

This makes node pressure immediately readable without needing to read the percentage label.

---

## Typography

| Role                                     | Family           | Weight  | Notes                                |
|------------------------------------------|------------------|---------|--------------------------------------|
| Headings, nav, badges                    | Barlow Condensed | 700–800 | Uppercase, letter-spaced for labels  |
| Body text                                | Barlow           | 400–600 | Used for descriptions and prose      |
| Data values, hostnames, timestamps, code | JetBrains Mono   | 400–600 | All tabular data, metric values, IPs |

Barlow Condensed's tall, narrow letterforms suit the density of an admin dashboard. JetBrains Mono is used for anything that benefits from fixed-width alignment — player counts, port numbers, cron
expressions, API paths.

---

## Tailwind CSS variables

When implementing with Tailwind and shadcn/ui, declare these as CSS custom properties on `:root` and map them to the Tailwind theme:

```css
:root {
    --bg: #0e0d0c;
    --surface: #1c1917;
    --surface-high: #27241f;
    --surface-higher: #312d27;
    --border: #2e2a24;
    --border-high: #3d3830;

    --accent: #d97706;
    --accent-bright: #f59e0b;
    --accent-subtle: rgba(217, 119, 6, 0.07);
    --accent-glow: rgba(217, 119, 6, 0.15);

    --text-primary: #f5f0e8;
    --text-dim: #a89880;
    --text-muted: #665e52;

    --healthy: #4ade80;
    --healthy-bg: rgba(74, 222, 128, 0.08);
    --healthy-border: rgba(74, 222, 128, 0.18);

    --error: #f87171;
    --error-bg: rgba(248, 113, 113, 0.08);
    --error-border: rgba(248, 113, 113, 0.2);

    --warning: #fbbf24;
    --warning-bg: rgba(251, 191, 36, 0.08);
    --warning-border: rgba(251, 191, 36, 0.2);
}
```

```js
// tailwind.config.js
module.exports = {
    theme: {
        extend: {
            colors: {
                bg: 'var(--bg)',
                surface: 'var(--surface)',
                'surface-high': 'var(--surface-high)',
                border: 'var(--border)',
                accent: 'var(--accent)',
                'accent-bright': 'var(--accent-bright)',
                'text-primary': 'var(--text-primary)',
                'text-dim': 'var(--text-dim)',
                'text-muted': 'var(--text-muted)',
                healthy: 'var(--healthy)',
                error: 'var(--error)',
                warning: 'var(--warning)',
            },
            fontFamily: {
                condensed: ['"Barlow Condensed"', 'sans-serif'],
                sans: ['Barlow', 'sans-serif'],
                mono: ['"JetBrains Mono"', 'monospace'],
            },
        },
    },
}
```

---

## Google Fonts import

```css
@import url('https://fonts.googleapis.com/css2?family=Barlow+Condensed:wght@400;500;600;700;800&family=Barlow:wght@400;500;600&family=JetBrains+Mono:wght@400;500;600&display=swap');
```

Or in `_app.tsx` / `layout.tsx` via `next/font/google`:

```ts
import {Barlow, Barlow_Condensed, JetBrains_Mono} from 'next/font/google'

export const barlow = Barlow({
    subsets: ['latin'],
    weight: ['400', '500', '600'],
    variable: '--font-sans',
})

export const barlowCondensed = Barlow_Condensed({
    subsets: ['latin'],
    weight: ['400', '500', '600', '700', '800'],
    variable: '--font-condensed',
})

export const jetbrainsMono = JetBrains_Mono({
    subsets: ['latin'],
    weight: ['400', '500', '600'],
    variable: '--font-mono',
})
```

---

## Design principles

**Amber is brand, not status.** Never use amber to mean "warning" — that role belongs to `--warning` (`#fbbf24`). Amber appears on interactive elements (buttons, active states, metric bars within
normal range).

**Density over decoration.** Admin dashboards are used for extended periods under pressure. Avoid decorative gradients, glows, or animations outside of status indicators and micro-interactions. Every
visual element should carry information.

**Warm surfaces, cool text.** The off-white primary text (`#f5f0e8`) sits cleanly on the warm dark backgrounds without the harshness of pure white. Muted text (`#665e52`) uses the same warm
undertone — metadata recedes naturally without requiring opacity tricks.

**Monospace for all data.** Anything that a user needs to scan vertically — counts, percentages, IPs, ports, timestamps — uses JetBrains Mono. This ensures column alignment in tables and makes
numerical differences immediately legible.

# AegisAI — UI Review

**Scope:** `aegis-lc4j/frontend` (Next.js App Router, TypeScript, Tailwind v4, framer-motion,
react-markdown). Backend logic findings are in **[CODE_REVIEW.md](./CODE_REVIEW.md)**.

**Build state:** `tsc --noEmit` clean, `eslint` clean, `next build` succeeds, `vitest run` 9/9.

**Headline:** the app was rebuilt from a "simple chat" into a premium fintech interface — a
startup splash, a near-black periwinkle theme with a measured-contrast token system, a shared
widget-card family, a hero empty-state, and a real accessibility pass. What remains is a cluster
of **stream-lifecycle and data-fetching bugs** that are logic, not visuals, and were deliberately
left for a focused pass.

---

## 1. Delivered — the redesign (verified in current source)

**Startup splash** (`components/Splash.tsx`)
Brand mark ignites with a lens streak, wordmark/tagline rise, an accent bar sweeps. Covers first
paint *while the token mint + history load run behind it*, so the hold does real work. Once per
tab session, skippable by key/click, collapsed to ~500ms under `prefers-reduced-motion`.

**Design tokens** (`app/globals.css`)
Near-black `#08090c` canvas, periwinkle `#8b9cff` accent, one top-centre bloom + grain
(`.canvas-bloom` / `.canvas-grain`), hairline borders, a `.lit` inner top-highlight. **Dark is the
default**; light is a tuned counterpart, not an inversion.

The contrast failures from the prior review are fixed by a **three-tier token system** — `--x`
(icons only), `--x-soft` (fills, never text), `--x-ink` (the only value allowed as text on a
soft fill). Every `-ink`/`-soft` pair measures **≥ 5.5:1 in both themes** (was 1.65:1 for the
worst case — the pending-approval badge). A grep confirms zero remaining base-hue-as-text-on-tint;
the surviving `text-good`/`text-warning` classes are all icons. Filled accent controls use a
separate `--accent-strong`/`--on-accent` pair (7.01:1 light / 7.87:1 dark), because `--accent`
itself only reached 4.17:1 as a button background in light mode. One named type scale
(`--text-micro`/`label`/`body`/`figure`) replaces the scattered bracket sizes.

**Widget family** (`components/chat/WidgetCard.tsx` + all nine widgets)
The five copy-pasted shell strings collapsed into one `WidgetCard` + `StatusBadge`. Fixed in the
process: `LedgerReceipt` no longer paints a routine transfer leg red (red is back to meaning
failure); `SpendingStatement`'s "Interest" is off `bg-critical` onto a categorical ramp;
`ApprovalCard` shows "Card replacement · CRD-7001" instead of the raw `CARD-REPLACEMENT:CRD-7001`
key and stops making `$0.00` the hero on closures; `MessageBubble` prints "Stopped by guardrails"
instead of leaking the raw `blocked` enum. Widgets attach beneath the assistant text in **one
shared left-rail column**, so a three-widget turn reads as one answer, not four stacked cards.

**Chat surface** — assistant turns are avatar + flowing text (not bubbles); user turns keep a
filled bubble. Empty state is a hero: glowing beacon, ghosted `Understand`/`Resolve` framing the
headline, centred composer, three clickable capability cards. Composer moves to the bottom once a
conversation starts. Streaming caret pins to the last inline child.

**Accessibility** — one global `:focus-visible` ring (was 2 declarations app-wide); sidebar
conversation rows are real `<button>`s; the mobile drawer traps focus and restores it on Escape;
`prefers-reduced-motion` is handled in CSS and via `useReducedMotion` in every animated component;
carousel has roles + 44px targets.

**Admin parity** — `/admin` moved onto the same tokens and radius, gained `admin/layout.tsx` so
its tab reads "Admin · Achu FinBot" instead of the customer-facing title, `EventsTable` scrolls
horizontally, and its status pills use the `-ink` tier.

**Delete safety** (`hooks/useConversations.ts`, working tree) — `remove` now awaits the DELETE and
**rolls back the optimistic removal** on failure with an alert, instead of the previous
fire-and-forget that dropped the row whether or not the server accepted it.

---

## 2. Open — P0 (stream lifecycle & data fetching, not visual)

These are logic bugs, independent of the redesign, and are the highest-impact work remaining.

### 2.1 Switching conversations mid-stream corrupts the new conversation's state
`hooks/useChatStream.ts`

`useChatStream` is one instance for the life of `ChatShell`; `activeId` is an argument, not a
remount trigger. Send in conversation A, click B before A finishes: A's `AbortController` is
orphaned (only `abortRef.current` is stoppable, and it's been overwritten), so A's stream runs on
uncancelled. A's `patchBot` calls become no-ops (its `botId` is gone), but A's late
`onMeta`/`onError`/`onAbort` all call `setState` **without a `botId` guard**, flipping `busy` to
false and clearing `statuses` on conversation B mid-stream.
**Fix:** key the stream state (or at least `abortRef` + `botId` + a `currentConvIdRef`) to
`conversationId`; abort the previous controller on change; guard the status/busy setters with
`if (convIdAtSend !== currentConvIdRef.current) return;`.

### 2.2 `loadHistory` has no staleness guard and always double-fetches
`hooks/useChatStream.ts`, `hooks/useConversations.ts`

`activeId` initialises to `"default"` and only resolves after `getProfile()` → the token mint. So
every load fires `fetchHistory("default")`, then `fetchHistory(realId)`, last-to-resolve wins. A
slow `"default"` fetch can overwrite the real conversation. Same race on rapid sidebar clicks.
**Fix:** capture the requested `conversationId`, compare against a ref before applying `setState`;
gate the initial `loadHistory` on `identityKey` being non-null.

### 2.3 An unknown SSE event name strands the bubble in `streaming: true` forever
`lib/sse.ts`

`dispatch` is an `if/else-if` chain with no `else`. Only `onMeta` (or a terminal error) clears
`streaming`. Any future/typo'd event name with valid JSON is parsed and dropped, and if the
backend ever signals a problem via a non-`meta` event the typing indicator never stops.
**Fix:** make `dispatch` exhaustive; standardise on the stream always terminating with `meta` or an
explicit `error` event, and add that `error` branch.

### 2.4 Every backend failure renders the same generic string
`lib/sse.ts`, `hooks/useChatStream.ts`

`streamChat` collapses any non-OK into `onError`, which ignores `err` and always shows "Something
went wrong reaching the assistant." 401 (expired token — and `streamChat` bypasses `authFetch`'s
re-mint, so it fails forever), 403, 429/blocked, 502, 503 are indistinguishable.
**Fix:** thread the status through; 401 → re-mint once and retry; 403/blocked, 502/503 → distinct
copy with the right affordance.

---

## 3. Open — P1

### 3.1 A stopped or errored answer looks identical to a complete one
`hooks/useChatStream.ts`, `components/chat/MessageBubble.tsx`

`onAbort` sets `streaming: false` and nothing else; `onError` keeps partial text verbatim
(`m.text || "…"`). A dispute-deadline explanation cut off mid-sentence renders as a confident,
finished answer. For a banking assistant that's a trust problem, not cosmetics. The `MessageBubble`
source-badge slot already exists — reuse it for a `stopped`/`truncated` affix.

### 3.2 `adminApi.ts` reinvents `api.ts`'s token logic and lost the 401 retry
Estimates `exp` as `Date.now() + 55min` instead of decoding the JWT claim, and `adminGet` has no
401 handling — once the token goes bad the 15s poll fails forever behind a generic banner.
**Fix:** collapse both onto one shared `authFetch` (parameterised by cache key + mint body).

### 3.3 No error/loading boundaries; proxy has no `maxDuration`
`app/` has no `error.tsx`, `global-error.tsx`, or `loading.tsx` — a client throw in production is
Next's blank "Application error" page. `app/api/[...path]/route.ts` exports no `maxDuration`, so on
a Vercel deploy the SSE proxy is capped at the platform default (10–15s), which truncates streams
*before* the in-code 30s `AbortSignal` fires.

### 3.4 The App Router shell is one client island
`/` is ~227 kB First Load JS, marked static but rendering an empty div because `ChatShell` is
`"use client"` all the way down. The client-side JWT mint genuinely blocks server-rendering the
chat, but the static shell (header, suggestion markup, admin headings) needn't be client JS.
`/admin` uses a hardcoded identity and *could* fetch server-side on first load.

---

## 4. Carried forward — deliberate / lower priority

- **Tokens in `sessionStorage`** (`lib/api.ts`) — readable by any XSS; the one frontend item with
  real security weight. A banking product wants an `httpOnly`, `SameSite=Strict` cookie with CSRF
  re-enabled server-side. `next.config.ts` is also empty — no CSP/security headers, which compounds
  this.
- **`lib/sse.ts` does no runtime schema validation** — `JSON.parse` then `as` cast. Low risk (the
  backend is the only producer); a `zod` boundary parse would be cheap insurance. Same gap on the
  history-rehydration path in `hydrateAssistantMessage`.
- **The two proxy items from CODE_REVIEW** (30s `AbortSignal` truncating SSE; the failover retry
  replaying an already-consumed `req.body`) remain — see §3.3 for the related `maxDuration` gap.
- **`public/`** still holds the Next.js starter SVGs and the default favicon.

*Verified correct, don't break:* `MessageBubble` uses `ReactMarkdown` + `remarkGfm` with **no**
`rehype-raw` — HTML escaped, `javascript:` URLs sanitised. The blocking inline theme script in
`layout.tsx` sets the class before paint with `suppressHydrationWarning`, and the theme toggle now
renders both icons via the `dark:` variant, so there's no hydration mismatch and no flash.

---

## 5. Recommended next

1. **§2.1–2.4 as one "stream lifecycle" pass** — same subsystem (`useChatStream` + `sse.ts`);
   fixing them separately means touching the hook four times. Highest user impact.
2. **§3.1** — the stopped/errored-answer affix. Small, and it's a trust issue.
3. **§3.3** — add `error.tsx` + `loading.tsx`, and `export const maxDuration` on the proxy route.
4. **§3.2** — collapse the two token caches.
5. **§4** — the `sessionStorage`→cookie migration + a CSP in `next.config.ts`, if this moves past
   demo status.

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

**Delete safety** (`hooks/useConversations.ts`) — `remove` awaits the DELETE and **rolls back the
optimistic removal** on failure with an alert, instead of the previous fire-and-forget that dropped
the row whether or not the server accepted it.

---

## 2. The stream-lifecycle pass — delivered

The P0/P1 logic cluster from the prior version of this review is now fixed, verified by `tsc`,
`eslint`, `vitest` (12/12), and a `next build`. New Vitest cases cover the two load-bearing
invariants.

**Conversation-switch no longer corrupts state (was §2.1)** `useChatStream` keeps an `activeIdRef`
updated every render; `send` captures its `conversationId` and every callback — `patchBot`, the
status setter, and the busy/`settle` setter — early-returns unless still current. A new `send`
aborts the previous controller, and a `useEffect` cleanup aborts the in-flight stream on
conversation change/unmount. A's late `meta`/`error`/`abort` can no longer flip `busy` or wipe
`statuses` on conversation B.

**`loadHistory` staleness (was §2.2)** A monotonic `historyReqRef` tags each fetch; only the newest
applies, and it double-checks `activeIdRef` before `setState`. A slow `"default"` fetch can't
overwrite a real conversation, and rapid sidebar clicks resolve last-issued-wins.

**No more stranded `streaming: true` (was §2.3)** `streamChat` now guarantees **exactly one terminal
callback** — `onMeta` (success), `onAbort` (cancelled), or `onError` (anything else). `dispatch` is
an exhaustive `switch` that ignores unknown event names, and if the body closes without a `meta`
frame the turn ends in `onError({ kind: "stream" })`. Covered by a test that sends a token then
closes and asserts `onError`.

**Per-status failure copy + 401 re-auth (was §2.4)** `onError` receives a structured `StreamError`
(`kind` + `status`); `errorCopy` maps 401/403/429/5xx and the cut-short case to distinct messages.
`streamChat` takes a `reauth` callback and retries once on a 401 with a fresh token (tested). The
stream path no longer bypasses re-mint.

**Stopped/errored answers are marked (was §3.1)** `Message.interrupted` (`"stopped"` | `"error"`);
`MessageBubble` renders "You stopped this response" or "Response interrupted — it may be
incomplete." A cut-off answer can no longer read as a confident, complete one.

**One shared auth client (was §3.2)** `lib/authClient.ts` — both the customer app (`api.ts`) and
the admin dashboard (`adminApi.ts`) route through it, so admin gained the real-`exp` decode and the
401-retry it was missing; the 15s poll now recovers from an expired token instead of failing
forever.

**Error/loading boundaries + `maxDuration` (was §3.3)** Added `app/error.tsx`, `global-error.tsx`
(self-contained, renders its own `<html>`), and `app/loading.tsx` (branded). The proxy route
exports `maxDuration = 60` and `dynamic = "force-dynamic"`, so a long stream isn't cut at the
platform's default function timeout.

**Also confirmed already-fixed upstream:** the two proxy items from earlier (30s `AbortSignal`
truncating SSE; the failover retry replaying a consumed `req.body`) were closed in commit
`c5f49a8` — streaming requests skip the timeout and the body is buffered once, both with tests in
`route.test.ts`.

---

## 3. Hardening pass — delivered

**The JWT is out of JavaScript's reach (was §3.2 — the one item with real security weight)**
Moved to a BFF pattern, backend untouched. `POST /api/auth/token` (`app/api/auth/token/route.ts`)
mints against the backend and sets the JWT as an **httpOnly, `SameSite=Strict`** cookie (Secure in
prod), returning only the non-secret profile to the client. The `[...path]` proxy reads that cookie
and injects `Authorization: Bearer …` upstream (selecting `aegis_session` vs `aegis_admin_session`
by path, so the customer and admin identities stay separate). `authClient.ts` now caches only the
profile — **no token in `sessionStorage`, nothing for an XSS to steal**. `sse.ts` and the fetch
helpers drop the bearer and ride the same-origin cookie; 401s re-mint (refreshing the cookie) and
retry.

**CSP + security headers (was §3.2 cont.)** `next.config.ts` sets `X-Content-Type-Options`,
`X-Frame-Options: DENY`, `Referrer-Policy`, and `Permissions-Policy` always, plus a
`Content-Security-Policy` (notably `connect-src 'self'`, bounding exfiltration) and HSTS in
production. CSP is production-gated so it doesn't fight dev HMR; verified the base headers appear in
dev and CSP is appended under `NODE_ENV=production`.

**Runtime boundary validation (was §3.3)** `lib/schemas.ts` — hand-rolled predicate validators (no
dependency; zod cost ~68 kB of First Load for a few flat shapes wasn't justified). `sse.ts` gates
every structured widget frame, and a malformed `meta` still terminates the turn as incomplete
rather than stranding it. `hydrateAssistantMessage` applies the same gate to persisted history
rows. Covered by the existing SSE tests (13/13).

**`public/` + favicon (was §3.4)** Removed the five Next.js starter SVGs and the default
`favicon.ico`; added `app/icon.svg` — the brand mark on the near-black tile, so the browser tab is
no longer a template tell.

**CSRF defense-in-depth (was §4.0, surfaced by the in-depth audit)** The cookie migration made the
session cookie ambient, so CSRF against the proxy became possible in principle — previously
impossible, since the token in `sessionStorage` was not an ambient credential. `SameSite=Strict` is
the primary defense; on top of it, `backendProxy.isCrossSite` now rejects any **cross-site,
state-changing** request (`POST`/`PUT`/`DELETE`/`PATCH`) with a 403 before the bearer is injected —
in both the proxy `handle()` and the auth route (which also blocks login-CSRF / forced sessions). It
keys off the browser-set, unforgeable `Sec-Fetch-Site` header, falling back to an Origin-vs-Host
comparison (proxy-robust), and never blocks when no signal is present (same-origin GET, non-browser
client). Two tests assert cross-site → 403 and same-origin → pass; verified live both ways. The
backend's `csrf().disable()` remains correct — it only ever sees proxy-injected bearers.

---

## 4. Open — deliberate judgment call

### 4.1 The App Router shell is one client island (not done — intentional)
`/` is ~231 kB First Load, marked static but rendering an empty div because `ChatShell` is
`"use client"` throughout. A full RSC split would hoist the static chrome to the server, but
`ChatShell` is deeply interactive (streaming, aborts, per-conversation state) and the client-side
session mint blocks server-rendering the personalised parts — so the split is a sizeable rewrite
for a modest first-paint win, with real regression surface. **Deliberately deferred**: `loading.tsx`
already covers the first-paint gap, and the payoff doesn't justify the risk for a demo. Revisit if
first-paint JS becomes a measured problem.

### 4.2 Toolchain note (not a code issue)
`next start` against the current `--turbopack` build throws `routesManifest.dataRoutes is not
iterable` — a known Next 15 turbopack-build-vs-`next start` mismatch, pre-existing and unrelated to
this work. `next build` succeeds; deploys via a platform adapter are unaffected. If self-hosting
with `next start` is needed, drop `--turbopack` from the `build` script.

*Verified correct, don't break:* `MessageBubble` uses `ReactMarkdown` + `remarkGfm` with **no**
`rehype-raw` — HTML escaped, `javascript:` URLs sanitised. The blocking inline theme script in
`layout.tsx` sets the class before paint with `suppressHydrationWarning`, and the theme toggle
renders both icons via the `dark:` variant, so there's no hydration mismatch and no flash.

---

## 5. Status

No actionable items open. §4.1 (RSC split) is deferred by judgment and §4.2 is a toolchain note.
`tsc`, `eslint`, `vitest` (15/15), and
`next build` all green.

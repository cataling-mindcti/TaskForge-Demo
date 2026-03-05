# TaskForge Frontend — UI/UX Review

**Date:** 2026-03-05
**Reviewer role:** UI/UX Expert
**Scope:** Angular 21 SPA — navigation, visual design, responsiveness, accessibility (WCAG 2.1 AA), user feedback states
**Constraints:** Angular Material 21 + Tailwind CSS 4, dark theme support required

---

## Table of Contents

1. [What Works Well](#what-works-well)
2. [Navigation](#navigation)
3. [Visual Design](#visual-design)
4. [Responsiveness](#responsiveness)
5. [Accessibility (WCAG 2.1 AA)](#accessibility-wcag-21-aa)
6. [User Feedback — Loading, Error, Empty States](#user-feedback--loading-error-empty-states)
7. [Dark Theme](#dark-theme)
8. [Summary Table](#summary-table)

---

## Severity Scale

| Level | Meaning |
|-------|---------|
| **P0** | Critical — broken functionality or severe accessibility failure |
| **P1** | High — significant UX degradation or WCAG AA violation |
| **P2** | Medium/Low — polish, consistency, or best-practice gap |

---

## What Works Well

Before identifying issues, the following patterns are well-implemented and should be preserved:

- **Theme persistence**: `ThemeService` correctly reads `localStorage` and OS `prefers-color-scheme` on first load, persisting user preference across sessions (`theme.service.ts:26-29`).
- **Password visibility toggle**: Accessible `aria-label` updates dynamically (`login-page.component.ts:77`), matching best practices.
- **Password strength checklist**: Real-time visual feedback on the register page with met/unmet states and icons is an excellent pattern (`register-page.component.ts:129-148`).
- **Dark mode variants on banners**: Login/register error and info banners correctly define `:host-context(body.dark-theme)` overrides (`login-page.component.ts:143-160`).
- **Route-level access control**: Auth guard and public guard prevent unauthorized access and redirect loops cleanly.
- **Session expiry messaging**: The query-param-driven session message on login (`login-page.component.ts:179-182`) is a solid UX pattern.
- **`ariaCurrentWhenActive`**: Correctly applied to the active nav link (`main-layout.component.ts:52`).
- **OnPush change detection**: Applied consistently — good for performance.
- **Signal-based architecture**: Modern, reactive patterns throughout.
- **Form validation UX**: Errors shown only after `touched`, preventing premature error display.
- **Dashboard layout**: Responsive 2-column grid with a `900px` breakpoint is a solid foundation.

---

## Navigation

### NAV-1 — Sidenav has no responsive behavior **[P0]**

**Problem:** The sidenav is declared `mode="side" opened` with no breakpoint logic. On viewports narrower than ~768px the sidenav occupies its full 220px width permanently, pushing main content into a critically narrow strip. The hamburger button exists but has no effect because `mode="side"` never overlays content — it always shifts it.

**File:** `main-layout.component.ts:46`

**Fix:** Use `@angular/cdk/layout`'s `BreakpointObserver` to switch `mode` and `opened` dynamically:

```typescript
// main-layout.component.ts
private readonly breakpointObserver = inject(BreakpointObserver);
protected readonly isMobile = toSignal(
  this.breakpointObserver.observe(Breakpoints.Handset).pipe(map(r => r.matches)),
  { initialValue: false }
);
```

```html
<mat-sidenav
  #sidenav
  [mode]="isMobile() ? 'over' : 'side'"
  [opened]="!isMobile()"
>
```

---

### NAV-2 — No skip-to-main-content link **[P1]**

**Problem:** Keyboard and screen-reader users must tab through the entire toolbar and sidenav on every page load before reaching page content. WCAG 2.4.1 (Bypass Blocks) requires a mechanism to skip repeated navigation.

**Fix:** Add a visually-hidden skip link as the first focusable element in `index.html` or `app.component.ts`:

```html
<a class="skip-link" href="#main-content">Skip to main content</a>
```

```scss
.skip-link {
  position: absolute;
  top: -40px;
  left: 0;
  background: var(--mat-primary-color);
  color: white;
  padding: 8px;
  z-index: 9999;
  &:focus { top: 0; }
}
```

Add `id="main-content"` to `<mat-sidenav-content>`.

---

### NAV-3 — Logout button has no visible label **[P1]**

**Problem:** The logout action is represented only by a `logout` icon in the toolbar with no visible text label. Icon-only buttons require additional context for users unfamiliar with the iconography. Although `aria-label="Logout"` is present (good), sighted users without prior context may not recognize the icon's meaning.

**File:** `main-layout.component.ts:40-42`

**Fix:** Add a visible text label on larger screens, hiding it on mobile:

```html
<button mat-button (click)="authStore.logout()" aria-label="Logout">
  <mat-icon>logout</mat-icon>
  <span class="btn-label">Logout</span>
</button>
```

```scss
.btn-label { display: none; }
@media (min-width: 600px) { .btn-label { display: inline; } }
```

---

### NAV-4 — No 404 / not-found page **[P2]**

**Problem:** The wildcard route (`**`) silently redirects to `/login`, which is confusing. If an authenticated user types an invalid URL, they are unexpectedly logged in as the guard redirects them back. A distinct 404 page communicates clearly that the route doesn't exist.

**File:** `app.routes.ts:51-53`

**Fix:** Create a `NotFoundComponent` and update the wildcard route:

```typescript
{ path: '**', component: NotFoundComponent }
```

---

### NAV-5 — Auth layout has no theme toggle **[P2]**

**Problem:** The login and register pages use `AuthLayoutComponent` which provides no theme toggle. Users cannot switch to dark mode before authenticating, even if that's their OS preference.

**File:** `auth-layout.component.ts`

**Fix:** Import and include `ThemeToggleComponent` in `AuthLayoutComponent`'s toolbar.

---

## Visual Design

### VIS-1 — Task chip styles are defined but never applied **[P0]**

**Problem:** `TaskCardComponent` computes `statusClass()` and `priorityClass()` values (e.g., `'status-todo'`, `'priority-high'`) and applies them to `mat-chip` elements. However, `task-card.component.ts` defines **no component styles at all** — no `styles` array and no linked stylesheet. The classes are applied but have zero CSS rules, rendering all status and priority chips identically unstyled. This is a functional regression.

**File:** `task-card.component.ts:50-69`

**Fix:** Add a `styles` array to the component with chip color rules that work in both themes:

```typescript
styles: `
  .status-todo mat-chip { --mdc-chip-label-text-color: #546e7a; }
  .status-in-progress mat-chip { --mdc-chip-label-text-color: #1565c0; }
  .status-done mat-chip { --mdc-chip-label-text-color: #2e7d32; }
  /* ... etc. */
  :host-context(body.dark-theme) .status-in-progress mat-chip {
    --mdc-chip-label-text-color: #90caf9;
  }
`
```

---

### VIS-2 — Tailwind CSS 4 is declared in the stack but never used **[P1]**

**Problem:** The project specifies Tailwind CSS 4 as a core styling tool but not a single Tailwind utility class appears in any component or stylesheet. All layout and spacing is done with bespoke SCSS, defeating the purpose of the utility-first approach and creating maintainability overhead. Conventions are inconsistent across the codebase.

**Fix:** Establish a clear convention: use Tailwind utilities for layout, spacing, and typography; use Angular Material's component tokens for theming. Migrate repeated patterns (e.g., `display: flex; align-items: center; gap: 8px`) to Tailwind classes (`flex items-center gap-2`). Update `CLAUDE.md` with the chosen convention.

---

### VIS-3 — Hardcoded hex colors don't adapt to theming **[P1]**

**Problem:** Status and priority indicator colors throughout the app are hardcoded hex values (`#42a5f5`, `#66bb6a`, `#ffa726`, etc.). These are not linked to Angular Material's color token system, meaning they cannot be updated centrally and may clash if the Material palette is ever changed.

**Files:** `status-summary-cards.component.ts:46-50`, `priority-breakdown.component.ts:79-83`, `overdue-tasks-section.component.ts:37,44`

**Fix:** Define semantic CSS custom properties in `styles.scss`:

```scss
:root {
  --color-status-todo: #90a4ae;
  --color-status-in-progress: #42a5f5;
  --color-status-done: #66bb6a;
  --color-priority-critical: #e57373;
  /* ... */
}
body.dark-theme {
  --color-status-in-progress: #90caf9;
  /* adjust for dark contrast */
}
```

---

### VIS-4 — `mat-flat-button color="primary"` uses deprecated API **[P2]**

**Problem:** In Angular Material 3 (MDC-based), the `color` input on buttons is deprecated. Using it may produce unexpected results or break in future versions.

**Files:** `login-page.component.ts:88`, `register-page.component.ts:165`

**Fix:** Remove `color="primary"` and apply styling via the component theme or a CSS class. In M3, use `mat-flat-button` with variant theming in `styles.scss`.

---

### VIS-5 — Typography doesn't leverage Material type scale **[P2]**

**Problem:** The dashboard `h1` uses a custom `font-size: 1.5rem; font-weight: 500` instead of Material Design's type scale. This is inconsistent with the rest of the app's Material-based design.

**File:** `dashboard-page.component.ts:83-87`

**Fix:** Use Material's typography class:

```html
<h1 class="mat-headline-5">Dashboard</h1>
```

---

## Responsiveness

### RES-1 — Sidenav width occupies space on all screen sizes **[P0]**

*(See also NAV-1)* The 220px sidenav is always visible on all viewports. On a 375px mobile screen, this leaves only ~155px for the main content area — insufficient for cards, forms, or tables.

**Fix:** Resolved by the `BreakpointObserver` change in NAV-1.

---

### RES-2 — Dashboard fixed right-column width creates a poor mid-range experience **[P1]**

**Problem:** The dashboard grid uses `grid-template-columns: 1fr 380px`. Between the 900px breakpoint (where it collapses to 1 column) and ~1200px, the `1fr` column gets very narrow while the 380px column remains wide, creating visual imbalance.

**File:** `dashboard-page.component.ts:92`

**Fix:** Use proportional columns or introduce an intermediate breakpoint:

```scss
.dashboard-grid {
  grid-template-columns: 1fr 320px;
  @media (min-width: 1280px) {
    grid-template-columns: 1fr 380px;
  }
  @media (max-width: 900px) {
    grid-template-columns: 1fr;
  }
}
```

---

### RES-3 — Status summary cards can overflow on very small screens **[P2]**

**Problem:** Status cards use `flex: 1; min-width: 120px` which wraps correctly, but on a 320px screen, 5 cards at 120px min-width will all wrap individually — resulting in 5 rows of full-width cards, consuming excessive vertical space.

**File:** `status-summary-cards.component.ts:29-31`

**Fix:** Set `min-width` to a lower value (e.g., `100px`) and use a grid for more predictable layout:

```scss
.status-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(100px, 1fr));
  gap: 12px;
}
```

---

## Accessibility (WCAG 2.1 AA)

### A11Y-1 — Task card missing Space keydown handler **[P1]**

**Problem:** `TaskCardComponent` handles `(keydown.enter)` but not `(keydown.space)`. ARIA 1.2 specifies that elements with `role="button"` must respond to both `Enter` and `Space` keys. Failing to handle Space prevents keyboard users from activating task cards using the standard button interaction pattern (WCAG 2.1.1 — Keyboard).

**File:** `task-card.component.ts:25`

**Fix:**

```html
(keydown.enter)="taskClicked.emit(task())"
(keydown.space)="$event.preventDefault(); taskClicked.emit(task())"
```

`$event.preventDefault()` prevents page scrolling when Space is pressed.

---

### A11Y-2 — Color is the only differentiator for status/priority **[P1]**

**Problem:** Status summary cards use only a left-border color to indicate status, and the priority breakdown bar uses only segment color to distinguish priorities. This fails WCAG 1.4.1 (Use of Color) — information must not be conveyed by color alone.

**Files:** `status-summary-cards.component.ts:33`, `priority-breakdown.component.ts:17-28`

**Fix:**
- Add an icon inside each status summary card (e.g., `check_circle` for Done, `hourglass_empty` for In Progress).
- For the priority bar, the legend text already exists — ensure segments have accessible titles via `aria-label` (not just `title`, which is inaccessible to keyboard users and screen readers by default).

---

### A11Y-3 — Dashboard error state has poor color contrast in dark mode **[P1]**

**Problem:** The error container uses `color: rgba(0, 0, 0, 0.6)` with no dark theme override. On the dark background (`#121212`), this is approximately `rgba(0,0,0,0.6)` rendered over a dark surface, producing near-zero contrast — failing WCAG 1.4.3 (Contrast Minimum, 4.5:1 for normal text).

**File:** `dashboard-page.component.ts:78`

**Fix:**

```scss
.error-container {
  color: rgba(0, 0, 0, 0.6);
}
:host-context(body.dark-theme) .error-container {
  color: rgba(255, 255, 255, 0.7);
}
```

---

### A11Y-4 — Loading spinner has no accessible label **[P1]**

**Problem:** `<mat-spinner diameter="56" />` in the dashboard loading state provides no text alternative. Screen readers will announce a generic "progress bar" with no context. WCAG 4.1.2 (Name, Role, Value) requires perceivable state for UI components.

**File:** `dashboard-page.component.ts:27-29`

**Fix:**

```html
<div class="loading-container" role="status" aria-label="Loading dashboard…">
  <mat-spinner diameter="56" aria-hidden="true" />
</div>
```

---

### A11Y-5 — Password requirements section is not announced live **[P1]**

**Problem:** The password requirements checklist appears and updates dynamically as the user types, but there is no `aria-live` region. Screen reader users will not be informed that requirements have been met or failed without manually navigating to that area.

**File:** `register-page.component.ts:130`

**Fix:**

```html
<div class="password-requirements" aria-live="polite" aria-label="Password requirements">
```

---

### A11Y-6 — `empty-state` text invisible in dark mode **[P1]**

**Problem:** `RecentTasksListComponent` uses `color: rgba(0, 0, 0, 0.5)` for the empty state message. In dark mode, the text is rendered in near-black on a dark surface — effectively invisible. No `:host-context(body.dark-theme)` override is defined.

**File:** `recent-tasks-list.component.ts:41-44`

**Fix:**

```scss
.empty-state { color: rgba(0, 0, 0, 0.5); }
:host-context(body.dark-theme) .empty-state { color: rgba(255, 255, 255, 0.5); }
```

---

### A11Y-7 — Priority breakdown bar segments not keyboard-accessible **[P2]**

**Problem:** The `[attr.title]` on bar segments provides tooltip-like info, but `title` attributes are not reliably read by screen readers and are inaccessible to keyboard users. The parent has `role="img"` and `aria-label`, which is correct, but individual segment counts are not exposed.

**File:** `priority-breakdown.component.ts:22-27`

**Fix:** Embed the data in the `aria-label` of the parent `role="img"` element to provide full context:

```typescript
// Compute a summary string in the component
protected readonly ariaDescription = computed(() =>
  this.items().map(i => `${i.label}: ${i.count}`).join(', ')
);
```

```html
<div class="bar" role="img" [attr.aria-label]="'Priority distribution — ' + ariaDescription()">
```

---

### A11Y-8 — Missing `autocomplete` on confirm password field **[P2]**

**Problem:** The confirm password field uses `autocomplete="new-password"` which is correct, but both password fields share the same value — password managers may auto-fill both identically, masking the confirmation purpose. Browsers treat both as fill candidates.

**File:** `register-page.component.ts:156`

**Fix:** Set `autocomplete="new-password"` on the first field and `autocomplete="off"` on the confirm field to prevent password managers from auto-filling the confirmation.

---

## User Feedback — Loading, Error, Empty States

### UF-1 — Task navigation routes to a non-existent route **[P0]**

**Problem:** Clicking any task card calls `this.router.navigate(['/tasks', task.id])` (`dashboard-page.component.ts:133`). However, `app.routes.ts` defines **no `/tasks` route**. The wildcard route catches this URL and redirects to `/login`. The user is unexpectedly logged out when attempting to view task detail.

**File:** `dashboard-page.component.ts:133`, `app.routes.ts`

**Fix:** Register the tasks feature module in the router:

```typescript
{
  path: 'tasks',
  loadChildren: () =>
    import('./features/tasks/tasks.routes').then(m => m.TASKS_ROUTES),
}
```

Or, if the tasks feature is not yet built, disable the click handler and show a "Coming soon" tooltip until the route exists.

---

### UF-2 — Dashboard error state has no retry action **[P1]**

**Problem:** When `store.hasError()` is true, the dashboard shows an error message with no action. Users have no way to retry without performing a full page reload.

**File:** `dashboard-page.component.ts:30-33`

**Fix:**

```html
<div class="error-container">
  <mat-icon color="warn">error_outline</mat-icon>
  <p>{{ store.error() }}</p>
  <button mat-stroked-button color="primary" (click)="store.loadDashboard()">
    Try again
  </button>
</div>
```

---

### UF-3 — Empty state provides no call-to-action **[P2]**

**Problem:** The "No tasks yet." text in `RecentTasksListComponent` is a dead end. Users see it but have no affordance to create their first task. An empty state should guide the user toward the next action.

**File:** `recent-tasks-list.component.ts:22`

**Fix:**

```html
<div class="empty-state">
  <mat-icon>task_alt</mat-icon>
  <p>No tasks yet.</p>
  <button mat-stroked-button routerLink="/tasks/new">Create your first task</button>
</div>
```

---

### UF-4 — Full-page spinner blocks all content during dashboard load **[P2]**

**Problem:** The entire dashboard is replaced with a spinner during loading. This causes layout shift when content arrives and provides no sense of the page's structure. Perceived performance suffers.

**Fix:** Replace the full-page spinner with skeleton loaders that mirror the shape of the cards (using Angular Material's `MatProgressBarModule` in buffered mode, or a simple CSS shimmer pattern applied with Tailwind's `animate-pulse`).

---

### UF-5 — No visual feedback during form submission beyond spinner **[P2]**

**Problem:** When logging in or registering, the submit button shows a spinner but the button's text disappears entirely. There is no `aria-live` announcement that submission is in progress, leaving screen reader users without feedback.

**Files:** `login-page.component.ts:94-98`, `register-page.component.ts:172-176`

**Fix:** Wrap the loading area in an `aria-live="polite"` region and keep screen-reader-only text present:

```html
<button ... [disabled]="store.loading()" aria-describedby="submit-status">
  @if (store.loading()) { <mat-spinner diameter="20" aria-hidden="true" /> }
  @else { Sign in }
</button>
<span id="submit-status" aria-live="polite" class="sr-only">
  {{ store.loading() ? 'Signing in, please wait…' : '' }}
</span>
```

---

## Dark Theme

### DARK-1 — Active nav item background is invisible in dark mode **[P1]**

**Problem:** `.active-nav-item` uses `background-color: rgba(0, 0, 0, 0.08)`. On a dark sidebar surface, this dark tint is imperceptible — the active page indicator is invisible to dark-mode users.

**File:** `main-layout.component.ts:77-79`

**Fix:** Use a theme-aware token or a CSS custom property:

```scss
.active-nav-item {
  background-color: rgba(0, 0, 0, 0.08);
}
:host-context(body.dark-theme) .active-nav-item {
  background-color: rgba(255, 255, 255, 0.12);
}
```

---

### DARK-2 — Overdue badge and card border are hardcoded red **[P2]**

**Problem:** The overdue card's left border (`border-left: 4px solid #f44336`) and badge (`background-color: #f44336`) are hardcoded. While red is semantically appropriate for overdue tasks, the specific shade may not meet contrast requirements on all dark surfaces.

**File:** `overdue-tasks-section.component.ts:36-48`

**Fix:** Reference a CSS custom property for semantic danger color:

```scss
:root { --color-danger: #f44336; }
body.dark-theme { --color-danger: #ef9a9a; }
```

---

### DARK-3 — Font loaded from Google Fonts over HTTP in production **[P2]**

**Problem:** `index.html` loads Roboto and Material Icons from `fonts.googleapis.com`. This introduces a third-party network dependency, a potential privacy concern (IP logging by Google), and may fail in restricted environments. It also means the font renders only after the external stylesheet resolves.

**File:** `index.html:9-10`

**Fix:** Self-host fonts by installing `@fontsource/roboto` and using `@import` in `styles.scss`, eliminating the external dependency.

---

## Summary Table

| ID | Area | Severity | Title |
|----|------|----------|-------|
| NAV-1 | Navigation | P0 | Sidenav has no responsive behavior |
| VIS-1 | Visual Design | P0 | Task chip styles never applied — chips are unstyled |
| UF-1 | User Feedback | P0 | Task click navigates to non-existent `/tasks` route |
| NAV-2 | Navigation | P1 | No skip-to-main-content link (WCAG 2.4.1) |
| NAV-3 | Navigation | P1 | Logout button has no visible label |
| VIS-2 | Visual Design | P1 | Tailwind CSS declared but never used |
| VIS-3 | Visual Design | P1 | Hardcoded hex colors not theme-aware |
| RES-2 | Responsiveness | P1 | Fixed 380px dashboard column creates mid-range imbalance |
| A11Y-1 | Accessibility | P1 | Task card missing Space keydown handler |
| A11Y-2 | Accessibility | P1 | Color only used to distinguish status/priority (WCAG 1.4.1) |
| A11Y-3 | Accessibility | P1 | Dashboard error text invisible in dark mode |
| A11Y-4 | Accessibility | P1 | Loading spinner has no accessible label |
| A11Y-5 | Accessibility | P1 | Password requirements not announced via `aria-live` |
| A11Y-6 | Accessibility | P1 | Empty-state text invisible in dark mode |
| DARK-1 | Dark Theme | P1 | Active nav item background invisible in dark mode |
| UF-2 | User Feedback | P1 | Dashboard error state has no retry action |
| NAV-4 | Navigation | P2 | No 404 page — wildcard silently redirects to login |
| NAV-5 | Navigation | P2 | No theme toggle on auth layout |
| VIS-4 | Visual Design | P2 | `color="primary"` on buttons is deprecated in Material 3 |
| VIS-5 | Visual Design | P2 | Page heading bypasses Material type scale |
| RES-1 | Responsiveness | P0 | (Same as NAV-1) Sidenav occupies space on all sizes |
| RES-3 | Responsiveness | P2 | Status summary cards overflow on 320px screens |
| A11Y-7 | Accessibility | P2 | Priority bar segments not keyboard/SR accessible |
| A11Y-8 | Accessibility | P2 | Confirm password `autocomplete` value |
| UF-3 | User Feedback | P2 | Empty state has no call-to-action |
| UF-4 | User Feedback | P2 | Full-page spinner blocks dashboard structure |
| UF-5 | User Feedback | P2 | No `aria-live` feedback during form submission |
| DARK-2 | Dark Theme | P2 | Overdue colors hardcoded, not dark-mode adjusted |
| DARK-3 | Dark Theme | P2 | Fonts loaded from Google CDN — no self-hosting |

---

*Generated by Claude Code on 2026-03-05. Findings are based on static code analysis of the Angular component source files. Visual rendering and runtime behavior should be validated with browser DevTools, a screen reader (NVDA/VoiceOver), and automated tools (axe-core, Lighthouse).*

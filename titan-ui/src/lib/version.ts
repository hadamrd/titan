/**
 * Pinned Titan UI version for the 1.0.0-rc1 release window.
 *
 * Surface this anywhere the UI needs to render its own version (Settings "About
 * this Titan" card, support footer, etc.). A future shared version source (the
 * follow-up to release PR #43) can replace this constant — for the rc cycle a
 * single literal is enough and avoids a backend round-trip on a settings page
 * render. Bumped in lockstep with gradle.properties + Chart.yaml.
 */
export const TITAN_UI_VERSION = '1.0.0-rc1'

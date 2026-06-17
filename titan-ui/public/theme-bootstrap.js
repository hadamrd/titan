// No-FOUC theme bootstrap (#669). Loaded synchronously from <head> so
// `data-theme` is on <html> before the first paint. Mirrors apply() in
// src/components/TweaksPanel.tsx — kept tiny + dependency-free + CSP-safe
// (served from 'self', no inline script needed).
(function () {
  try {
    var raw = localStorage.getItem('titan.tweaks.v1');
    var theme = raw ? (JSON.parse(raw) || {}).theme : null;
    if (theme !== 'light' && theme !== 'dark') {
      theme = window.matchMedia('(prefers-color-scheme: dark)').matches
        ? 'dark'
        : 'light';
    }
    document.documentElement.setAttribute('data-theme', theme);
  } catch (e) {
    document.documentElement.setAttribute('data-theme', 'dark');
  }
})();

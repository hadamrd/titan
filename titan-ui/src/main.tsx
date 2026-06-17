import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider, createRouter } from '@tanstack/react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { routeTree } from './routeTree.gen'
import { AuthProvider } from './auth/AuthProvider'
import { initTweaks } from './components/TweaksPanel'

// Fonts: Geist + Geist Mono via @fontsource so the bundle is self-contained
// (no CDN call). Imports run at module load → the CSS is in the document
// before first paint, avoiding FOUT on the dashboard chrome.
import '@fontsource/geist/300.css'
import '@fontsource/geist/400.css'
import '@fontsource/geist/500.css'
import '@fontsource/geist/600.css'
import '@fontsource/geist/700.css'
import '@fontsource/geist-mono/400.css'
import '@fontsource/geist-mono/500.css'
import '@fontsource/geist-mono/600.css'

import './styles/globals.css'

// Apply persisted tweaks (theme/accent/density/sidebar/time-format) BEFORE
// the React tree mounts so the first paint already matches the user's prefs.
// initTweaks just writes data-* attributes + accent CSS vars on <html>.
initTweaks()

const queryClient = new QueryClient()

const router = createRouter({ routeTree })

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}

const rootElement = document.getElementById('root')!
createRoot(rootElement).render(
  <StrictMode>
    <AuthProvider>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </AuthProvider>
  </StrictMode>,
)

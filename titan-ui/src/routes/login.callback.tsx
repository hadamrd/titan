/**
 * OIDC redirect-callback route.
 *
 * The IdP redirects the browser to /login/callback?code=...&state=...; this
 * component finishes the Authorization-Code + PKCE exchange via
 * UserManager.signinRedirectCallback(), then navigates to /.
 */
import { createFileRoute, useNavigate } from '@tanstack/react-router'
import { useEffect, useRef, useState } from 'react'
import { useAuth } from '@/auth/AuthProvider'

export const Route = createFileRoute('/login/callback')({
  component: LoginCallbackPage,
})

function LoginCallbackPage() {
  const navigate = useNavigate()
  const { signinRedirectCallback } = useAuth()
  const [error, setError] = useState<string | null>(null)
  const ran = useRef(false)

  useEffect(() => {
    if (ran.current) return
    ran.current = true
    signinRedirectCallback()
      .then(() => {
        void navigate({ to: '/', replace: true })
      })
      .catch((err: unknown) => {
        setError(err instanceof Error ? err.message : String(err))
      })
  }, [signinRedirectCallback, navigate])

  return (
    <div className="flex min-h-[60vh] items-center justify-center text-sm text-muted-foreground">
      {error ? (
        <p role="alert" className="text-destructive">
          Sign-in failed: {error}
        </p>
      ) : (
        <p>Completing sign-in…</p>
      )}
    </div>
  )
}

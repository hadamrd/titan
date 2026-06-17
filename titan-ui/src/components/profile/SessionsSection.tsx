import { ComingSoon } from './ComingSoon'

export function SessionsSection() {
  return (
    <ComingSoon
      title="Active sessions"
      detail="Multi-session management lands with GET /api/v1/sessions. Until then, sign-out clears your local session and the IdP cookie."
    />
  )
}

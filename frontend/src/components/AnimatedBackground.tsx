/**
 * Soft, slowly-drifting color blobs behind the whole page. Blurred and low-opacity so
 * they read as ambient light, not visual noise — easy on the eyes. Pointer-events off
 * and aria-hidden so it never interferes with the UI.
 */
export default function AnimatedBackground() {
  return (
    <div aria-hidden className="pointer-events-none fixed inset-0 -z-10 overflow-hidden">
      <div
        className="animate-blob absolute -left-24 -top-32 h-[38rem] w-[38rem] rounded-full opacity-50 blur-3xl"
        style={{ background: 'radial-gradient(circle at center, var(--bg-tint-1), transparent 60%)' }}
      />
      <div
        className="animate-blob absolute -right-24 top-1/3 h-[34rem] w-[34rem] rounded-full opacity-40 blur-3xl [animation-delay:-6s]"
        style={{ background: 'radial-gradient(circle at center, var(--bg-tint-2), transparent 60%)' }}
      />
      <div
        className="animate-blob absolute -bottom-40 left-1/4 h-[32rem] w-[32rem] rounded-full opacity-40 blur-3xl [animation-delay:-12s]"
        style={{ background: 'radial-gradient(circle at center, var(--bg-tint-3), transparent 60%)' }}
      />
    </div>
  )
}

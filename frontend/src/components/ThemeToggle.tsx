import { useTheme } from '../theme/ThemeProvider'

/**
 * Sliding sun/moon switch. The knob glides across the track and swaps its glyph;
 * the page-wide sunrise/moonrise plays in the background via the `.dark` class.
 */
export default function ThemeToggle() {
  const { theme, toggle } = useTheme()
  const isDark = theme === 'dark'

  return (
    <button
      type="button"
      role="switch"
      aria-checked={isDark}
      onClick={toggle}
      aria-label={isDark ? 'Switch to light theme' : 'Switch to dark theme'}
      className="surface relative inline-flex h-6 w-[44px] items-center rounded-full p-[2px] transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-[color:var(--ring)]"
    >
      {/* Fixed track hints: sun on the left, moon on the right. */}
      <span className="pointer-events-none absolute inset-0 flex items-center justify-between px-[5px] text-faint">
        <svg width="9" height="9" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" aria-hidden>
          <circle cx="12" cy="12" r="4.5" />
          <path d="M12 2.5v2M12 19.5v2M4.4 4.4l1.4 1.4M18.2 18.2l1.4 1.4M2.5 12h2M19.5 12h2M4.4 19.6l1.4-1.4M18.2 5.8l1.4-1.4" />
        </svg>
        <svg width="9" height="9" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
          <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z" />
        </svg>
      </span>

      {/* Sliding knob. */}
      <span
        className="relative z-10 grid h-5 w-5 place-items-center rounded-full text-white shadow-[var(--shadow-sm)] transition-transform duration-300 ease-[cubic-bezier(.22,1,.36,1)]"
        style={{
          transform: isDark ? 'translateX(20px)' : 'translateX(0)',
          background: isDark
            ? 'radial-gradient(circle at 35% 30%, #f5f7ff, #b9c4e0 90%)'
            : 'radial-gradient(circle at 35% 30%, #ffe0b0, #ff9d4d 55%, var(--spark))',
        }}
      >
        {isDark ? (
          <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="#3a4568" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
            <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z" />
          </svg>
        ) : (
          <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="#fff" strokeWidth="2.2" strokeLinecap="round" aria-hidden>
            <circle cx="12" cy="12" r="4.5" />
            <path d="M12 2.5v2M12 19.5v2M4.4 4.4l1.4 1.4M18.2 18.2l1.4 1.4M2.5 12h2M19.5 12h2M4.4 19.6l1.4-1.4M18.2 5.8l1.4-1.4" />
          </svg>
        )}
      </span>
    </button>
  )
}

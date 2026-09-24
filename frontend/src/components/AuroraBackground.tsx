/**
 * Ambient backdrop + a realistic celestial cycle, dead-center on screen.
 *
 * Light: a glowing sun rests at center. Toggle to dark and the sun SETS along a
 * natural diagonal toward the horizon while the moon RISES along the same path
 * into the exact spot the sun held — same size, same place. In dark, soft clouds
 * flank the moon, lit on the moon-facing edge and pitch-dark on the far side.
 * The sun/moon are hand-built realistic SVGs. All CSS-transition driven off the
 * `.dark` class, so it tweens smoothly (no JS).
 */
export default function AuroraBackground() {
  return (
    <div aria-hidden className="pointer-events-none fixed inset-0 -z-10 overflow-hidden">
      <div className="celestial">
        {/* Sun */}
        <div className="sun">
          <SunSvg />
        </div>

        {/* Moon + its clouds rise together as one group */}
        <div className="moon-group">
          <div className="cloud cloud-a" />
          <div className="cloud cloud-b" />
          <div className="moon">
            <MoonSvg />
          </div>
        </div>
      </div>

      {/* Faint neutral glow, lower area — keeps the field from going flat. */}
      <div
        className="absolute bottom-[-20rem] left-1/2 h-[34rem] w-[34rem] -translate-x-1/2 rounded-full blur-[130px]"
        style={{ background: `radial-gradient(circle at center, var(--glow-2), transparent 70%)` }}
      />
    </div>
  )
}

/** Realistic sun: white-hot core → amber → ember, wrapped in a soft warm corona. */
function SunSvg() {
  return (
    <svg viewBox="0 0 120 120" style={{ width: '100%', height: '100%', overflow: 'visible', display: 'block' }}>
      <defs>
        <radialGradient id="sunCore" cx="42%" cy="36%" r="68%">
          <stop offset="0%" stopColor="#fffdf5" />
          <stop offset="28%" stopColor="#ffe7a6" />
          <stop offset="58%" stopColor="#ffb457" />
          <stop offset="84%" stopColor="#ff8a3d" />
          <stop offset="100%" stopColor="#f2603a" />
        </radialGradient>
        <radialGradient id="sunGlow" cx="50%" cy="50%" r="50%">
          <stop offset="0%" stopColor="#ff9a46" stopOpacity="0.55" />
          <stop offset="45%" stopColor="#ff783c" stopOpacity="0.26" />
          <stop offset="100%" stopColor="#ff783c" stopOpacity="0" />
        </radialGradient>
        <filter id="sunSoft" x="-50%" y="-50%" width="200%" height="200%">
          <feGaussianBlur stdDeviation="0.6" />
        </filter>
      </defs>
      <circle cx="60" cy="60" r="58" fill="url(#sunGlow)" />
      <circle cx="60" cy="60" r="30" fill="url(#sunCore)" filter="url(#sunSoft)" />
    </svg>
  )
}

/** Realistic moon: shaded sphere (light top-left), craters + maria, cool halo. */
function MoonSvg() {
  return (
    <svg viewBox="0 0 120 120" style={{ width: '100%', height: '100%', overflow: 'visible', display: 'block' }}>
      <defs>
        <radialGradient id="moonBody" cx="38%" cy="33%" r="74%">
          <stop offset="0%" stopColor="#fbfcff" />
          <stop offset="45%" stopColor="#dbe1ef" />
          <stop offset="78%" stopColor="#b9c2da" />
          <stop offset="100%" stopColor="#98a4c2" />
        </radialGradient>
        <radialGradient id="moonGlow" cx="50%" cy="50%" r="50%">
          <stop offset="0%" stopColor="#becaf0" stopOpacity="0.5" />
          <stop offset="50%" stopColor="#aabee6" stopOpacity="0.18" />
          <stop offset="100%" stopColor="#aabee6" stopOpacity="0" />
        </radialGradient>
        <radialGradient id="craterG" cx="40%" cy="34%" r="72%">
          <stop offset="0%" stopColor="#aab3ce" />
          <stop offset="100%" stopColor="#7f8bab" />
        </radialGradient>
        <clipPath id="moonClip">
          <circle cx="60" cy="60" r="30" />
        </clipPath>
      </defs>
      <circle cx="60" cy="60" r="56" fill="url(#moonGlow)" />
      <circle cx="60" cy="60" r="30" fill="url(#moonBody)" />
      <g clipPath="url(#moonClip)">
        {/* terminator: subtle shadow on the lower-right for a spherical read */}
        <circle cx="76" cy="72" r="34" fill="#46547a" fillOpacity="0.16" />
        {/* maria (dark plains) */}
        <ellipse cx="66" cy="52" rx="9" ry="7" fill="#8b96b6" fillOpacity="0.35" />
        {/* craters */}
        <circle cx="49" cy="45" r="6" fill="url(#craterG)" />
        <circle cx="49" cy="45" r="6" fill="none" stroke="#f2f4fb" strokeOpacity="0.35" strokeWidth="0.6" />
        <circle cx="70" cy="63" r="4" fill="url(#craterG)" />
        <circle cx="52" cy="71" r="3.4" fill="url(#craterG)" />
        <circle cx="63" cy="39" r="2.4" fill="url(#craterG)" />
        <circle cx="43" cy="59" r="2.8" fill="url(#craterG)" />
        <circle cx="58" cy="66" r="1.8" fill="url(#craterG)" />
      </g>
    </svg>
  )
}

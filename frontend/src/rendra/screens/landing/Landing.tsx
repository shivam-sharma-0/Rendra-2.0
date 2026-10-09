import CallToAction from './CallToAction'
import Hero from './Hero'
import HowItWorks from './HowItWorks'
import LandingFooter from './LandingFooter'
import LandingNav from './LandingNav'
import TemplatesMarquee from './TemplatesMarquee'
import { useStartProject } from './useStartProject'
import './landing.css'

/** Public marketing page at `/` (also shown behind the auth dialog). */
export default function Landing() {
  const { goToStart } = useStartProject()

  return (
    <div className="lp">
      <div className="lp-inner">
        <LandingNav onStart={goToStart} />
        <main>
          <Hero />
          <HowItWorks />
          <TemplatesMarquee />
          <CallToAction onStart={goToStart} />
        </main>
        <LandingFooter />
      </div>
    </div>
  )
}

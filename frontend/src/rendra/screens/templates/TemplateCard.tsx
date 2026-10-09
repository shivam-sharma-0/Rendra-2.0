import { NeuButton, NeuPanel } from '../../../components/neu'
import MiniPreview from '../../ui/MiniPreview'
import type { Template } from './content'

type Props = { template: Template; busy: boolean; onUse: () => void }

/** A template: its preview, name and kind, and a "Use template" button. */
export default function TemplateCard({ template, busy, onUse }: Props) {
  return (
    <NeuPanel as="article" size="sm" interactive className="tp-card" aria-label={template.name}>
      <div className="tp-card__well neu-well neu-well--deep"><MiniPreview colours={template.colours} /></div>
      <div className="tp-card__meta">
        <span className="tp-card__text"><b>{template.name}</b><small>{template.kind}</small></span>
        <NeuButton size="sm" pill disabled={busy} onClick={onUse}>Use template</NeuButton>
      </div>
    </NeuPanel>
  )
}

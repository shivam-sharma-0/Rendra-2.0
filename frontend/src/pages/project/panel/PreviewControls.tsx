import type { Device } from '../workspaceTypes'
import { AppIcon } from '../../../components/icons'
import { WS_DEVICES } from './tabs'

type PreviewControlsProps = {
  device: Device
  onDevice: (device: Device) => void
  onReload: () => void
  /** The address the preview shows, for "open in a new tab" (none while it is not available). */
  url: string | null
}

/**
 * The preview's controls in the panel header: a size button (its icon is the current size; each
 * click moves to the next — desktop, tablet, mobile, then back) and the address pill — reload, the
 * page shown, open in a new tab.
 */
export function PreviewControls({ device, onDevice, onReload, url }: PreviewControlsProps) {
  const index = Math.max(0, WS_DEVICES.findIndex((d) => d.id === device))
  const current = WS_DEVICES[index]
  const next = WS_DEVICES[(index + 1) % WS_DEVICES.length]
  const sizeLabel = `Preview size: ${current.label} (click for ${next.label})`

  return (
    <>
      <button type="button" className="ws-flat-btn" aria-label={sizeLabel} title={sizeLabel} onClick={() => onDevice(next.id)}>
        <AppIcon name={current.icon} size={16} />
      </button>

      <div className="ws-url">
        <button type="button" aria-label="Reload preview" title="Reload preview" onClick={onReload}>
          <AppIcon name="reload" size={16} />
        </button>
        <span title="The preview opens on the app's home page">Homepage</span>
        {url
          ? <a href={url} target="_blank" rel="noopener noreferrer" aria-label="Open the preview in a new tab" title="Open in a new tab"><AppIcon name="openExternal" size={16} /></a>
          : <i aria-hidden />}
      </div>
    </>
  )
}

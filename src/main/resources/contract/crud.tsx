import { useEffect, useMemo, useState, useSyncExternalStore, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ArrowUpDown, CalendarDays, ChevronRight, CircleCheck, Clock, Hash, Image as ImageIcon, LayoutGrid, List, Pencil, Plus, Tag, Trash2, type LucideIcon } from 'lucide-react'
import { Badge, Button, EmptyState, FilterChips, Modal, Notice, SearchInput, SelectField, TextArea, TextField, toast, type Tone } from '{{kit}}'

export type FieldKind = 'string' | 'text' | 'number' | 'boolean' | 'date' | 'enum' | 'image'
export type FieldSpec = { name: string; label: string; kind: FieldKind; required: boolean; options?: string[]; tones?: Record<string, Tone> }
export type Source<T, I> = {
  list: (query?: Record<string, string>) => Promise<T[]>
  get: (id: string) => Promise<T>
  create: (input: I) => Promise<T>
  update: (id: string, input: Partial<I>) => Promise<T>
  remove: (id: string) => Promise<unknown>
}
export type EntitySpec<T extends { id: string }, I> = {
  label: string
  pluralLabel: string
  plural: string
  icon: LucideIcon
  layout: 'grid' | 'table'
  recordPath: string
  listPath?: string
  display: string
  fields: FieldSpec[]
  search: boolean
  filter?: string
  canCreate: () => boolean
  canEdit: (record: T) => boolean
  watch?: (notify: () => void) => () => void
  source: Source<T, I>
}
type Values = Record<string, unknown>
const still = () => () => {}
const NARROW = '(max-width: 640px)'
const narrowNow = () => typeof window !== 'undefined' && typeof window.matchMedia === 'function' && window.matchMedia(NARROW).matches
function watchNarrow(notify: () => void) {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') return () => {}
  const media = window.matchMedia(NARROW)
  media.addEventListener('change', notify)
  return () => media.removeEventListener('change', notify)
}
const PAGE = 24
const KIND_ICONS: Record<FieldKind, LucideIcon> = { string: Tag, text: Tag, number: Hash, boolean: CircleCheck, date: CalendarDays, enum: Tag, image: ImageIcon }
const UNITS: [Intl.RelativeTimeFormatUnit, number][] = [['year', 31536000], ['month', 2592000], ['week', 604800], ['day', 86400], ['hour', 3600], ['minute', 60]]

export function fieldValue(record: unknown, name: string): unknown {
  return record && typeof record === 'object' ? (record as Values)[name] : undefined
}

export function optionLabel(option: string): string {
  return option.charAt(0).toUpperCase() + option.slice(1)
}

export function relativeTime(value: unknown): string {
  const time = Date.parse(String(value ?? ''))
  if (Number.isNaN(time)) return ''
  const seconds = (time - Date.now()) / 1000
  const format = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' })
  for (const [unit, size] of UNITS) if (Math.abs(seconds) >= size) return format.format(Math.round(seconds / size), unit)
  return 'just now'
}

export function formatValue(field: FieldSpec | undefined, value: unknown): string {
  if (value === undefined || value === null || value === '') return '—'
  if (field?.kind === 'boolean') return value === true ? 'Yes' : 'No'
  if (field?.kind === 'enum') return optionLabel(String(value))
  if (field?.kind === 'number' && typeof value === 'number') return value.toLocaleString()
  if (field?.kind === 'date') {
    const date = new Date(`${String(value).slice(0, 10)}T00:00:00`)
    if (!Number.isNaN(date.getTime())) return date.toLocaleDateString(undefined, { day: 'numeric', month: 'short', year: 'numeric' })
  }
  return String(value)
}

export function toneOf<T extends { id: string }, I>(spec: EntitySpec<T, I>, record: unknown): Tone {
  const field = spec.fields.find(candidate => candidate.name === spec.filter && candidate.kind === 'enum')
    ?? spec.fields.find(candidate => candidate.kind === 'enum')
  const value = field ? fieldValue(record, field.name) : undefined
  return field && typeof value === 'string' ? field.tones?.[value] ?? 'accent' : 'accent'
}

function present(value: unknown): boolean {
  return value !== undefined && value !== null && value !== ''
}

function today(): string {
  const now = new Date()
  return new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().slice(0, 10)
}

function initialValues(fields: FieldSpec[], record?: unknown): Values {
  return Object.fromEntries(fields.map(field => {
    const value = fieldValue(record, field.name)
    if (value !== undefined && value !== null) return [field.name, field.kind === 'number' ? String(value) : value]
    if (field.kind === 'boolean') return [field.name, false]
    if (!field.required) return [field.name, '']
    if (field.kind === 'enum') return [field.name, field.options?.[0] ?? '']
    if (field.kind === 'date') return [field.name, today()]
    if (field.kind === 'number') return [field.name, '0']
    return [field.name, '']
  }))
}

function problems(fields: FieldSpec[], values: Values): Record<string, string> {
  const found: Record<string, string> = {}
  for (const field of fields) {
    if (field.kind === 'boolean') continue
    const text = String(values[field.name] ?? '').trim()
    if (field.required && text === '') found[field.name] = `${field.label} is required`
    else if (field.kind === 'number' && text !== '' && !Number.isFinite(Number(text))) found[field.name] = `${field.label} must be a number`
    else if (field.kind === 'image' && text !== '' && !/^https?:\/\/\S+$/i.test(text)) found[field.name] = `${field.label} must be a web address starting with https://`
  }
  return found
}

function toInput(fields: FieldSpec[], values: Values): Values {
  const input: Values = {}
  for (const field of fields) {
    const value = values[field.name]
    if (field.kind === 'boolean') { input[field.name] = value === true; continue }
    const text = String(value ?? '').trim()
    if (text === '') { if (!field.required) input[field.name] = null; continue }
    input[field.name] = field.kind === 'number' ? Number(text) : text
  }
  return input
}

function message(failure: unknown, fallback: string): string {
  const status = failure instanceof Error ? failure.message.match(/\((\d{3})\)/)?.[1] : undefined
  if (status === '403') return 'Only admins can make this change.'
  if (status === '404') return 'This item no longer exists.'
  if (status === '400') return 'Some values are not valid. Check the fields and try again.'
  return fallback
}

export function RecordForm<T extends { id: string }, I>({ spec, record, busy, error, submitLabel, onSubmit, onCancel }: {
  spec: EntitySpec<T, I>; record?: T; busy: boolean; error?: string; submitLabel: string
  onSubmit: (input: I) => void; onCancel?: () => void
}) {
  const [values, setValues] = useState<Values>(() => initialValues(spec.fields, record))
  const [errors, setErrors] = useState<Record<string, string>>({})
  const set = (name: string, value: unknown) => setValues(current => ({ ...current, [name]: value }))
  function submit(event: FormEvent) {
    event.preventDefault()
    const found = problems(spec.fields, values)
    setErrors(found)
    if (Object.keys(found).length === 0) onSubmit(toInput(spec.fields, values) as I)
  }
  return <form className="app-stack" onSubmit={submit} noValidate>
    {error && <Notice tone="danger">{error}</Notice>}
    {spec.fields.map(field => {
      const id = `${spec.plural}-${field.name}`
      const value = values[field.name]
      const shared = { id, name: field.name, label: field.required ? field.label : `${field.label} (optional)`, error: errors[field.name], required: field.required }
      if (field.kind === 'boolean') return <label key={field.name} className="app-check">
        <input id={id} type="checkbox" checked={value === true} onChange={event => set(field.name, event.target.checked)} />
        {field.label}
      </label>
      if (field.kind === 'text') return <TextArea key={field.name} {...shared} rows={4} maxLength={5000}
        value={String(value ?? '')} onChange={event => set(field.name, event.target.value)} />
      if (field.kind === 'enum') return <SelectField key={field.name} {...shared} value={String(value ?? '')}
        onChange={event => set(field.name, event.target.value)}
        options={[...(field.required ? [] : [{ value: '', label: 'Not set' }]), ...(field.options ?? []).map(option => ({ value: option, label: optionLabel(option) }))]} />
      return <TextField key={field.name} {...shared}
        type={field.kind === 'number' ? 'number' : field.kind === 'date' ? 'date' : field.kind === 'image' ? 'url' : 'text'}
        step={field.kind === 'number' ? 'any' : undefined} maxLength={field.kind === 'string' ? 200 : field.kind === 'image' ? 2000 : undefined}
        placeholder={field.kind === 'image' ? 'https://' : undefined}
        value={String(value ?? '')} onChange={event => set(field.name, event.target.value)} />
    })}
    <div className="app-actions">
      <Button type="submit" id={`${spec.plural}-save`} disabled={busy}>{busy ? 'Saving…' : submitLabel}</Button>
      {onCancel && <Button variant="ghost" onClick={onCancel} disabled={busy}>Cancel</Button>}
    </div>
  </form>
}

function Facts<T extends { id: string }, I>({ spec, record }: { spec: EntitySpec<T, I>; record: T }) {
  const facts = spec.fields.filter(field => field.name !== spec.display && !['text', 'image', 'string'].includes(field.kind)
    && present(fieldValue(record, field.name))).slice(0, 3)
  return <>
    {facts.map(field => field.kind === 'enum'
      ? <Badge key={field.name} tone={field.tones?.[String(fieldValue(record, field.name))] ?? 'accent'}>{formatValue(field, fieldValue(record, field.name))}</Badge>
      : field.kind === 'boolean'
        ? <Badge key={field.name} tone={fieldValue(record, field.name) === true ? 'success' : 'neutral'}>{field.label}: {formatValue(field, fieldValue(record, field.name))}</Badge>
        : <span key={field.name} className="app-meta">{field.kind === 'date' ? <CalendarDays size={15} /> : <Hash size={15} />}{field.label} {formatValue(field, fieldValue(record, field.name))}</span>)}
  </>
}

function RecordCard<T extends { id: string }, I>({ spec, record, onOpen }: { spec: EntitySpec<T, I>; record: T; onOpen: () => void }) {
  const Icon = spec.icon
  const display = spec.fields.find(field => field.name === spec.display)
  const summary = spec.fields.find(field => field.kind === 'text' && field.name !== spec.display)
  const subtitle = spec.fields.find(field => field.kind === 'string' && field.name !== spec.display)
  const image = spec.fields.find(field => field.kind === 'image' && present(fieldValue(record, field.name)))
  const updated = relativeTime(fieldValue(record, 'updatedAt'))
  return <button type="button" className={`app-card app-card--interactive app-record app-tone--${toneOf(spec, record)}`} onClick={onOpen}>
    {image && <img className="app-record__cover" src={String(fieldValue(record, image.name))} alt="" loading="lazy"
      onError={event => { event.currentTarget.style.display = 'none' }} />}
    <div className="app-record__head">
      <span className="app-icon-tile"><Icon size={20} aria-hidden="true" /></span>
      <span className="app-record__title">
        <h3>{formatValue(display, fieldValue(record, spec.display))}</h3>
        {subtitle && present(fieldValue(record, subtitle.name)) && <span className="app-meta">{formatValue(subtitle, fieldValue(record, subtitle.name))}</span>}
      </span>
      <ChevronRight className="app-record__chevron" size={18} aria-hidden="true" />
    </div>
    {summary && present(fieldValue(record, summary.name)) && <p className="app-muted app-clamp">{String(fieldValue(record, summary.name))}</p>}
    <div className="app-record__meta">
      <Facts spec={spec} record={record} />
      {updated && <span className="app-meta"><Clock size={15} aria-hidden="true" />{updated}</span>}
    </div>
  </button>
}

function RecordTable<T extends { id: string }, I>({ spec, records, onOpen }: { spec: EntitySpec<T, I>; records: T[]; onOpen: (record: T) => void }) {
  const Icon = spec.icon
  const columns = spec.fields.filter(field => field.name !== spec.display && field.kind !== 'text' && field.kind !== 'image').slice(0, 4)
  return <div className="app-table-wrap" data-records>
    <table>
      <thead><tr><th>{spec.fields.find(field => field.name === spec.display)?.label}</th>{columns.map(field => <th key={field.name}>{field.label}</th>)}<th>Updated</th></tr></thead>
      <tbody>
        {records.map(record => <tr key={record.id} className="app-row--interactive" onClick={() => onOpen(record)}>
          <td><span className={`app-record__head app-tone--${toneOf(spec, record)}`}>
            <span className="app-icon-tile"><Icon size={16} aria-hidden="true" /></span>
            <strong>{formatValue(spec.fields.find(field => field.name === spec.display), fieldValue(record, spec.display))}</strong>
          </span></td>
          {columns.map(field => <td key={field.name}>{field.kind === 'enum' && present(fieldValue(record, field.name))
            ? <Badge tone={field.tones?.[String(fieldValue(record, field.name))] ?? 'accent'}>{formatValue(field, fieldValue(record, field.name))}</Badge>
            : formatValue(field, fieldValue(record, field.name))}</td>)}
          <td className="app-muted">{relativeTime(fieldValue(record, 'updatedAt'))}</td>
        </tr>)}
      </tbody>
    </table>
  </div>
}

function readView(key: string, fallback: 'grid' | 'table'): 'grid' | 'table' {
  try {
    const value = window.localStorage.getItem(key)
    return value === 'grid' || value === 'table' ? value : fallback
  } catch {
    return fallback
  }
}

export function RecordList<T extends { id: string }, I>({ spec, title, description }: {
  spec: EntitySpec<T, I>; title?: string; description?: string
}) {
  const navigate = useNavigate()
  const [query, setQuery] = useState('')
  const [choice, setChoice] = useState('')
  const [sort, setSort] = useState('newest')
  const [view, setView] = useState<'grid' | 'table'>(() => readView(`rendra-view:${spec.plural}`, spec.layout))
  const [shown, setShown] = useState(PAGE)
  const [records, setRecords] = useState<T[] | null>(null)
  const [error, setError] = useState('')
  const [creating, setCreating] = useState(false)
  const [busy, setBusy] = useState(false)
  const [formError, setFormError] = useState('')
  const [version, setVersion] = useState(0)
  const allowed = useSyncExternalStore(spec.watch ?? still, spec.canCreate, spec.canCreate)
  const narrow = useSyncExternalStore(watchNarrow, narrowNow, () => false)
  const filter = spec.fields.find(field => field.name === spec.filter)
  const display = spec.fields.find(field => field.name === spec.display)
  const numbers = spec.fields.filter(field => field.kind === 'number')
  useEffect(() => {
    let active = true
    const timer = window.setTimeout(() => {
      spec.source.list(query.trim() ? { q: query.trim() } : {})
        .then(rows => { if (active) { setRecords(rows); setError('') } })
        .catch(() => { if (active) setError(`Could not load ${spec.pluralLabel.toLowerCase()}. Please try again.`) })
    }, query ? 250 : 0)
    return () => { active = false; window.clearTimeout(timer) }
  }, [query, version, spec])
  const visible = useMemo(() => {
    const rows = (records ?? []).filter(record => !filter || choice === '' || String(fieldValue(record, filter.name)) === choice)
    const text = (record: T, name: string) => String(fieldValue(record, name) ?? '')
    return [...rows].sort((first, second) => {
      if (sort === 'oldest') return text(first, 'createdAt').localeCompare(text(second, 'createdAt'))
      if (sort === 'name') return text(first, spec.display).localeCompare(text(second, spec.display))
      if (sort.startsWith('number:')) return Number(fieldValue(second, sort.slice(7)) ?? -Infinity) - Number(fieldValue(first, sort.slice(7)) ?? -Infinity)
      return text(second, 'createdAt').localeCompare(text(first, 'createdAt'))
    })
  }, [records, filter, choice, sort, spec.display])
  const counts = useMemo(() => {
    const result = new Map<string, number>()
    for (const record of records ?? []) {
      const value = filter ? String(fieldValue(record, filter.name)) : ''
      result.set(value, (result.get(value) ?? 0) + 1)
    }
    return result
  }, [records, filter])
  async function create(input: I) {
    setBusy(true)
    setFormError('')
    try {
      await spec.source.create(input)
      setCreating(false)
      setVersion(value => value + 1)
      toast(`${spec.label} created`)
    } catch (failure) {
      setFormError(message(failure, `Could not save this ${spec.label.toLowerCase()}. Please try again.`))
    } finally {
      setBusy(false)
    }
  }
  const choose = (next: 'grid' | 'table') => {
    setView(next)
    try { window.localStorage.setItem(`rendra-view:${spec.plural}`, next) } catch { return }
  }
  const open = () => { setFormError(''); setCreating(true) }
  const Icon = spec.icon
  const options = !filter ? [] : [{ value: '', label: `All ${records?.length ?? ''}`.trim() }, ...(filter.kind === 'boolean'
    ? [{ value: 'true', label: `Yes ${counts.get('true') ?? 0}` }, { value: 'false', label: `No ${counts.get('false') ?? 0}` }]
    : (filter.options ?? []).map(option => ({ value: option, label: `${optionLabel(option)} ${counts.get(option) ?? 0}` })))]
  const narrowed = query.trim() !== '' || choice !== ''
  const newButton = <Button id={`${spec.plural}-new`} onClick={open}><Plus size={18} aria-hidden="true" />New {spec.label.toLowerCase()}</Button>
  return <div className="app-stack" data-crud={spec.plural}>
    <header className="app-page-header app-fade-in">
      <div className="app-stack">
        <h1>{title ?? spec.pluralLabel}{records && <span className="app-count">{records.length}</span>}</h1>
        {description && <p className="app-lead app-muted">{description}</p>}
      </div>
      {allowed && <div className="app-actions">{newButton}</div>}
    </header>
    <div className="app-toolbar">
      {spec.search && <SearchInput label={`Search ${spec.pluralLabel.toLowerCase()}`} value={query} onChange={event => { setQuery(event.target.value); setShown(PAGE) }} />}
      <div className="app-toolbar__end">
        <label className="app-sort">
          <ArrowUpDown size={16} aria-hidden="true" />
          <span className="app-visually-hidden">Sort</span>
          <select value={sort} onChange={event => setSort(event.target.value)}>
            <option value="newest">Newest first</option>
            <option value="oldest">Oldest first</option>
            <option value="name">{display?.label ?? 'Name'} A–Z</option>
            {numbers.map(field => <option key={field.name} value={`number:${field.name}`}>Highest {field.label.toLowerCase()}</option>)}
          </select>
        </label>
        <div className="app-segmented" role="group" aria-label="View">
          <button type="button" aria-label="Cards" aria-pressed={view === 'grid'} onClick={() => choose('grid')}><LayoutGrid size={17} /></button>
          <button type="button" aria-label="Table" aria-pressed={view === 'table'} onClick={() => choose('table')}><List size={17} /></button>
        </div>
      </div>
    </div>
    {filter && <FilterChips options={options} value={choice} onChange={value => { setChoice(value); setShown(PAGE) }} label={filter.label} />}
    {error && <Notice tone="danger" title="Something went wrong">{error}</Notice>}
    {records === null
      ? !error && <div className="app-grid" aria-busy="true" aria-label="Loading">
        {Array.from({ length: 6 }, (_, index) => <div key={index} className="app-card app-skeleton app-skeleton-card"><span /><span /><span /></div>)}
      </div>
      : visible.length === 0
        ? <EmptyState icon={<Icon size={24} />} title={narrowed ? `No matching ${spec.pluralLabel.toLowerCase()}` : `No ${spec.pluralLabel.toLowerCase()} yet`}
          description={narrowed ? 'Try a different search or filter.' : allowed ? `Create the first ${spec.label.toLowerCase()} to get started.` : 'New entries will appear here.'}
          action={!narrowed && allowed ? newButton : undefined} />
        : view === 'table' && !narrow
          ? <RecordTable spec={spec} records={visible.slice(0, shown)} onOpen={record => navigate(`${spec.recordPath}/${record.id}`)} />
          : <div className="app-grid" data-records>
            {visible.slice(0, shown).map(record => <RecordCard key={record.id} spec={spec} record={record} onOpen={() => navigate(`${spec.recordPath}/${record.id}`)} />)}
          </div>}
    {visible.length > shown && <div className="app-actions" style={{ justifyContent: 'center' }}>
      <Button variant="secondary" onClick={() => setShown(value => value + PAGE)}>Show more ({visible.length - shown} left)</Button>
    </div>}
    <Modal open={creating} title={`New ${spec.label.toLowerCase()}`} onClose={() => setCreating(false)}>
      {creating && <RecordForm spec={spec} busy={busy} error={formError} submitLabel={`Create ${spec.label.toLowerCase()}`}
        onSubmit={create} onCancel={() => setCreating(false)} />}
    </Modal>
  </div>
}

export function RecordDetail<T extends { id: string }, I>({ spec, id }: { spec: EntitySpec<T, I>; id: string }) {
  const navigate = useNavigate()
  const [record, setRecord] = useState<T | null>(null)
  const [state, setState] = useState<'loading' | 'ready' | 'missing' | 'error'>('loading')
  const [editing, setEditing] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)
  const [formError, setFormError] = useState('')
  const [version, setVersion] = useState(0)
  const allowed = () => record !== null && spec.canEdit(record)
  const editable = useSyncExternalStore(spec.watch ?? still, allowed, allowed)
  useEffect(() => {
    let active = true
    setState('loading')
    spec.source.get(id)
      .then(found => { if (active) { setRecord(found); setState('ready') } })
      .catch((failure: unknown) => { if (active) setState(String(failure).includes('404') ? 'missing' : 'error') })
    return () => { active = false }
  }, [id, spec, version])
  const leave = () => { if (spec.listPath) navigate(spec.listPath); else navigate(-1) }
  const Icon = spec.icon
  const crumbs = (current: string) => <nav className="app-breadcrumbs" aria-label="Breadcrumb">
    {spec.listPath ? <Link to={spec.listPath}>{spec.pluralLabel}</Link> : <Button variant="ghost" size="sm" onClick={leave}>Back</Button>}
    <ChevronRight size={15} aria-hidden="true" />
    <span>{current}</span>
  </nav>
  if (state === 'loading') return <div className="app-stack" aria-busy="true">
    {crumbs('Loading…')}
    <div className="app-card app-skeleton app-skeleton-card"><span /><span /><span /></div>
  </div>
  if (state !== 'ready' || !record) return <div className="app-stack">
    {crumbs(spec.label)}
    <EmptyState icon={<Icon size={24} />} title={state === 'error' ? `Could not load this ${spec.label.toLowerCase()}` : `This ${spec.label.toLowerCase()} is not available`}
      description={state === 'error' ? 'Please try again in a moment.' : 'It may have been deleted.'}
      action={state === 'error' ? <Button onClick={() => setVersion(value => value + 1)}>Try again</Button> : <Button variant="secondary" onClick={leave}>Back to {spec.pluralLabel.toLowerCase()}</Button>} />
  </div>
  async function save(input: I) {
    setBusy(true)
    setFormError('')
    try {
      setRecord(await spec.source.update(id, input))
      setEditing(false)
      toast('Changes saved')
    } catch (failure) {
      setFormError(message(failure, 'Could not save your changes. Please try again.'))
    } finally {
      setBusy(false)
    }
  }
  async function remove() {
    setBusy(true)
    try {
      await spec.source.remove(id)
      setConfirming(false)
      toast(`${spec.label} deleted`)
      leave()
    } catch (failure) {
      setConfirming(false)
      toast(message(failure, `Could not delete this ${spec.label.toLowerCase()}. Please try again.`), 'danger')
    } finally {
      setBusy(false)
    }
  }
  const display = spec.fields.find(field => field.name === spec.display)
  const title = formatValue(display, fieldValue(record, spec.display))
  const image = spec.fields.find(field => field.kind === 'image' && present(fieldValue(record, field.name)))
  const writing = spec.fields.filter(field => field.kind === 'text' && present(fieldValue(record, field.name)))
  const facts = spec.fields.filter(field => field.name !== spec.display && field.kind !== 'text' && field.kind !== 'image')
  const badges = spec.fields.filter(field => field.kind === 'enum' && present(fieldValue(record, field.name)))
  const updated = relativeTime(fieldValue(record, 'updatedAt'))
  const created = relativeTime(fieldValue(record, 'createdAt'))
  const details = <aside className="app-card">
    <h2 className="app-section-title">Details</h2>
    <dl className="app-props">
      {facts.map(field => {
        const FieldIcon = KIND_ICONS[field.kind]
        const value = fieldValue(record, field.name)
        return <div key={field.name}>
          <dt><FieldIcon size={15} aria-hidden="true" />{field.label}</dt>
          <dd>{field.kind === 'enum' && present(value) ? <Badge tone={field.tones?.[String(value)] ?? 'accent'}>{formatValue(field, value)}</Badge> : formatValue(field, value)}</dd>
        </div>
      })}
      {created && <div><dt><CalendarDays size={15} aria-hidden="true" />Created</dt><dd>{created}</dd></div>}
      {updated && <div><dt><Clock size={15} aria-hidden="true" />Updated</dt><dd>{updated}</dd></div>}
    </dl>
  </aside>
  return <div className={`app-stack app-tone--${toneOf(spec, record)}`}>
    {crumbs(title)}
    {image && <img className="app-detail__cover" src={String(fieldValue(record, image.name))} alt="" onError={event => { event.currentTarget.style.display = 'none' }} />}
    <header className="app-detail-hero app-fade-in">
      <span className="app-icon-tile app-icon-tile--lg"><Icon size={28} aria-hidden="true" /></span>
      <div className="app-stack">
        <p className="app-eyebrow">{spec.label}</p>
        <h1>{title}</h1>
        {(badges.length > 0 || updated) && <div className="app-record__meta">
          {badges.map(field => <Badge key={field.name} tone={field.tones?.[String(fieldValue(record, field.name))] ?? 'accent'}>{formatValue(field, fieldValue(record, field.name))}</Badge>)}
          {updated && <span className="app-meta"><Clock size={15} aria-hidden="true" />Updated {updated}</span>}
        </div>}
      </div>
      {editable && <div className="app-actions">
        <Button id={`${spec.plural}-edit`} variant="secondary" onClick={() => { setFormError(''); setEditing(true) }}><Pencil size={16} aria-hidden="true" />Edit</Button>
        <Button id={`${spec.plural}-delete`} variant="danger" onClick={() => setConfirming(true)}><Trash2 size={16} aria-hidden="true" />Delete</Button>
      </div>}
    </header>
    {writing.length > 0
      ? <div className="app-detail">
        <div className="app-stack">
          {writing.map(field => <section key={field.name} className="app-card">
            <h2 className="app-section-title">{field.label}</h2>
            <p className="app-prose">{String(fieldValue(record, field.name))}</p>
          </section>)}
        </div>
        {details}
      </div>
      : <div className="app-detail app-detail--single">{details}</div>}
    <Modal open={editing} title={`Edit ${spec.label.toLowerCase()}`} onClose={() => setEditing(false)}>
      {editing && <RecordForm spec={spec} record={record} busy={busy} error={formError} submitLabel="Save changes" onSubmit={save} onCancel={() => setEditing(false)} />}
    </Modal>
    <Modal open={confirming} title={`Delete this ${spec.label.toLowerCase()}?`} onClose={() => setConfirming(false)}
      actions={<>
        <Button variant="ghost" onClick={() => setConfirming(false)} disabled={busy}>Cancel</Button>
        <Button id={`${spec.plural}-confirm-delete`} variant="danger" onClick={remove} disabled={busy}>{busy ? 'Deleting…' : 'Delete'}</Button>
      </>}>
      <p className="app-muted">"{title}" will be removed for good. This cannot be undone.</p>
    </Modal>
  </div>
}

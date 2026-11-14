import { useEffect, useState, useSyncExternalStore, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { Badge, Button, Card, EmptyState, FilterChips, Grid, Modal, Notice, PageHeader, SearchInput, SelectField, Skeleton, TextArea, TextField } from '{{kit}}'

export type FieldKind = 'string' | 'text' | 'number' | 'boolean' | 'date' | 'enum'
export type FieldSpec = { name: string; label: string; kind: FieldKind; required: boolean; options?: string[] }
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

export function fieldValue(record: unknown, name: string): unknown {
  return record && typeof record === 'object' ? (record as Values)[name] : undefined
}

export function optionLabel(option: string): string {
  return option.charAt(0).toUpperCase() + option.slice(1)
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
      const shared = { id, name: field.name, label: field.label, error: errors[field.name], required: field.required }
      if (field.kind === 'boolean') return <label key={field.name} className="app-check">
        <input id={id} type="checkbox" checked={value === true} onChange={event => set(field.name, event.target.checked)} />
        {field.label}
      </label>
      if (field.kind === 'text') return <TextArea key={field.name} {...shared} rows={4} maxLength={5000}
        value={String(value ?? '')} onChange={event => set(field.name, event.target.value)} />
      if (field.kind === 'enum') return <SelectField key={field.name} {...shared} value={String(value ?? '')}
        onChange={event => set(field.name, event.target.value)}
        options={[...(field.required ? [] : [{ value: '', label: 'Not set' }]), ...(field.options ?? []).map(option => ({ value: option, label: optionLabel(option) }))]} />
      return <TextField key={field.name} {...shared} type={field.kind === 'number' ? 'number' : field.kind === 'date' ? 'date' : 'text'}
        step={field.kind === 'number' ? 'any' : undefined} maxLength={field.kind === 'string' ? 200 : undefined}
        value={String(value ?? '')} onChange={event => set(field.name, event.target.value)} />
    })}
    <div className="app-actions">
      <Button type="submit" id={`${spec.plural}-save`} disabled={busy}>{busy ? 'Saving…' : submitLabel}</Button>
      {onCancel && <Button variant="ghost" onClick={onCancel} disabled={busy}>Cancel</Button>}
    </div>
  </form>
}

export function RecordList<T extends { id: string }, I>({ spec, title, description }: {
  spec: EntitySpec<T, I>; title?: string; description?: string
}) {
  const navigate = useNavigate()
  const [query, setQuery] = useState('')
  const [choice, setChoice] = useState('')
  const [records, setRecords] = useState<T[] | null>(null)
  const [error, setError] = useState('')
  const [creating, setCreating] = useState(false)
  const [busy, setBusy] = useState(false)
  const [formError, setFormError] = useState('')
  const [version, setVersion] = useState(0)
  const filter = spec.fields.find(field => field.name === spec.filter)
  useEffect(() => {
    let active = true
    const timer = window.setTimeout(() => {
      const params: Record<string, string> = {}
      if (query.trim()) params.q = query.trim()
      if (filter && choice) params[filter.name] = choice
      spec.source.list(params)
        .then(rows => { if (active) { setRecords(rows); setError('') } })
        .catch(() => { if (active) setError(`Could not load ${spec.pluralLabel.toLowerCase()}. Please try again.`) })
    }, query ? 250 : 0)
    return () => { active = false; window.clearTimeout(timer) }
  }, [query, choice, version, spec, filter])
  async function create(input: I) {
    setBusy(true)
    setFormError('')
    try {
      await spec.source.create(input)
      setCreating(false)
      setVersion(value => value + 1)
    } catch {
      setFormError(`Could not save this ${spec.label.toLowerCase()}. Check the fields and try again.`)
    } finally {
      setBusy(false)
    }
  }
  const open = () => { setFormError(''); setCreating(true) }
  const allowed = useSyncExternalStore(spec.watch ?? still, spec.canCreate, spec.canCreate)
  const display = spec.fields.find(field => field.name === spec.display)
  const summary = spec.fields.find(field => field.kind === 'text' && field.name !== spec.display)
  const badges = spec.fields.filter(field => field.name !== spec.display && field.kind !== 'text').slice(0, 3)
  const options = !filter ? [] : [{ value: '', label: 'All' }, ...(filter.kind === 'boolean'
    ? [{ value: 'true', label: 'Yes' }, { value: 'false', label: 'No' }]
    : (filter.options ?? []).map(option => ({ value: option, label: optionLabel(option) })))]
  const narrowed = query.trim() !== '' || choice !== ''
  return <div className="app-stack" data-crud={spec.plural}>
    <PageHeader title={title ?? spec.pluralLabel} description={description}
      actions={allowed ? <Button id={`${spec.plural}-new`} onClick={open}>New {spec.label.toLowerCase()}</Button> : undefined} />
    {(spec.search || options.length > 0) && <div className="app-toolbar">
      {spec.search && <SearchInput label={`Search ${spec.pluralLabel.toLowerCase()}`} value={query} onChange={event => setQuery(event.target.value)} />}
      {filter && <FilterChips options={options} value={choice} onChange={setChoice} label={filter.label} />}
    </div>}
    {error && <Notice tone="danger">{error}</Notice>}
    {records === null
      ? !error && <Skeleton lines={4} />
      : records.length === 0
        ? <EmptyState title={narrowed ? `No matching ${spec.pluralLabel.toLowerCase()}` : `No ${spec.pluralLabel.toLowerCase()} yet`}
          description={narrowed ? 'Try a different search or filter.' : allowed ? `Add the first ${spec.label.toLowerCase()} to get started.` : 'New entries will appear here.'}
          action={!narrowed && allowed ? <Button onClick={open}>New {spec.label.toLowerCase()}</Button> : undefined} />
        : <Grid min={16}>
          {records.map(record => <Card key={record.id} title={formatValue(display, fieldValue(record, spec.display))}
            onClick={() => navigate(`${spec.recordPath}/${record.id}`)}
            footer={badges.some(field => fieldValue(record, field.name) !== undefined && fieldValue(record, field.name) !== null)
              ? <div className="app-actions">{badges.filter(field => fieldValue(record, field.name) !== undefined && fieldValue(record, field.name) !== null)
                .map(field => <Badge key={field.name} tone={field.kind === 'enum' ? 'accent' : field.kind === 'boolean' && fieldValue(record, field.name) === true ? 'success' : 'neutral'}>
                  {field.kind === 'enum' ? formatValue(field, fieldValue(record, field.name)) : `${field.label}: ${formatValue(field, fieldValue(record, field.name))}`}
                </Badge>)}</div>
              : undefined}>
            {summary && fieldValue(record, summary.name) ? <p className="app-muted app-clamp">{String(fieldValue(record, summary.name))}</p> : null}
          </Card>)}
        </Grid>}
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
  const back = <div className="app-actions"><Button variant="ghost" onClick={leave}>← {spec.listPath ? spec.pluralLabel : 'Back'}</Button></div>
  if (state === 'loading') return <div className="app-stack">{back}<Skeleton lines={5} /></div>
  if (state === 'missing' || !record) return <div className="app-stack">{back}
    <EmptyState title={state === 'error' ? `Could not load this ${spec.label.toLowerCase()}` : `This ${spec.label.toLowerCase()} is not available`}
      description={state === 'error' ? 'Please try again in a moment.' : 'It may have been deleted.'}
      action={state === 'error' ? <Button onClick={() => setVersion(value => value + 1)}>Try again</Button> : undefined} />
  </div>
  async function save(input: I) {
    setBusy(true)
    setFormError('')
    try {
      setRecord(await spec.source.update(id, input))
      setEditing(false)
    } catch {
      setFormError('Could not save your changes. Check the fields and try again.')
    } finally {
      setBusy(false)
    }
  }
  async function remove() {
    setBusy(true)
    try {
      await spec.source.remove(id)
      setConfirming(false)
      leave()
    } catch {
      setConfirming(false)
      setFormError(`Could not delete this ${spec.label.toLowerCase()}. Please try again.`)
    } finally {
      setBusy(false)
    }
  }
  const display = spec.fields.find(field => field.name === spec.display)
  return <div className="app-stack">
    {back}
    <PageHeader eyebrow={spec.label} title={formatValue(display, fieldValue(record, spec.display))}
      actions={editable && !editing ? <>
        <Button id={`${spec.plural}-edit`} variant="secondary" onClick={() => { setFormError(''); setEditing(true) }}>Edit</Button>
        <Button id={`${spec.plural}-delete`} variant="danger" onClick={() => setConfirming(true)}>Delete</Button>
      </> : undefined} />
    {formError && !editing && <Notice tone="danger">{formError}</Notice>}
    <Card>
      {editing
        ? <RecordForm spec={spec} record={record} busy={busy} error={formError} submitLabel="Save changes" onSubmit={save} onCancel={() => setEditing(false)} />
        : <dl className="app-details">
          {spec.fields.filter(field => field.name !== spec.display).map(field =>
            <div key={field.name} className={field.kind === 'text' ? 'app-details__wide' : undefined}>
              <dt>{field.label}</dt>
              <dd>{field.kind === 'enum' && fieldValue(record, field.name) ? <Badge tone="accent">{formatValue(field, fieldValue(record, field.name))}</Badge> : formatValue(field, fieldValue(record, field.name))}</dd>
            </div>)}
        </dl>}
    </Card>
    <Modal open={confirming} title={`Delete this ${spec.label.toLowerCase()}?`} onClose={() => setConfirming(false)}
      actions={<>
        <Button variant="ghost" onClick={() => setConfirming(false)} disabled={busy}>Cancel</Button>
        <Button id={`${spec.plural}-confirm-delete`} variant="danger" onClick={remove} disabled={busy}>{busy ? 'Deleting…' : 'Delete'}</Button>
      </>}>
      <p className="app-muted">This permanently removes it. This cannot be undone.</p>
    </Modal>
  </div>
}

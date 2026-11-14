import { Router, type RequestHandler, type Response } from 'express'

export type FieldKind = 'string' | 'text' | 'number' | 'boolean' | 'date' | 'enum' | 'image'
export type FieldSpec = { name: string; kind: FieldKind; required: boolean; options?: string[] }
export type EntitySpec = { display: string; fields: FieldSpec[]; search: string[]; filter?: string }

const LENGTH: Record<FieldKind, number> = { string: 200, text: 5000, number: 0, boolean: 0, date: 10, enum: 40, image: 2000 }

class InvalidInput extends Error {
  readonly status = 400
}

function pattern(text: string): string {
  return text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

function parse(field: FieldSpec, raw: unknown): unknown {
  if (raw === null || raw === undefined || raw === '') return null
  if (field.kind === 'number') {
    const number = typeof raw === 'number' ? raw : typeof raw === 'string' && raw.trim() !== '' ? Number(raw) : Number.NaN
    if (!Number.isFinite(number)) throw new InvalidInput(`${field.name} must be a number`)
    return number
  }
  if (field.kind === 'boolean') {
    if (typeof raw === 'boolean') return raw
    if (raw === 'true' || raw === 'false') return raw === 'true'
    throw new InvalidInput(`${field.name} must be true or false`)
  }
  if (field.kind === 'date') {
    if (typeof raw !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(raw) || Number.isNaN(Date.parse(raw)))
      throw new InvalidInput(`${field.name} must be a date such as 2026-01-31`)
    return raw
  }
  if (field.kind === 'enum') {
    if (typeof raw !== 'string' || !(field.options ?? []).includes(raw))
      throw new InvalidInput(`${field.name} must be one of: ${(field.options ?? []).join(', ')}`)
    return raw
  }
  if (field.kind === 'image') {
    if (typeof raw !== 'string' || raw.length > LENGTH.image || !/^https?:\/\/\S+$/i.test(raw.trim())) throw new InvalidInput(`${field.name} must be a web address starting with https://`)
    return raw.trim()
  }
  if (typeof raw !== 'string' && typeof raw !== 'number') throw new InvalidInput(`${field.name} must be text`)
  const text = String(raw).trim()
  if (text.length > LENGTH[field.kind]) throw new InvalidInput(`${field.name} must be at most ${LENGTH[field.kind]} characters`)
  return text === '' ? null : text
}

function input(spec: EntitySpec, body: unknown, partial: boolean) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) throw new InvalidInput('Send the record as a JSON object')
  const set: Record<string, unknown> = {}
  const unset: Record<string, ''> = {}
  for (const field of spec.fields) {
    if (!Object.prototype.hasOwnProperty.call(body, field.name)) {
      if (!partial && field.required) throw new InvalidInput(`${field.name} is required`)
      continue
    }
    const value = parse(field, (body as Record<string, unknown>)[field.name])
    if (value === null) {
      if (field.required) throw new InvalidInput(`${field.name} is required`)
      if (partial) unset[field.name] = ''
      continue
    }
    set[field.name] = value
  }
  return { set, unset }
}

function recordId(id: unknown): id is string {
  return typeof id === 'string' && /^[a-f0-9]{24}$/i.test(id)
}

function fail(res: Response, error: unknown) {
  const status = (error as { status?: unknown }).status
  const name = (error as { name?: unknown }).name
  if (status === 400) res.status(400).json({ error: (error as Error).message })
  else if (status === 403) res.status(403).json({ error: 'Admins only' })
  else if (name === 'ValidationError' || name === 'CastError') res.status(400).json({ error: 'Invalid record' })
  else res.status(500).json({ error: 'Request failed' })
}

export function crudRouter(model: any, spec: EntitySpec, writeGuard?: RequestHandler) {
  const router = Router()
  const guard: RequestHandler[] = writeGuard ? [writeGuard] : []
  router.get('/', async (req, res) => {
    try {
      const filter: Record<string, unknown> = {}
      const text = typeof req.query.q === 'string' ? req.query.q.trim().slice(0, 100) : ''
      if (text && spec.search.length > 0) filter.$or = spec.search.map(name => ({ [name]: { $regex: pattern(text), $options: 'i' } }))
      const field = spec.fields.find(candidate => candidate.name === spec.filter)
      const chosen = field ? req.query[field.name] : undefined
      if (field && typeof chosen === 'string' && chosen !== '') filter[field.name] = parse(field, chosen)
      res.json(await model.find(filter).sort({ createdAt: -1 }).limit(200))
    } catch (error) {
      fail(res, error)
    }
  })
  router.get('/:id', async (req, res, next) => {
    if (!recordId(req.params.id)) { next(); return }
    try {
      const record = await model.findById(req.params.id)
      if (!record) { res.status(404).json({ error: 'Not found' }); return }
      res.json(record)
    } catch (error) {
      fail(res, error)
    }
  })
  router.post('/', ...guard, async (req, res) => {
    try {
      res.status(201).json(await model.create(input(spec, req.body, false).set))
    } catch (error) {
      fail(res, error)
    }
  })
  router.patch('/:id', ...guard, async (req, res, next) => {
    if (!recordId(req.params.id)) { next(); return }
    try {
      const { set, unset } = input(spec, req.body, true)
      const update: Record<string, unknown> = {}
      if (Object.keys(set).length > 0) update.$set = set
      if (Object.keys(unset).length > 0) update.$unset = unset
      const record = Object.keys(update).length > 0
        ? await model.findOneAndUpdate({ _id: req.params.id }, update, { returnDocument: 'after', runValidators: true })
        : await model.findById(req.params.id)
      if (!record) { res.status(404).json({ error: 'Not found' }); return }
      res.json(record)
    } catch (error) {
      fail(res, error)
    }
  })
  router.delete('/:id', ...guard, async (req, res, next) => {
    if (!recordId(req.params.id)) { next(); return }
    try {
      const record = await model.findOneAndDelete({ _id: req.params.id })
      if (!record) { res.status(404).json({ error: 'Not found' }); return }
      res.json({ id: req.params.id })
    } catch (error) {
      fail(res, error)
    }
  })
  return router
}

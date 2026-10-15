import express from 'express'
import mongoose from 'mongoose'

const app = express()
app.use(express.json())

app.get('/api/health', (_req, res) => {
  res.json({ ok: true })
})

// Register the app's routers here, every one under /api, e.g. app.use('/api/todos', todos).

const port = Number(process.env.PORT ?? 3000)

async function start() {
  // The backend gives the app its own database through MONGODB_URI; never hard-code one.
  const uri = process.env.MONGODB_URI
  if (uri) await mongoose.connect(uri)
  app.listen(port)
}

start().catch((error) => {
  console.error(error)
  process.exit(1)
})

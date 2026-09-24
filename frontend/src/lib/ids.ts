/**
 * Client-generated, time-sortable ids with a type prefix (ULID-ish).
 *
 * Generated on the client so a message can be rendered optimistically, retried idempotently
 * (the backend upserts on this id), and matched to its streaming reply. Lowercase Crockford
 * base32 keeps them within the backend's `[A-Za-z0-9_-]+`, ≤40-char contract.
 */
const B32 = '0123456789abcdefghjkmnpqrstvwxyz' // Crockford base32 (no i, l, o, u)

function ulid(prefix: string): string {
  // 10 chars of millisecond time (sortable) + 16 chars of randomness.
  let time = ''
  let n = Date.now()
  for (let i = 0; i < 10; i++) {
    time = B32[n % 32] + time
    n = Math.floor(n / 32)
  }

  const bytes = new Uint8Array(16)
  crypto.getRandomValues(bytes)
  let rand = ''
  for (const b of bytes) rand += B32[b % 32]

  return `${prefix}_${time}${rand}`
}

export const newUserMessageId = () => ulid('umsg')
export const newAiMessageId = () => ulid('aimsg')

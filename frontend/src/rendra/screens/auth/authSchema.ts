import { z } from 'zod'

export type AuthMode = 'login' | 'signup'
export type AuthStep = 'email' | 'password'

const email = z.string().min(1, 'Email is required').email('Enter a valid email')

export const loginSchema = z.object({
  email,
  name: z.string().optional(),
  password: z.string().min(1, 'Password is required'),
})

export const signupSchema = z.object({
  email,
  name: z.string().max(100, 'Name is too long').optional(),
  password: z.string().min(8, 'At least 8 characters'),
})

export type AuthValues = z.infer<typeof signupSchema>

export const schemaFor = (mode: AuthMode) => (mode === 'signup' ? signupSchema : loginSchema)

import type { BrandId } from '../../../components/brand/brands'

export type Connector = { id: string; brand: BrandId; name: string; category: string; desc: string }

export const CONNECTORS: readonly Connector[] = [
  { id: 'gh', brand: 'github', name: 'GitHub', category: 'Code', desc: 'Sync every project to a repository and open pull requests.' },
  { id: 'sl', brand: 'slack', name: 'Slack', category: 'Messaging', desc: 'Send alerts and let workflows post to channels.' },
  { id: 'st', brand: 'stripe', name: 'Stripe', category: 'Payments', desc: 'Checkout, subscriptions and invoices.' },
  { id: 'gs', brand: 'googleSheets', name: 'Google Sheets', category: 'Data', desc: 'Read and write rows from any sheet.' },
  { id: 'no', brand: 'notion', name: 'Notion', category: 'Docs', desc: 'Use pages and databases as content.' },
  { id: 'rs', brand: 'resend', name: 'Resend', category: 'Email', desc: 'Transactional email from your app.' },
  { id: 'tw', brand: 'twilio', name: 'Twilio', category: 'SMS & voice', desc: 'Text message reminders and OTP login.' },
  { id: 'hs', brand: 'hubspot', name: 'HubSpot', category: 'CRM', desc: 'Sync contacts, deals and forms.' },
  { id: 'at', brand: 'airtable', name: 'Airtable', category: 'Data', desc: 'Bases as a quick backend.' },
]


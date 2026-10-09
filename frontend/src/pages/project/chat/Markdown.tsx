import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'

/** Renders assistant text as formatted Markdown (headings, bold, lists, tables, code) instead of
 * raw "**"/"###"/"|" syntax — the model's replies are written expecting markdown rendering. */
export function Markdown({ content }: { content: string }) {
  return <div className="pw-md"><ReactMarkdown remarkPlugins={[remarkGfm]}>{content}</ReactMarkdown></div>
}

import { useLocation } from 'react-router-dom'
// Compatibility exports for existing starter consumers; new apps can import the package directly.
export { HashRouter as Router, Routes, Route, Link, useParams, useNavigate } from 'react-router-dom'

export function navigate(to: string): void { window.location.hash = to }

export function NotFound() {
  return <main><h1>Page not found</h1><a href="#/">Go to the home page</a></main>
}

export function useRoute(): string { return useLocation().pathname }

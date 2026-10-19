export default function App() {
const route = routes.find(route => route.path === window.location.pathname)
const Component = route?.component
const params: Record<string,string> = {}
return Component ? <Component params={params} /> : <main><h1>Page not found</h1><p>The page you requested does not exist.</p><a href="/">Go to the homepage</a></main>
}

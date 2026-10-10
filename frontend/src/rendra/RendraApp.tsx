import { lazy, Suspense, useState } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { LoadingState } from '../components/ui/States'
import RendraLanding from './screens/landing/Landing'

// Each screen is lazy-loaded so its code and CSS stay out of the landing bundle.
const Auth = lazy(() => import('./screens/auth/Auth'))
const Onboarding = lazy(() => import('./screens/onboarding/Onboarding'))
const Dashboard = lazy(() => import('./screens/dashboard/Dashboard'))
const Projects = lazy(() => import('./screens/projects/Projects'))
const Templates = lazy(() => import('./screens/templates/Templates'))
const Connectors = lazy(() => import('./screens/connectors/Connectors'))
const Notifications = lazy(() => import('./screens/notifications/Notifications'))
const Chats = lazy(() => import('./screens/chats/Chats'))
const Settings = lazy(() => import('./screens/settings/Settings'))
const Invite = lazy(() => import('./screens/invite/Invite'))
const Pricing = lazy(() => import('./screens/pricing/Pricing'))

function Mark() { return <span className="k-mark"><i /></span> }
function Arrow() { return <span className="k-arrow">→</span> }

function Workspace(){const location=useLocation(); const nav=useNavigate(); const [message,setMessage]=useState(''); const path=location.pathname; const tab=path.includes('code')?'Code':path.includes('details')?'Details':path.includes('history')?'History':path.includes('share')?'Share':path.includes('publish')?'Publish':path.includes('analytics')?'Analytics':path.includes('database')?'Database':path.includes('security')?'Security':path.includes('members')?'Members':path.includes('settings')?'Settings':'Preview'; return <div className="rendra k-workspace"><header><Link to="/dashboard" className="k-brand"><Mark/>Rendra</Link><span className="k-divider"/><button className="k-project-name">Cedar & Stone⌄</button><span className="k-spacer"/><button>↶</button><button>↷</button><button onClick={()=>nav('/project/demo/share')}>Share</button><button className="k-dark-btn" onClick={()=>nav('/project/demo/publish')}>Publish</button></header><nav className="k-work-tabs">{[['Preview','/project/demo'],['Code','/project/demo/code'],['Details','/project/demo/details'],['History','/project/demo/history']].map(([x,p])=><Link className={tab===x?'active':''} key={x} to={p}>{x}</Link>)}<span/><button onClick={()=>nav('/project/demo/analytics')}>More⌄</button></nav><main>{tab==='Preview'||tab==='Code'?<><section className="k-chat"><div className="k-chat-scroll"><article className="k-user-msg">Create a warm, minimal online home for Cedar & Stone — a furniture studio making considered pieces for everyday living.</article><article className="k-agent-msg"><b><Mark/> Rendra <small>just now</small></b><p>I’ve built the first version of your site. It has a calm editorial feel with an easy path to browse the collection.</p><div className="k-build-status"><span>✓</span> Created 8 files <small>View changes</small></div></article></div><form onSubmit={e=>{e.preventDefault();setMessage('')}}><textarea value={message} onChange={e=>setMessage(e.target.value)} placeholder="Ask Rendra to make a change..."/><footer><button type="button">＋</button><span/><button className="k-send">↑</button></footer></form></section><section className={'k-preview '+(tab==='Code'?'code':'')} >{tab==='Code'?<CodeView/>:<Preview/>}</section></>:<ProjectTool tab={tab}/>}</main></div>}
function Preview(){return <div className="k-preview-page"><nav><b>CEDAR<br/>& STONE</b><span>Collection　 Story　 Journal</span><button>Visit us</button></nav><section><small>EST. 2014 — BROOKLYN, NY</small><h1>Objects for<br/><em>everyday</em> living.</h1><p>Furniture made slowly, from honest materials.</p><button>Explore collection　↗</button></section><footer><span>01 / 04</span><b>↓</b></footer></div>}
function CodeView(){return <div className="k-code-view"><header><span>Explorer</span><button>＋</button></header><aside>{['src','  components','    Header.tsx','    Hero.tsx','  App.tsx','  main.tsx','package.json'].map(x=><div key={x} className={x.includes('Hero')?'selected':''}>{x}</div>)}</aside><article><code><i>1</i> import {'{ Header }'} from './components/Header'<br/><i>2</i><br/><i>3</i> export default function App() {'{'}<br/><i>4</i>　 return (&lt;main&gt;<br/><i>5</i>　　&lt;Header /&gt;<br/><i>6</i>　　&lt;Hero /&gt;<br/><i>7</i>　 &lt;/main&gt;)<br/><i>8</i> {'}'}</code></article></div>}
function ProjectTool({tab}:{tab:string}){const titles:Record<string,[string,string]>={Details:['Build details','Everything Rendra changed in this version.'],History:['Version history','Restore any point in your project’s timeline.'],Share:['Share this project','Invite people to view, comment or build with you.'],Publish:['Publish your project','Make Cedar & Stone live for the world.'],Analytics:['Analytics','See how people are using your app.'],Database:['Cloud database','Your project’s data, ready when you are.'],Security:['Security scan','A quick check of your project’s security posture.'],Members:['Members & access','People who can access this project.'],Settings:['Project settings','Manage the details of this project.']};const [h,s]=titles[tab]??titles.Details;return <section className="k-tool"><div><small>PROJECT · {tab.toUpperCase()}</small><h1>{h}</h1><p>{s}</p></div>{tab==='Share'?<div className="k-tool-card"><label>Invite people<input placeholder="name@company.com"/></label><button className="k-dark-wide">Send invite</button><hr/><p><b>Alex Morgan</b><span>Owner</span></p><p><b>Maya Chen</b><span>Can edit</span></p></div>:tab==='Publish'?<div className="k-tool-card"><b className="k-publish-icon">↗</b><h2>Ready to publish?</h2><p>Your app will be live at a secure rendra.app URL.</p><label>Project URL<input value="cedar-stone.rendra.app" readOnly/></label><button className="k-dark-wide">Publish project <Arrow/></button></div>:<div className="k-tool-card k-list">{['Build completed successfully','Project is deployed and healthy','All team members have access','No issues found'].map((x,i)=><p key={x}><span>{tab==='Security'?'✓':i+1}</span>{x}<small>{i===0?'Today, 10:42 AM':'Updated recently'}</small></p>)}</div>}</section>}

const PRIVATE_PATHS = ['/dashboard', '/projects', '/chats', '/connectors', '/notifications', '/settings', '/workspace-preview', '/invite']

/** Screen for the current path: public pages, then signed-in pages behind the auth check. Search is an overlay (`App`). */
function screenFor(path: string) {
  if (path === '/pricing') return <Pricing />
  if (path === '/auth' || path === '/login' || path === '/signup') return <><RendraLanding /><Auth /></>
  if (path.startsWith('/onboarding')) return <Onboarding />
  if (path === '/invite') return <Invite />
  if (path === '/dashboard') return <Dashboard />
  if (path === '/projects') return <Projects />
  if (path === '/chats') return <Chats />
  if (path === '/templates') return <Templates />
  if (path === '/connectors') return <Connectors />
  if (path === '/notifications') return <Notifications />
  if (path.startsWith('/settings')) return <Settings />
  if (path === '/workspace-preview') return <Workspace />
  return null
}

export default function RendraApp() {
  const p = useLocation().pathname
  const { isAuthenticated, loading } = useAuth()

  const privatePath = PRIVATE_PATHS.some((x) => p.startsWith(x))
  if (loading && privatePath) return null
  if (privatePath && !isAuthenticated) return <Navigate to="/auth" replace />
  if (p === '/') {
    if (loading) return null
    return isAuthenticated ? <Navigate to="/dashboard" replace /> : <RendraLanding />
  }
  const screen = screenFor(p)
  return screen ? <Suspense fallback={<LoadingState />}>{screen}</Suspense> : <Navigate to="/" replace />
}

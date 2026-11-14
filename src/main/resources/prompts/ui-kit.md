UI kit: when the project has a kit folder (src/kit, or client/src/kit for MERN), build pages from it; a page in
src/pages imports from '../kit'. Every component follows the theme preset, so never restyle it with colours.
- Layout: PageHeader{eyebrow?,title,description?,actions?} · Hero{eyebrow?,title,description?,actions?,visual?} ·
  Section{title?,description?,actions?,children} · Grid{min? (rem),children} · Card{title?,description?,actions?,footer?,onClick?,children} ·
  Stat{label,value,hint?,trend?:'up'|'down'|'flat'} · StatGrid{children}
- Controls: Button{variant?:'primary'|'secondary'|'ghost'|'danger',size?:'sm'|'md'|'lg',...button props} ·
  TextField / TextArea {label,hint?,error?,...input props} · SelectField{label,options:[{value,label}],...select props} ·
  Field{label,hint?,error?,children} · SearchInput{label?,...input props} · FilterChips{options:[{value,label}],value,onChange,label?} ·
  Tabs{tabs:[{value,label}],value,onChange,label?}
- Feedback: Badge{tone?:'neutral'|'accent'|'success'|'warning'|'danger'} · Notice{tone?,title?,children} ·
  EmptyState{icon?,title,description?,action?} · Skeleton{lines?} · Spinner{label?} · ProgressBar{value,max?,label?} ·
  Modal{open,title,onClose,actions?,children}
- Data: DataTable{columns:[{key,header,render?,align?}],rows,rowKey,empty?,onRowClick?} · RankList{entries:[{id,name,score,detail?,highlight?}],empty?} ·
  Avatar{name,src?,size?}
Classes: app-stack, app-grid, app-card, app-muted, app-eyebrow, app-lead, app-actions, app-fade-in. Kit inputs and buttons
pass through name, aria-* and data-* attributes, so required selectors still work.
Design: open each main page with a Hero or PageHeader, show numbers as Stat cards, lists as Cards, DataTable or RankList,
and give every list an EmptyState and a Skeleton while loading. Write CSS only for what the kit lacks (a game board,
a chart, an illustration), using var(--app-accent|surface|surface-2|text|muted|border|radius|shadow), never fixed colours.

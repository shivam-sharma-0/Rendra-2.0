/** Primary destinations in the app shell; a screen passes its own id to highlight it. */
export type NavId = 'home' | 'projects' | 'templates' | 'connectors' | 'chats'

/** Which shell popover is open. Only one can be open at a time. */
export type ShellPopover = 'none' | 'workspace' | 'account' | 'notifications'

/** Open / toggle / close controls for one shell popover. */
export type PopoverControl = { open: boolean; onToggle: () => void; onClose: () => void }

import { createContext, useContext } from 'react'

/**
 * What a screen inside the app shell needs to offer its own menu button: whether the sidebar is a
 * drawer (compact screens) and how to open it. Screens without the shell's top bar (the project
 * workspace) use it to put the button in their own header.
 */
export type ShellMenu = { compact: boolean; openMenu: () => void }

export const ShellMenuContext = createContext<ShellMenu>({ compact: false, openMenu: () => {} })

export const useShellMenu = () => useContext(ShellMenuContext)

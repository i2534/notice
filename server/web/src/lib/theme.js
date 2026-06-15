const darkVars = {
  '--bg-deep': '#08080a',
  '--bg-surface': '#101014',
  '--bg-elevated': '#181820',
  '--bg-card': '#22222c',
  '--bg-hover': '#2c2c38',
  '--border': '#30303e',
  '--border-light': '#3c3c4a',
  '--text-primary': '#eeeeef',
  '--text-secondary': '#a8a8b8',
  '--text-hint': '#68687a',
}

const lightVars = {
  '--bg-deep': '#f5f5f7',
  '--bg-surface': '#fafafa',
  '--bg-elevated': '#ffffff',
  '--bg-card': '#f0f0f5',
  '--bg-hover': '#e8e8ee',
  '--border': '#d5d5de',
  '--border-light': '#e0e0e8',
  '--text-primary': '#1a1a2e',
  '--text-secondary': '#4a4a5e',
  '--text-hint': '#808090',
  '--accent': '#00997a',
  '--accent-alpha-8': 'rgba(0,153,122,0.1)',
  '--accent-alpha-15': 'rgba(0,153,122,0.15)',
  '--accent-glow': 'rgba(0,153,122,0.25)',
  '--danger': '#d63031',
  '--danger-dim': 'rgba(214,48,49,0.1)',
  '--warning': '#b8860b',
  '--info': '#1a73e8',
  '--text-on-accent': '#0d0d16',
  '--text-on-accent-light': '#fff',
  '--accent-secondary': '#4a3fcf',
}

/** @param {'dark' | 'light'} theme */
export function applyTheme(theme) {
  const vars = theme === 'light' ? lightVars : darkVars
  const root = document.documentElement
  Object.entries(vars).forEach(([key, val]) => root.style.setProperty(key, val))
}

export function getSystemTheme() {
  if (window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches) {
    return 'light'
  }
  return 'dark'
}

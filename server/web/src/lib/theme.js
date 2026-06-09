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
  '--bg-deep': '#e4e6ea',
  '--bg-surface': '#eef0f2',
  '--bg-elevated': '#f6f7f8',
  '--bg-card': '#eef0f4',
  '--bg-hover': '#e4e6ec',
  '--border': '#c8cad2',
  '--border-light': '#d8dae2',
  '--text-primary': '#14141a',
  '--text-secondary': '#545660',
  '--text-hint': '#848690',
}

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

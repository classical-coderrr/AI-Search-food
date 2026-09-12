export const AGENT_PANEL_SAFE_GAP = 16

function toFiniteNumber(value, fallback = 0) {
  const number = Number(value)
  return Number.isFinite(number) ? number : fallback
}

function clamp(value, minimum, maximum) {
  return Math.min(Math.max(value, minimum), maximum)
}

export function clampAgentPanelPosition(position, viewport, safeGap = AGENT_PANEL_SAFE_GAP) {
  const gap = Math.max(0, toFiniteNumber(safeGap, AGENT_PANEL_SAFE_GAP))
  const viewportWidth = Math.max(0, toFiniteNumber(viewport?.viewportWidth))
  const viewportHeight = Math.max(0, toFiniteNumber(viewport?.viewportHeight))
  const panelWidth = Math.max(0, toFiniteNumber(viewport?.panelWidth))
  const panelHeight = Math.max(0, toFiniteNumber(viewport?.panelHeight))
  const maxLeft = Math.max(gap, viewportWidth - panelWidth - gap)
  const maxTop = Math.max(gap, viewportHeight - panelHeight - gap)

  return {
    left: clamp(toFiniteNumber(position?.left, gap), gap, maxLeft),
    top: clamp(toFiniteNumber(position?.top, gap), gap, maxTop)
  }
}

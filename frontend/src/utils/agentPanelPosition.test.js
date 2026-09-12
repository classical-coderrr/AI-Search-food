import assert from 'node:assert/strict'
import test from 'node:test'
import { clampAgentPanelPosition } from './agentPanelPosition.js'

test('小厨灵窗口位置会留在可见视口的安全范围内', () => {
  assert.deepEqual(
    clampAgentPanelPosition(
      { left: -120, top: 900 },
      { viewportWidth: 1200, viewportHeight: 800, panelWidth: 420, panelHeight: 600 }
    ),
    { left: 16, top: 184 }
  )
})

test('视口小于窗口时仍返回可用的安全边距起点', () => {
  assert.deepEqual(
    clampAgentPanelPosition(
      { left: 400, top: 300 },
      { viewportWidth: 300, viewportHeight: 240, panelWidth: 420, panelHeight: 360 }
    ),
    { left: 16, top: 16 }
  )
})

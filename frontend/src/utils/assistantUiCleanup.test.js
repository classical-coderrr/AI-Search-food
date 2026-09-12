import assert from 'node:assert/strict'
import fs from 'node:fs'
import test from 'node:test'

const agentSource = fs.readFileSync(new URL('../components/KitchenAgentWidget.vue', import.meta.url), 'utf8')
const appSource = fs.readFileSync(new URL('../App.vue', import.meta.url), 'utf8')
const sceneSource = fs.readFileSync(new URL('../components/kitchen/KitchenScene.vue', import.meta.url), 'utf8')
const worldSource = fs.readFileSync(new URL('../views/KitchenWorldView.vue', import.meta.url), 'utf8')

test('小厨灵使用最小化入口而不是新对话入口', () => {
  assert.match(agentSource, /aria-label="最小化小厨灵" title="最小化小厨灵" @click="minimizePanel"/)
  assert.match(agentSource, /class="agent-pixel-minus" aria-hidden="true"/)
  assert.doesNotMatch(agentSource, /创建新对话|startNewConversation|\bPlus\b/)
  assert.match(agentSource, /\.agent-quick-prompts \{ display: flex; flex-wrap: wrap; gap: 6px; overflow-x: hidden;/)
})

test('最小化只收起面板并把焦点交还启动入口', () => {
  assert.match(agentSource, /ref="launcher"/)
  assert.match(agentSource, /function minimizePanel\(\) \{\s+isOpen\.value = false\s+void nextTick\(\(\) => launcher\.value\?\.focus\(\)\)/)
  const minimizeBody = agentSource.match(/function minimizePanel\(\) \{([\s\S]*?)\r?\n\}/)?.[1] || ''
  assert.doesNotMatch(minimizeBody, /(conversationId|messages|draft)\.value\s*=/)
  assert.match(agentSource, /async function clearConversation\(\)[\s\S]*?conversationId\.value = null[\s\S]*?messages\.value = \[\]/)
})

test('关闭面板后把焦点交还启动入口', () => {
  assert.match(agentSource, /function closePanel\(\) \{[\s\S]*?isOpen\.value = false\s+void nextTick\(\(\) => launcher\.value\?\.focus\(\)\)/)
})

test('小厨灵和主场景不保留已删除的主题浮动入口或角色快捷入口', () => {
  assert.doesNotMatch(agentSource, /agent-character|character-shortcut|角色快捷|人物快捷/)
  assert.doesNotMatch(worldSource, /station-guide|getKitchenGuideItems|功能入口速查/)
  assert.doesNotMatch(appSource, /主题设置|theme-trigger|theme-panel|theme-option|Palette|floating-theme-control/)
  assert.match(appSource, /const themeConfigs = \[/)
  assert.match(appSource, /applyTheme\(theme\)/)
  assert.match(sceneSource, /canvas\.setAttribute\('aria-label', '点击厨房中的人物打开对应功能'\)/)
  assert.match(sceneSource, /drawCharacter\(characterLayer/)
})

test('小厨灵在桌面端支持可访问的放大、拖拽和回到原位', () => {
  assert.match(agentSource, /ref="panel"/)
  assert.match(agentSource, /:aria-label="isExpanded \? '还原小厨灵窗口' : '放大小厨灵窗口'"/)
  assert.match(agentSource, /@pointerdown="startDrag"/)
  assert.match(agentSource, /@pointerdown\.stop/)
  assert.match(agentSource, /function togglePanelExpanded\(\)/)
  assert.match(agentSource, /savedPanelPosition\.value = clampPanelRect\(rect\)/)
  assert.match(agentSource, /clampAgentPanelPosition/)
  assert.match(agentSource, /\.agent-panel\.is-expanded \{[\s\S]*calc\(100vw - 48px\)[\s\S]*calc\(100dvh - 48px\)/)
  assert.match(agentSource, /\.agent-panel\.is-expanded\.is-positioned \{ transform: none; \}/)
})

test('小厨灵支持上下拖动调整面板高度并提供键盘操作', () => {
  assert.match(agentSource, /class="agent-resize-handle"[\s\S]*role="separator"[\s\S]*aria-label="调整小厨灵窗口高度"/)
  assert.match(agentSource, /@pointerdown\.stop\.prevent="startResize"/)
  assert.match(agentSource, /function startResize\(event\)/)
  assert.match(agentSource, /function handleResizeKeydown\(event\)/)
  assert.match(agentSource, /event\.key === 'ArrowUp'[\s\S]*event\.key === 'ArrowDown'/)
  assert.match(agentSource, /function clampPanelHeight\(height\)/)
})

test('顶部品牌区使用小厨灵像素菜谱书标识和英文副标题', () => {
  assert.ok(fs.existsSync(new URL('../../public/images/brand-cookbook.png', import.meta.url)))
  assert.match(appSource, /<RouterLink class="brand" to="\/" aria-label="小厨灵 AI COOKING ASSISTANT 首页">/)
  assert.match(appSource, /class="brand-mark"[\s\S]*src="\/images\/brand-cookbook\.png"[\s\S]*alt=""/)
  assert.match(appSource, /class="brand-copy"[\s\S]*<strong>小厨灵<\/strong>[\s\S]*<small>AI COOKING ASSISTANT<\/small>/)
  assert.match(appSource, /\.brand-mark img[\s\S]*image-rendering: pixelated;/)
  assert.match(appSource, /\.brand-copy strong[\s\S]*font-size: 22px;/)
  assert.match(appSource, /\.brand-copy small[\s\S]*font-size: 10px;/)
})

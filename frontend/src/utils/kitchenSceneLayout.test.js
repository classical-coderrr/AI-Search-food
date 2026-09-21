import assert from 'node:assert/strict'
import fs from 'node:fs'
import test from 'node:test'

const sceneSource = fs.readFileSync(new URL('../components/kitchen/KitchenScene.vue', import.meta.url), 'utf8')
const worldSource = fs.readFileSync(new URL('../views/KitchenWorldView.vue', import.meta.url), 'utf8')
const windowSource = fs.readFileSync(new URL('../components/kitchen/SceneWindow.vue', import.meta.url), 'utf8')
const homeSource = fs.readFileSync(new URL('../views/HomeView.vue', import.meta.url), 'utf8')
const appSource = fs.readFileSync(new URL('../App.vue', import.meta.url), 'utf8')
const routerSource = fs.readFileSync(new URL('../router/index.js', import.meta.url), 'utf8')

test('厨房场景使用统一设计尺寸并按工作区宽高等比缩放', () => {
  assert.match(sceneSource, /const DESIGN_WIDTH = 1520/)
  assert.match(sceneSource, /const DESIGN_HEIGHT = 760/)
  assert.match(sceneSource, /availableWidth \/ DESIGN_WIDTH/)
  assert.match(sceneSource, /availableHeight \/ DESIGN_HEIGHT/)
  assert.match(sceneSource, /new ResizeObserver\(fitScene\)/)
  assert.match(sceneSource, /app\.stage\.scale\.set\(fittedScale\)/)
  assert.doesNotMatch(sceneSource, /MIN_SCENE_WIDTH/)
  assert.match(windowSource, /height: min\(760px/)
})

test('七个厨房区域使用稳定的站点和人物映射', () => {
  for (const stationId of ['chef', 'pantry', 'recipes', 'nutrition', 'weekly', 'hot']) {
    assert.match(sceneSource, new RegExp(`id: '${stationId}'`))
  }

  for (const characterId of ['chef-helper', 'chef-recipes', 'chef-nutrition', 'pantry', 'recipes', 'weekly', 'review', 'account', 'hot']) {
    assert.match(sceneSource, new RegExp(`id: '${characterId}'`))
  }

  for (const roomTitle of ['主厨接待大厅', '食材储藏室', '菜谱书房', '营养咨询室', '厨房协作区', '美食情报站', '厨房休息区']) {
    assert.match(sceneSource, new RegExp(roomTitle))
  }
})

test('像素布景配置覆盖七个区域的关键家具', () => {
  for (const helper of [
    'drawChalkboard',
    'drawWallClock',
    'drawSpiceJars',
    'drawDoubleFridge',
    'drawRack',
    'drawBookcase',
    'drawOvenStove',
    'drawSinkCabinet',
    'drawDeskComputer',
    'drawSmallDiningSet',
    'drawHangingPots',
    'drawDisplayCabinet',
    'drawPetRestCorner',
    'drawTeaCart'
  ]) {
    assert.match(sceneSource, new RegExp(`function ${helper}`))
  }

  assert.match(sceneSource, /drawHeroRoom\(staticLayer, characterLayer, effectsLayer, foregroundLayer, innerX, 30, innerW, 166\)/)
  assert.match(sceneSource, /drawBreakRoom\(staticLayer, innerX, 606, innerW, 132\)/)
  assert.match(sceneSource, /drawPixelGrid\(staticLayer, 0, 0, width, DESIGN_HEIGHT, 32/)
})

test('六处截图标记按三删三增落实', () => {
  assert.doesNotMatch(sceneSource, /\['菜谱', '等待你的第一道'\]/)
  assert.doesNotMatch(sceneSource, /function drawCalendarBoard/)
  assert.doesNotMatch(sceneSource, /drawWallClock\(parent, x \+ 83, y \+ 61/)

  assert.match(sceneSource, /drawDisplayCabinet\(parent, x \+ Math\.round\(w \* 0\.23\)/)
  assert.match(sceneSource, /drawPetRestCorner\(parent, x \+ Math\.round\(w \* 0\.22\)/)
  assert.match(sceneSource, /drawTeaCart\(parent, x \+ Math\.round\(w \* 0\.68\)/)
})

test('Pixi 场景按静态、特效、人物和界面分层并限制事件命中', () => {
  for (const label of [
    'kitchen-static-layer',
    'kitchen-effects-layer',
    'kitchen-character-layer',
    'kitchen-foreground-layer',
    'kitchen-ui-layer'
  ]) {
    assert.match(sceneSource, new RegExp(label))
  }

  assert.match(sceneSource, /new GraphicsContext\(\)/)
  assert.match(sceneSource, /room\.cacheAsTexture\(\{ resolution: 1, antialias: false \}\)/)
  assert.match(sceneSource, /layer\.eventMode = eventMode/)
  assert.match(sceneSource, /container\.eventMode = 'static'/)
  assert.match(sceneSource, /container\.hitArea = new Rectangle/)
})

test('所有人物固定原地踏步且全身不被工作台遮挡', () => {
  assert.doesNotMatch(sceneSource, /function updateHeroWanderers/)
  assert.doesNotMatch(sceneSource, /\bmotionX\b|\bmotionY\b|\bwander\b|\bwalking\b/)
  assert.match(sceneSource, /item\.container\.position\.set\(item\.baseX, item\.baseY\)/)
  assert.match(sceneSource, /const feetY = consoleY - 4/)
  assert.match(sceneSource, /drawCharacter\(characterLayer, station, x \+ w \* positions\[index\], feetY/)
  assert.match(sceneSource, /drawWorkstation\(foregroundLayer, x \+ w \* positions\[index\], consoleY/)
  assert.match(sceneSource, /drawPrepTable\(foregroundLayer, x \+ w \* 0\.5, y \+ h - 57, 104, station\.accent\)/)

  const heroRoomSource = sceneSource.slice(sceneSource.indexOf('function drawHeroRoom'), sceneSource.indexOf('function drawRoom'))
  assert.match(heroRoomSource, /const feetY = y \+ h - 12/)
  assert.doesNotMatch(heroRoomSource, /drawWorkstation/)
})

test('工作台两侧提供炒锅、砧板和蔬菜且人物中线保持留空', () => {
  assert.match(sceneSource, /function drawWorktopFood/)
  assert.match(sceneSource, /middle of the worktop clear/)
  assert.ok((sceneSource.match(/drawWorktopFood\(/g) || []).length >= 5)
  assert.match(sceneSource, /ingredients\.ellipse\(-20, -2/)
  assert.match(sceneSource, /body\.roundRect\(-30, 0, 60, 20/)
  assert.match(sceneSource, /screen\.roundRect\(-10, 2, 20, 13/)
})

test('首页工作区占满剩余空间且不依赖固定场景尺寸产生滚动', () => {
  assert.match(worldSource, /class="kitchen-scene-viewport" aria-label="完整厨房场景将自动适配当前窗口"/)
  assert.match(worldSource, /\.kitchen-world-page \{[\s\S]*?display: flex;[\s\S]*?height: 100%;[\s\S]*?overflow: hidden;/)
  assert.match(worldSource, /grid-template-rows: minmax\(0, 1fr\);/)
  assert.match(worldSource, /\.kitchen-scene-viewport \{[\s\S]*?min-height: 0;[\s\S]*?overflow: hidden;/)
  assert.match(appSource, /:global\(html\),[\s\S]*?:global\(#app\) \{[\s\S]*?height: 100%;/)
  assert.match(appSource, /\.app-shell \{[\s\S]*?height: 100dvh;[\s\S]*?overflow: hidden;/)
  assert.match(appSource, /\.app-body \{[\s\S]*?min-height: 0;[\s\S]*?overflow: hidden;/)
  assert.match(appSource, /\.app-shell\.kitchen-world-chrome \.app-main \{[\s\S]*?overflow: hidden;/)
  assert.match(worldSource, /max-width: 1023px\) and \(orientation: portrait\) and \(pointer: coarse\)/)
  assert.match(worldSource, /为了您的体验，请横屏使用/)
  assert.doesNotMatch(worldSource, /min-width: 1080px/)
  assert.doesNotMatch(sceneSource, /min-height: 760px/)
})

test('首页移除底部操作提示和暂停入口并释放布局空间', () => {
  assert.doesNotMatch(worldSource, /class="scene-caption"/)
  assert.doesNotMatch(worldSource, /class="motion-toggle"/)
  assert.doesNotMatch(worldSource, /操作提示/)
  assert.doesNotMatch(worldSource, /暂停动态/)
  assert.doesNotMatch(worldSource, /grid-template-rows: minmax\(0, 1fr\) auto;/)
  assert.match(worldSource, /padding: clamp\(8px, 1\.2vw, 16px\);/)
})

test('未登录主入口与登录后使用同一厨房场景', () => {
  assert.match(routerSource, /import KitchenWorldView from ['"]\.\.\/views\/KitchenWorldView\.vue['"]/)
  assert.match(routerSource, /name: 'home',[\s\S]*?component: KitchenWorldView/)
  assert.doesNotMatch(routerSource, /isLoggedIn[\s\S]*?KitchenWorldView/)
  assert.match(worldSource, /class="kitchen-world-page" aria-label="AI 智能厨房"/)
})

test('主场景不再绘制右上角状态框，侧栏为小厨灵预留底部空间', () => {
  assert.doesNotMatch(sceneSource, /drawSceneStats/)
  assert.match(appSource, /class="sidebar-navigation"/)
  assert.match(appSource, /\.app-sidebar :deep\(\.agent-widget\)/)
  assert.match(appSource, /\.sidebar-link,[\s\S]*?flex: 0 0 38px;/)
})

test('主厨料理大厅内容区域允许滚动查看完整工作台', () => {
  const stationPanelSource = fs.readFileSync(new URL('../components/kitchen/KitchenStationPanel.vue', import.meta.url), 'utf8')
  assert.match(stationPanelSource, /\.scene-feature-host--chef,[\s\S]*?overflow: auto;/)
  assert.match(stationPanelSource, /\.scene-feature-host--hot[\s\S]*?overflow: auto;/)
})

test('嵌入主厨结果只由场景容器提供原生滚动', () => {
  const stationPanelSource = fs.readFileSync(new URL('../components/kitchen/KitchenStationPanel.vue', import.meta.url), 'utf8')
  assert.match(stationPanelSource, /\.scene-feature-host--chef,[\s\S]*?overscroll-behavior: contain;/)
  assert.match(stationPanelSource, /\.scene-feature-host :deep\(\.home-page\) \{[\s\S]*?overflow: visible;/)
  assert.match(homeSource, /'is-embedded': embedded/)
  assert.match(homeSource, /\.home-page\.is-embedded\.is-result-expanded:not\(\.is-detail-view\) \.result-content \{[\s\S]*?max-height: none;[\s\S]*?overflow: visible;/)
  assert.match(homeSource, /\.home-page\.is-embedded\.is-detail-view \.recipe-sections[\s\S]*?overflow: visible;/)
  assert.match(homeSource, /function getRecipeScrollTarget\(\)/)
})

test('食材识别结果流转到阿灶时保留图片识别来源', () => {
  const stationPanelSource = fs.readFileSync(new URL('../components/kitchen/KitchenStationPanel.vue', import.meta.url), 'utf8')
  assert.match(stationPanelSource, /recognition: openChefWithRecognizedIngredients/)
  assert.match(stationPanelSource, /searchMode: 'image'/)
  assert.match(homeSource, /searchMode\.value = \['image', 'camera'\]\.includes\(props\.initialSearch\?\.searchMode\)/)
  assert.match(homeSource, /if \(!generationCompleted\.value\) \{[\s\S]*?resetRecommendationBatch\(\)/)
})

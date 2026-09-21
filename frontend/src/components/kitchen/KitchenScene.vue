<template>
  <div ref="sceneHost" class="kitchen-scene" aria-label="AI 智能厨房场景">
    <div class="scene-loading" v-if="loading">正在准备厨房……</div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { AnimatedSprite, Application, Assets, Container, Graphics, GraphicsContext, Rectangle, Sprite, Text, TextStyle } from 'pixi.js'
import { useKitchenStore } from '../../stores/kitchen'

const props = defineProps({
  motionPaused: {
    type: Boolean,
    default: false
  }
})

const emit = defineEmits(['select-station'])
const kitchen = useKitchenStore()

const sceneHost = ref(null)
const loading = ref(true)

const DESIGN_WIDTH = 1520
const DESIGN_HEIGHT = 760
const INITIAL_SPRITE_FRAME = 1
const ANIMATION_SPRITE_FRAMES = [2, 3]
const SPRITE_FRAMES = [INITIAL_SPRITE_FRAME, ...ANIMATION_SPRITE_FRAMES]
const SPRITE_NUMS = [1, 2, 3, 4, 5, 6, 7, 8, 9]
const SPRITE_DIRECTORY = '/sprites/web'
const NAMEPLATE_WIDTH_SCALE = 0.6
const NAMEPLATE_HEIGHT_SCALE = 0.85
const NAME_TEXT_SCALE = 0.95

const stations = [
  {
    id: 'chef',
    title: '主厨料理大厅',
    name: '阿灶',
    role: 'AI 主厨',
    icon: '✦',
    accent: 0xd6a43b,
    floor: 0x2a211e,
    floorAlt: 0x342923,
    description: '输入现有食材，生成并保存专属菜谱。',
    room: 'hero',
    spriteNum: 1,
    characters: [
      { id: 'chef', name: '阿灶', role: 'AI 主厨', spriteNum: 1, action: 'cook' },
      {
        id: 'chef-helper',
        name: '小灶',
        role: '食材识别员',
        guideLabel: '识别食材',
        speechLines: ['拍照识别食材吧！', '相机也能认食材哦'],
        spriteNum: 2,
        stationId: 'recognition',
        action: 'prep'
      },
      {
        id: 'chef-recipes',
        name: '小谱',
        role: '生成记录员',
        guideLabel: '生成记录',
        speechLines: ['找回上次生成记录', '继续刚才的菜谱吗？'],
        spriteNum: 4,
        stationId: 'history',
        action: 'prep'
      },
      {
        id: 'chef-nutrition',
        name: '小衡',
        role: '饮食偏好顾问',
        guideLabel: '饮食偏好',
        speechLines: ['口味和忌口交给我', '先设置饮食偏好吧'],
        spriteNum: 5,
        stationId: 'diet-preference',
        action: 'prep'
      }
    ]
  },
  {
    id: 'pantry',
    title: '食材储藏室',
    name: '小仓',
    role: '食材管家',
    icon: '▣',
    accent: 0x68a873,
    floor: 0x283d37,
    floorAlt: 0x304b42,
    description: '查看库存、临期食材和入库记录。',
    room: 'standard',
    spriteNum: 3,
    characters: [{ id: 'pantry', name: '小仓', role: '食材管家', spriteNum: 3, action: 'work' }]
  },
  {
    id: 'recipes',
    title: '菜谱书房',
    name: '阿笺',
    role: '菜谱管理员',
    icon: '▤',
    accent: 0xb083c7,
    floor: 0x33283c,
    floorAlt: 0x42314c,
    description: '整理已保存菜谱、收藏夹和分享记录。',
    room: 'standard',
    spriteNum: 4,
    characters: [{ id: 'recipes', name: '阿笺', role: '菜谱管理员', spriteNum: 4, action: 'work' }]
  },
  {
    id: 'nutrition',
    title: '营养咨询室',
    name: '衡衡',
    role: '营养师',
    icon: '♥',
    accent: 0xe2816c,
    floor: 0x3e2c31,
    floorAlt: 0x51363a,
    description: '维护健康档案和每日营养目标。',
    room: 'standard',
    spriteNum: 5,
    characters: [{ id: 'nutrition', name: '衡衡', role: '营养师', spriteNum: 5, action: 'work' }]
  },
  {
    id: 'weekly',
    title: '厨房协作区',
    name: '周周',
    role: '菜单 · 品鉴 · 服务',
    icon: '▦',
    accent: 0x6d9cc3,
    floor: 0x293746,
    floorAlt: 0x314559,
    description: '安排菜单、品鉴成品，并处理厨房服务。',
    room: 'wide',
    spriteNum: 6,
    characters: [
      { id: 'weekly', name: '周周', role: '菜单规划师', spriteNum: 6, action: 'work' },
      { id: 'review', name: '味味', role: '成品品鉴员', spriteNum: 7, stationId: 'review', action: 'work' },
      { id: 'account', name: '小管', role: '厨房管家', spriteNum: 8, stationId: 'account', action: 'work' }
    ]
  },
  {
    id: 'hot',
    title: '美食情报站',
    name: '椒椒',
    role: '市场观察员',
    icon: '♨',
    accent: 0xd48c52,
    floor: 0x3b3026,
    floorAlt: 0x4b392b,
    description: '查看全站热门食材和搜索趋势。',
    room: 'standard',
    spriteNum: 9,
    characters: [{ id: 'hot', name: '椒椒', role: '市场观察员', spriteNum: 9, action: 'work' }]
  }
]

let app
let resizeObserver
let animationTick
let characterVisuals = []
let workstationVisuals = []
let cookingVisuals = []
let speechVisuals = []
let activeSpeech = null
let nextSpeechAt = 0
let reducedMotionQuery
let prefersReducedMotion = false
let stopCharacterNameWatch
let headerGuideText
let spriteLoadingCancelled = false
const spriteTextures = new Map()
const sharedGraphicsContexts = new Map()

onMounted(async () => {
  if (!sceneHost.value) return

  app = new Application()
  await app.init({
    width: DESIGN_WIDTH,
    height: DESIGN_HEIGHT,
    backgroundAlpha: 0,
    antialias: false,
    resolution: Math.min(window.devicePixelRatio || 1, 2),
    autoDensity: true
  })

  await loadSpriteTextures([INITIAL_SPRITE_FRAME])

  const canvas = app.canvas
  canvas.className = 'kitchen-scene-canvas'
  canvas.setAttribute('role', 'img')
  canvas.setAttribute('aria-label', '点击厨房中的人物打开对应功能')
  sceneHost.value.appendChild(canvas)
  loading.value = false

  reducedMotionQuery = window.matchMedia('(prefers-reduced-motion: reduce)')
  prefersReducedMotion = reducedMotionQuery.matches
  reducedMotionQuery.addEventListener?.('change', handleReducedMotionChange)

  const fitScene = () => {
    if (!app || app.destroyed || !sceneHost.value) return
    const availableWidth = sceneHost.value.clientWidth
    const availableHeight = sceneHost.value.clientHeight
    if (availableWidth <= 0 || availableHeight <= 0) return

    const scale = Math.min(
      availableWidth / DESIGN_WIDTH,
      availableHeight / DESIGN_HEIGHT
    )
    const displayWidth = Math.max(1, Math.floor(DESIGN_WIDTH * scale))
    const displayHeight = Math.max(1, Math.floor(DESIGN_HEIGHT * scale))
    const fittedScale = Math.min(
      displayWidth / DESIGN_WIDTH,
      displayHeight / DESIGN_HEIGHT
    )

    app.renderer.resize(displayWidth, displayHeight)
    app.stage.scale.set(fittedScale)
  }

  const draw = () => {
    if (!app || app.destroyed) return
    app.stage.removeChildren().forEach((child) => child.destroy({ children: true }))
    characterVisuals = []
    workstationVisuals = []
    cookingVisuals = []
    speechVisuals = []
    activeSpeech = null
    headerGuideText = null
    nextSpeechAt = animationTick + randomBetween(30, 60)
    drawKitchen(app.stage, DESIGN_WIDTH)
    fitScene()
  }

  animationTick = 0
  draw()
  void loadAnimationSpriteTextures(draw)
  stopCharacterNameWatch = watch(() => kitchen.characterNames, syncCharacterNames, { deep: true })
  resizeObserver = new ResizeObserver(fitScene)
  resizeObserver.observe(sceneHost.value)

  app.ticker.add(() => {
    const motionDisabled = props.motionPaused || prefersReducedMotion
    if (!motionDisabled) animationTick += app.ticker.deltaTime
    updateRandomSpeech()
    characterVisuals.forEach((item, index) => {
      item.container.position.set(item.baseX, item.baseY)
      item.container.rotation = 0
      item.glow.alpha = item.hovered ? 0.94 : 0.2 + (Math.sin(animationTick * 0.06 + index) + 1) * 0.06
      item.container.scale.set(item.pressed ? 0.98 : item.hovered ? 1.045 : 1)
      if (item.sprite) {
        item.sprite.animationSpeed = motionDisabled ? 0 : 0.08
      }
    })
    if (!motionDisabled) {
      workstationVisuals.forEach((item, index) => {
        item.screen.alpha = 0.65 + (Math.sin(animationTick * 0.08 + index * 0.8) + 1) * 0.12
        item.light.alpha = 0.35 + (Math.sin(animationTick * 0.1 + index) + 1) * 0.2
      })
      cookingVisuals.forEach((item) => {
        const motion = Math.sin(animationTick * 0.16) * 1.6
        item.pan.position.x = item.baseX + motion
        item.pan.rotation = Math.sin(animationTick * 0.12) * 0.08
        item.steam.alpha = 0.35 + (Math.sin(animationTick * 0.14) + 1) * 0.16
      })
    }
  })
})

async function loadSpriteTextures(frames) {
  const requests = []
  SPRITE_NUMS.forEach((spriteNum) => {
    frames.forEach((frame) => {
      const fileName = `${spriteNum}-D-${frame}.png`
      requests.push(
        Assets.load(`${SPRITE_DIRECTORY}/${fileName}`)
          .then((texture) => spriteTextures.set(`${spriteNum}-D-${frame}`, texture))
          .catch(() => null)
      )
    })
  })
  await Promise.all(requests)
}

async function loadAnimationSpriteTextures(redraw) {
  await waitForScenePaint()
  if (spriteLoadingCancelled) return

  await loadSpriteTextures(ANIMATION_SPRITE_FRAMES)
  if (!spriteLoadingCancelled && app && !app.destroyed) redraw()
}

function waitForScenePaint() {
  return new Promise((resolve) => {
    requestAnimationFrame(() => requestAnimationFrame(resolve))
  })
}

function getSpriteFrames(spriteNum) {
  return SPRITE_FRAMES.map((frame) => spriteTextures.get(`${spriteNum}-D-${frame}`)).filter(Boolean)
}

onBeforeUnmount(() => {
  spriteLoadingCancelled = true
  resizeObserver?.disconnect()
  stopCharacterNameWatch?.()
  reducedMotionQuery?.removeEventListener?.('change', handleReducedMotionChange)
  if (app) {
    app.destroy(true)
    app = null
  }
  sharedGraphicsContexts.forEach((context) => context.destroy())
  sharedGraphicsContexts.clear()
})

function drawKitchen(stage, width) {
  stage.sortableChildren = true
  const staticLayer = createSceneLayer(stage, 'kitchen-static-layer', 0, 'none')
  const characterLayer = createSceneLayer(stage, 'kitchen-character-layer', 20, 'passive')
  const foregroundLayer = createSceneLayer(stage, 'kitchen-foreground-layer', 25, 'none')
  const effectsLayer = createSceneLayer(stage, 'kitchen-effects-layer', 26, 'none')
  const uiLayer = createSceneLayer(stage, 'kitchen-ui-layer', 30, 'none')

  const outer = new Graphics()
  outer.rect(0, 0, width, DESIGN_HEIGHT).fill(0xe6d2a8)
  staticLayer.addChild(outer)

  // Keep the checkerboard as a quiet floor texture so the furniture reads first.
  drawPixelGrid(staticLayer, 0, 0, width, DESIGN_HEIGHT, 32, 0xe6d2a8, 0xe0cfa4)
  drawFrame(staticLayer, 12, 12, width - 24, DESIGN_HEIGHT - 24)

  const innerX = 22
  const innerW = width - 44
  drawHeroRoom(staticLayer, characterLayer, effectsLayer, foregroundLayer, innerX, 30, innerW, 166)

  const gap = 12
  const columns = 3
  const colW = (innerW - gap * (columns - 1)) / columns
  const rowY = 204
  const rowH = 190
  drawRoom(staticLayer, characterLayer, effectsLayer, foregroundLayer, stations[1], innerX, rowY, colW, rowH)
  drawRoom(staticLayer, characterLayer, effectsLayer, foregroundLayer, stations[2], innerX + colW + gap, rowY, colW, rowH)
  drawRoom(staticLayer, characterLayer, effectsLayer, foregroundLayer, stations[3], innerX + (colW + gap) * 2, rowY, colW, rowH)

  const lowerY = 404
  const wideW = colW * 2 + gap
  drawRoom(staticLayer, characterLayer, effectsLayer, foregroundLayer, stations[4], innerX, lowerY, wideW, 190)
  drawRoom(staticLayer, characterLayer, effectsLayer, foregroundLayer, stations[5], innerX + wideW + gap, lowerY, colW, 190)

  drawBreakRoom(staticLayer, innerX, 606, innerW, 132)
  drawOfficeHeader(uiLayer, width)
}

function createSceneLayer(stage, label, zIndex, eventMode) {
  const layer = new Container({ label })
  layer.zIndex = zIndex
  layer.eventMode = eventMode
  if (eventMode === 'none') layer.interactiveChildren = false
  stage.addChild(layer)
  return layer
}

function createStaticRoom(parent, label) {
  const room = new Container({ label })
  room.eventMode = 'none'
  room.interactiveChildren = false
  parent.addChild(room)
  return room
}

function cacheStaticRoom(room) {
  room.cacheAsTexture({ resolution: 1, antialias: false })
}

function drawFrame(parent, x, y, w, h) {
  const frame = new Graphics()
  frame.rect(x, y, w, h).fill({ color: 0x221d1a, alpha: 0.92 })
  frame.rect(x, y, w, h).stroke({ width: 4, color: 0x70533d, alpha: 0.95 })
  frame.rect(x + 5, y + 5, w - 10, h - 10).stroke({ width: 1, color: 0xb68c55, alpha: 0.6 })
  parent.addChild(frame)

  const topBand = new Graphics()
  topBand.rect(x + 7, y + 7, w - 14, 12).fill(0x43362c)
  for (let i = 0; i < Math.floor((w - 30) / 26); i += 1) {
    topBand.moveTo(x + 18 + i * 26, y + 20)
      .lineTo(x + 24 + i * 26, y + 20)
      .lineTo(x + 21 + i * 26, y + 25)
      .fill(i % 2 ? 0x936d42 : 0xc5964e)
  }
  parent.addChild(topBand)
}

function drawOfficeHeader(parent, width) {
  const header = new Graphics()
  header.roundRect(20, 14, width - 40, 14, 4).fill({ color: 0x302621, alpha: 0.96 })
  header.roundRect(20, 14, width - 40, 14, 4).stroke({ width: 1, color: 0xb68c55, alpha: 0.9 })
  header.rect(28, 18, 5, 5).fill(0xd6a43b)
  header.rect(width - 44, 18, 5, 5).fill(0x68a873)
  parent.addChild(header)
  addText(parent, '像素厨房 · 办公区', 40, 17, 7, 0xf4dfb3, true)
  headerGuideText = addText(parent, getHeroGuideText(), width / 2, 17, 7, 0xf1c36a, true, true)
  addText(parent, '在线 · 点击人物开始工作', width - 210, 17, 7, 0xc6e0c7, true)
}

function drawHeroRoom(staticLayer, characterLayer, effectsLayer, foregroundLayer, x, y, w, h) {
  const parent = createStaticRoom(staticLayer, 'hero-room-static')
  const room = new Graphics()
  room.rect(x, y, w, h).fill(0x211b1d)
  drawPixelGrid(room, x, y, w, h, 28, 0x211b1d, 0x2a2327)
  room.rect(x, y, w, 27).fill(0x46362d)
  room.rect(x, y + 25, w, 2).fill(0xd1a25a)
  room.rect(x, y, w, h).stroke({ width: 2, color: 0x93633f })
  parent.addChild(room)

  addRoomLabel(parent, x + 10, y + 7, '主厨接待大厅', '今日 AI 菜谱工作台', 0xd6a43b)
  drawChalkboard(parent, x + 24, y + 39, 150, 48, '好食材＝好心情', 0xd6a43b)
  drawWallClock(parent, x + w * 0.46, y + 51, 15, 0xf1c36a)
  drawSpiceJars(parent, x + 26, y + 101, 4, 0xd6a43b)
  addText(parent, '准备好食材了吗？', x + 195, y + 41, 12, 0xf3dfb9, true)
  addText(parent, '点击人物，开始你的专属菜谱旅程', x + 195, y + 59, 8, 0xc8b496, false)
  drawDisplayCabinet(parent, x + Math.round(w * 0.23), y + 91, 154, 0xd6a43b)

  drawRack(parent, x + w * 0.69, y + 38, 116, 34, 0xd6a43b)
  drawRecommendedBoard(parent, x + w * 0.72, y + 78, 126, 35, 0xd6a43b)
  drawStickyNotes(parent, x + w * 0.835, y + 39, 2, 0xf4cf75)
  drawDoubleFridge(parent, x + w - 88, y + 39, 62, 78)
  drawCookingCounter(foregroundLayer, x + w * 0.69, y + 117, Math.min(230, w * 0.22), 0xd6a43b)

  const chefPositions = [0.41, 0.49, 0.57, 0.65].map((ratio) => x + w * ratio)
  stations[0].characters.forEach((character, index) => {
    const feetY = y + h - 12
    const visual = drawCharacter(characterLayer, stations[0], chefPositions[index], feetY, 0.94, character)
    characterVisuals.push(visual)
    if (visual.speechBubbles.length) speechVisuals.push(visual)
  })

  cacheStaticRoom(parent)
  const cooking = drawCookingPan(effectsLayer, x + w * 0.79, y + 112)
  cookingVisuals.push(cooking)
}

function drawRoom(staticLayer, characterLayer, effectsLayer, foregroundLayer, station, x, y, w, h) {
  const parent = createStaticRoom(staticLayer, `${station.id}-room-static`)
  const room = new Graphics()
  room.rect(x, y, w, h).fill(station.floor)
  drawPixelGrid(room, x, y, w, h, 24, station.floor, station.floorAlt)
  room.rect(x, y, w, 30).fill({ color: station.accent, alpha: 0.28 })
  room.rect(x, y + 28, w, 2).fill(station.accent)
  room.rect(x, y, w, h).stroke({ width: 2, color: station.accent, alpha: 0.78 })
  parent.addChild(room)

  addRoomLabel(parent, x + w / 2, y + 8, station.title, station.role, station.accent, true)
  drawHangingLamp(parent, x + w / 2, y + 34, station.accent)
  drawWallDecor(parent, station.id, x, y, w)

  const characters = station.characters || [{ id: station.id, name: station.name, role: station.role, spriteNum: station.spriteNum }]
  const positions = characters.length === 3 ? [0.32, 0.5, 0.68] : characters.length === 2 ? [0.4, 0.6] : [0.5]
  characters.forEach((character, index) => {
    const consoleY = station.id === 'weekly' ? y + h - 48 : y + h - 57
    const feetY = consoleY - 4
    const char = drawCharacter(characterLayer, station, x + w * positions[index], feetY, 0.94, character)
    characterVisuals.push(char)
    if (station.id === 'weekly') {
      const desk = drawWorkstation(foregroundLayer, x + w * positions[index], consoleY, station.accent)
      workstationVisuals.push(desk)
    }
  })

  if (station.id === 'pantry') {
    drawDoubleFridge(parent, x + w - 61, y + 51, 45, 88)
    drawRack(parent, x + 16, y + 49, 82, 52, station.accent)
    drawSpiceJars(parent, x + 22, y + 107, 4, station.accent)
    drawCuttingBoard(parent, x + 112, y + 55, 28, 35)
    drawHangingUtensils(parent, x + 146, y + 52, 3, station.accent)
    drawPrepTable(foregroundLayer, x + w * 0.5, y + h - 57, 104, station.accent)
    drawWorktopFood(foregroundLayer, x + w * 0.5, y + h - 57, 104, station.accent)
    drawCrate(parent, x + 18, y + h - 46, station.accent)
    drawRiceBag(parent, x + w - 112, y + h - 47, station.accent)
    drawStool(parent, x + w * 0.5 + 56, y + h - 34, station.accent)
  } else if (station.id === 'recipes') {
    drawBookcase(parent, x + 14, y + 48, 45, 105, station.accent)
    drawBookcase(parent, x + w - 59, y + 48, 45, 105, station.accent)
    drawPictureFrame(parent, x + 68, y + 49, 44, 28, station.accent)
    drawPictureFrame(parent, x + w - 112, y + 49, 44, 28, station.accent)
    drawPlant(parent, x + 74, y + 119)
    drawBookPile(parent, x + w - 109, y + 127, station.accent)
    drawRecipeDesk(foregroundLayer, x + w * 0.5, y + h - 57, 112, station.accent, true)
  } else if (station.id === 'nutrition') {
    drawRack(parent, x + 14, y + 50, 70, 62, station.accent)
    drawSpiceJars(parent, x + 20, y + 118, 3, station.accent)
    drawHangingUtensils(parent, x + w - 83, y + 49, 4, station.accent)
    drawOvenStove(parent, x + w - 77, y + 98, station.accent)
    drawNutritionPoster(parent, x + 101, y + 48, 62, 52, station.accent)
    drawPlant(parent, x + w - 32, y + 144)
    drawExperimentTable(foregroundLayer, x + w * 0.5, y + h - 57, 118, station.accent, true)
    drawScale(parent, x + 99, y + 121, station.accent)
  } else if (station.id === 'weekly') {
    drawBulletin(parent, x + 16, y + 48, station.accent, '任务公告板')
    drawShelfWithBins(parent, x + 166, y + 49, 118, 44, station.accent)
    drawWallUtensilRack(parent, x + 170, y + 111, station.accent)
    drawSinkCabinet(parent, x + w - 142, y + 105, 126, 45, station.accent)
    drawTrashBin(parent, x + w - 29, y + h - 49, station.accent)
    drawPlant(parent, x + w - 27, y + 52)
    drawDishStack(parent, x + w - 120, y + 84, station.accent)
  } else if (station.id === 'hot') {
    drawBulletin(parent, x + 16, y + 49, station.accent, '菜品情报板')
    drawBookcase(parent, x + 18, y + 125, 42, 52, station.accent)
    drawStickyNotes(parent, x + 103, y + 50, 3, 0xf4cf75)
    drawDeskComputer(foregroundLayer, x + w * 0.5, y + h - 57, 122, station.accent, true)
    drawBookPile(parent, x + w - 119, y + 126, station.accent)
    drawStorageCabinet(parent, x + w - 62, y + 47, 46, 106, station.accent)
  }
  cacheStaticRoom(parent)
}

function drawBreakRoom(staticLayer, x, y, w, h) {
  const parent = createStaticRoom(staticLayer, 'break-room-static')
  const room = new Graphics()
  room.rect(x, y, w, h).fill(0x211d1a)
  drawPixelGrid(room, x, y, w, h, 24, 0x211d1a, 0x2a2420)
  room.rect(x, y, w, 24).fill(0x514135)
  room.rect(x, y + 23, w, 2).fill(0xb98a4c)
  room.rect(x, y, w, h).stroke({ width: 2, color: 0x816044 })
  parent.addChild(room)
  addRoomLabel(parent, x + 12, y + 6, '厨房休息区', '让推荐灵感稍微喘口气', 0x9bb58a)
  drawSmallDiningSet(parent, x + 34, y + 52, 136, 0x9bb58a)
  drawStickyNotes(parent, x + 215, y + 39, 3, 0xf4cf75)
  drawPetRestCorner(parent, x + Math.round(w * 0.22), y + 69, 112, 0x9bb58a)
  drawLongTable(parent, x + w * 0.48, y + 49, Math.min(330, w * 0.3), 0xb98a4c)
  drawTeaSet(parent, x + w * 0.48, y + 43, 0xb98a4c)
  drawTeaCart(parent, x + Math.round(w * 0.68), y + 38, 132, 0xb98a4c)
  drawShelfWithBins(parent, x + w - 200, y + 41, 166, 62, 0x9bb58a)
  drawHangingPots(parent, x + w - 177, y + 45, 3, 0xb98a4c)
  drawIngredientBasket(parent, x + w - 113, y + 104, 0x9bb58a)
  drawSmallAppliance(parent, x + w - 65, y + 101, 0x9bb58a)
  drawPictureFrame(parent, x + w - 68, y + 39, 42, 30, 0x9bb58a)
  addText(parent, '今天也要好好吃饭。', x + 36, y + 111, 10, 0xe8d7b7, true)
  cacheStaticRoom(parent)
}

function drawCharacter(parent, station, x, feetY, scale, character = null) {
  const actor = character || station
  const displayName = kitchen.getCharacterName(actor.id, actor.name || station.name)
  const targetHeight = 58 * scale
  const spriteNum = actor.spriteNum || station.spriteNum
  const spriteFrames = getSpriteFrames(spriteNum)
  const container = new Container()
  container.position.set(x, feetY)
  container.eventMode = 'static'
  container.cursor = 'pointer'

  const glow = new Graphics()
  glow.ellipse(0, 4, 30 * scale, 7 * scale).fill({ color: station.accent, alpha: 0.2 })
  glow.ellipse(0, 4, 24 * scale, 4 * scale).fill({ color: 0x090807, alpha: 0.5 })
  container.addChild(glow)

  let animatedSprite = null
  if (spriteFrames.length) {
    const sprite = spriteFrames.length > 1 ? new AnimatedSprite(spriteFrames) : new Sprite(spriteFrames[0])
    sprite.anchor.set(0.5, 1)
    const fitSpriteToHeight = () => {
      if (sprite.texture?.height) {
        sprite.scale.set(targetHeight / sprite.texture.height)
      }
    }
    // Keep every frame fitted to the same display height, including fallback
    // assets that may not use the aligned web canvases.
    fitSpriteToHeight()
    if (sprite instanceof AnimatedSprite) {
      sprite.onFrameChange = fitSpriteToHeight
      sprite.animationSpeed = 0.08
      sprite.play()
      animatedSprite = sprite
    }
    container.addChild(sprite)
  }

  if (!spriteFrames.length) {
    const body = new Graphics()
    body.rect(-13 * scale, -42 * scale, 26 * scale, 27 * scale).fill(station.id === 'chef' ? 0xf0e2bf : 0xb7d0c3)
    body.rect(-13 * scale, -42 * scale, 26 * scale, 27 * scale).stroke({ width: 1.4 * scale, color: 0x2d201a })
    body.rect(-8 * scale, -16 * scale, 6 * scale, 16 * scale).fill(0x5e493b)
    body.rect(2 * scale, -16 * scale, 6 * scale, 16 * scale).fill(0x5e493b)
    body.rect(-18 * scale, -37 * scale, 5 * scale, 15 * scale).fill(0xc68d68)
    body.rect(13 * scale, -37 * scale, 5 * scale, 15 * scale).fill(0xc68d68)
    container.addChild(body)

    const head = new Graphics()
    head.rect(-12 * scale, -67 * scale, 24 * scale, 24 * scale).fill(0xd99a78)
    head.rect(-12 * scale, -67 * scale, 24 * scale, 24 * scale).stroke({ width: 1.4 * scale, color: 0x2d201a })
    head.rect(-8 * scale, -57 * scale, 3 * scale, 3 * scale).fill(0x2b211e)
    head.rect(5 * scale, -57 * scale, 3 * scale, 3 * scale).fill(0x2b211e)
    head.rect(-4 * scale, -48 * scale, 8 * scale, 2 * scale).fill(0x7d4539)
    head.rect(-14 * scale, -71 * scale, 28 * scale, 8 * scale).fill(station.id === 'chef' ? 0xd6a43b : station.accent)
    head.rect(-10 * scale, -74 * scale, 20 * scale, 4 * scale).fill(station.id === 'chef' ? 0xf2c966 : station.accent)

    if (station.id === 'chef') {
      head.rect(-3 * scale, -78 * scale, 6 * scale, 5 * scale).fill(0xffe7a3)
      head.rect(-8 * scale, -82 * scale, 16 * scale, 4 * scale).fill(0xf0c362)
    } else if (station.id === 'nutrition') {
      head.rect(-14 * scale, -64 * scale, 4 * scale, 15 * scale).fill(0x2a2524)
      head.rect(10 * scale, -64 * scale, 4 * scale, 15 * scale).fill(0x2a2524)
    } else if (station.id === 'pantry') {
      head.rect(-16 * scale, -72 * scale, 32 * scale, 5 * scale).fill(0x6d9b72)
    }
    container.addChild(head)
  }

  const hasGuideLabel = Boolean(actor.guideLabel)
  const baseLabelWidth = (hasGuideLabel ? 76 : 62) * scale * NAMEPLATE_WIDTH_SCALE
  const labelHeight = (hasGuideLabel ? 26 : 15) * scale * NAMEPLATE_HEIGHT_SCALE
  const labelY = -targetHeight - labelHeight
  const name = new Text({
    text: displayName,
    style: new TextStyle({ fontFamily: 'Microsoft YaHei, sans-serif', fontSize: (hasGuideLabel ? 8 : 9) * scale * NAME_TEXT_SCALE, fontWeight: '900', fill: 0x392b24 })
  })
  name.anchor.set(0.5, 0.5)
  name.position.set(0, labelY + labelHeight * (hasGuideLabel ? 0.27 : 0.5))

  let guide = null
  if (hasGuideLabel) {
    guide = new Text({
      text: actor.guideLabel,
      style: new TextStyle({ fontFamily: 'Microsoft YaHei, sans-serif', fontSize: 7.5 * scale * NAME_TEXT_SCALE, fontWeight: '900', fill: 0x4a3022 })
    })
    guide.anchor.set(0.5, 0.5)
    guide.position.set(0, labelY + labelHeight * 0.72)
  }

  const nameBg = new Graphics()
  const nameplate = { background: nameBg, nameText: name, guideText: guide, baseWidth: baseLabelWidth, height: labelHeight, y: labelY, scale, accent: station.accent, hasGuideLabel }
  drawNameplateBackground(nameplate)
  container.addChild(nameBg)
  container.addChild(name)
  if (guide) container.addChild(guide)

  const speechBubbles = (actor.speechLines || []).map((line) => {
    const bubble = createSpeechBubble(line, labelY - 24 * scale, scale)
    bubble.visible = false
    container.addChild(bubble)
    return bubble
  })

  const glowFrame = new Graphics()
  glowFrame.roundRect(-42 * scale, labelY - 5 * scale, 84 * scale, targetHeight + labelHeight + 10 * scale, 5).stroke({ width: 2, color: station.accent, alpha: 0.2 })
  glowFrame.visible = false
  container.addChild(glowFrame)
  container.hitArea = new Rectangle(
    -42 * scale,
    labelY - 5 * scale,
    84 * scale,
    targetHeight + labelHeight + 10 * scale
  )

  const visual = {
    container,
    baseX: x,
    baseY: feetY,
    glow: glowFrame,
    hovered: false,
    pressed: false,
    action: actor.action || 'work',
    sprite: animatedSprite,
    actorId: actor.id,
    nameText: name,
    nameplate,
    speechBubbles
  }
  container.on('pointerover', () => {
    visual.hovered = true
    glowFrame.visible = true
  })
  container.on('pointerout', () => {
    visual.hovered = false
    glowFrame.visible = false
  })
  container.on('pointerdown', () => {
    visual.pressed = true
    glowFrame.visible = true
    emit('select-station', actor.stationId || station.id)
  })
  container.on('pointerup', () => {
    visual.pressed = false
  })
  parent.addChild(container)
  return visual
}

function updateRandomSpeech() {
  if (props.motionPaused || prefersReducedMotion) {
    if (activeSpeech) activeSpeech.bubble.visible = false
    activeSpeech = null
    nextSpeechAt = animationTick + randomBetween(60, 150)
    return
  }

  if (activeSpeech) {
    const { bubble, showAt, hideAt } = activeSpeech
    if (animationTick >= hideAt) {
      bubble.visible = false
      activeSpeech = null
      nextSpeechAt = animationTick + randomBetween(200, 360)
      return
    }
    const fadeIn = Math.min(1, (animationTick - showAt) / 10)
    const fadeOut = Math.min(1, (hideAt - animationTick) / 12)
    bubble.alpha = Math.max(0, Math.min(fadeIn, fadeOut))
    return
  }

  if (animationTick < nextSpeechAt || !speechVisuals.length) return
  const speaker = speechVisuals[Math.floor(Math.random() * speechVisuals.length)]
  const bubble = speaker.speechBubbles[Math.floor(Math.random() * speaker.speechBubbles.length)]
  bubble.alpha = 0
  bubble.visible = true
  activeSpeech = {
    bubble,
    showAt: animationTick,
    hideAt: animationTick + randomBetween(150, 220)
  }
}

function handleReducedMotionChange(event) {
  prefersReducedMotion = event.matches
}

function syncCharacterNames() {
  characterVisuals.forEach((item) => {
    if (!item.nameText) return
    item.nameText.text = kitchen.getCharacterName(item.actorId, item.nameText.text)
    if (item.nameplate) drawNameplateBackground(item.nameplate)
  })
  if (headerGuideText) headerGuideText.text = getHeroGuideText()
}

function drawNameplateBackground(nameplate) {
  const contentWidth = Math.max(nameplate.nameText.width, nameplate.guideText?.width || 0)
  const width = Math.max(nameplate.baseWidth, contentWidth + 10 * nameplate.scale)
  const { background, height, y, accent, hasGuideLabel } = nameplate
  background.clear()
  background.roundRect(-width / 2, y, width, height, 3).fill(0xf3ead4)
  background.roundRect(-width / 2, y, width, height, 3).stroke({ width: 1, color: accent })
  if (hasGuideLabel) {
    background.rect(-width / 2 + 1, y + height * 0.5, width - 2, height * 0.42).fill({ color: accent, alpha: 0.3 })
  }
}

function getHeroGuideText() {
  return `${kitchen.getCharacterName('chef-helper', '小灶')}识食材 · ${kitchen.getCharacterName('chef-recipes', '小谱')}查记录 · ${kitchen.getCharacterName('chef-nutrition', '小衡')}调偏好`
}

function randomBetween(min, max) {
  return min + Math.random() * (max - min)
}

function addRoomLabel(parent, x, y, title, subtitle, accent, centered = false) {
  const label = new Graphics()
  const labelW = Math.max(136, title.length * 12 + 28)
  const labelX = centered ? x - labelW / 2 : x
  label.roundRect(labelX, y, labelW, 27, 5).fill(accent)
  label.roundRect(labelX + 2, y + 2, labelW - 4, 23, 4).fill({ color: 0x281f1b, alpha: 0.3 })
  parent.addChild(label)
  addText(parent, title, centered ? x : x + 10, y + 4, 10, 0xfff8e7, true, centered)
  addText(parent, subtitle, centered ? x : x + 10, y + 16, 7, 0xf1dfbd, false, centered)
}

function createSpeechBubble(text, y, scale) {
  const parent = new Container()
  parent.eventMode = 'none'
  const width = Math.max(96, Math.min(132, text.length * 8 + 18)) * scale
  const height = 20 * scale
  const x = -width / 2
  const bubble = new Graphics()
  bubble.roundRect(x, y, width, height, 5).fill(0xf8f3e7)
  bubble.roundRect(x, y, width, height, 5).stroke({ width: 1.2, color: 0xb08350 })
  bubble.moveTo(-6 * scale, y + height).lineTo(0, y + height + 6 * scale).lineTo(6 * scale, y + height).fill(0xf8f3e7)
  parent.addChild(bubble)
  addText(parent, text, 0, y + 5 * scale, 7.5 * scale, 0x4a392c, true, true)
  return parent
}

function addText(parent, text, x, y, size, fill, bold = false, centered = false) {
  const node = new Text({
    text,
    style: new TextStyle({
      fontFamily: 'Microsoft YaHei, sans-serif',
      fontSize: size,
      fontWeight: bold ? '900' : '600',
      fill,
      letterSpacing: 0.3
    })
  })
  if (centered) node.anchor.set(0.5, 0)
  node.position.set(x, y)
  parent.addChild(node)
  return node
}

function getSharedGraphicsContext(key, build) {
  let context = sharedGraphicsContexts.get(key)
  if (!context) {
    context = new GraphicsContext()
    build(context)
    sharedGraphicsContexts.set(key, context)
  }
  return context
}

function addSharedGraphic(parent, context, x, y) {
  const graphic = new Graphics({ context, roundPixels: true })
  graphic.position.set(Math.round(x), Math.round(y))
  graphic.eventMode = 'none'
  parent.addChild(graphic)
  return graphic
}

function drawPixelGrid(parent, x, y, w, h, tile, colorA, colorB) {
  const target = typeof parent.rect === 'function' ? parent : new Graphics()
  for (let ty = y; ty < y + h; ty += tile) {
    for (let tx = x; tx < x + w; tx += tile) {
      target.rect(tx, ty, tile, tile).fill(((tx - x) / tile + (ty - y) / tile) % 2 === 0 ? colorA : colorB)
      target.rect(tx, ty, tile, 1).fill({ color: 0xffffff, alpha: 0.06 })
      target.rect(tx, ty + tile - 1, tile, 1).fill({ color: 0x000000, alpha: 0.08 })
    }
  }
  if (target !== parent) parent.addChild(target)
}

function drawWallDecor(parent, id, x, y, w) {
  const accent = stations.find((station) => station.id === id)?.accent || 0xd6a43b
  if (id === 'pantry') drawHangingPots(parent, x + w - 105, y + 42, 2, accent)
  if (id === 'recipes') drawPictureFrame(parent, x + w / 2 - 22, y + 45, 44, 28, accent)
  if (id === 'nutrition') drawPictureFrame(parent, x + w / 2 - 22, y + 45, 44, 28, accent)
  if (id === 'weekly') drawPictureFrame(parent, x + w - 88, y + 43, 50, 30, accent)
  if (id === 'hot') drawPictureFrame(parent, x + w / 2 - 24, y + 43, 48, 30, accent)
}

function drawHangingLamp(parent, x, y, accent) {
  const g = new Graphics({ roundPixels: true })
  g.rect(x - 1, y - 9, 2, 9).fill(0xd8bd7b)
  g.rect(x - 4, y - 10, 8, 3).fill(0x5a4232)
  g.rect(x - 12, y, 24, 4).fill(0x3a2b25)
  g.rect(x - 8, y + 4, 16, 9).fill(accent)
  g.rect(x - 10, y + 11, 20, 3).fill(0x5a3e2d)
  g.rect(x - 5, y + 6, 10, 4).fill(0xffe7a3)
  g.rect(x - 2, y + 13, 4, 3).fill(0xf6d26f)
  g.rect(x - 15, y + 15, 6, 2).fill(0xb58d58)
  g.rect(x + 9, y + 15, 6, 2).fill(0xb58d58)
  parent.addChild(g)
}

function drawWallClock(parent, x, y, radius, accent) {
  const g = new Graphics()
  g.circle(x, y, radius + 3).fill(0x2f2724)
  g.circle(x, y, radius).fill(0xf0e5c8)
  g.circle(x, y, radius).stroke({ width: 2, color: accent })
  g.rect(x - 1, y - radius + 4, 2, radius - 4).fill(0x5f4734)
  g.rect(x, y - 1, radius - 4, 2).fill(0x5f4734)
  g.rect(x - 2, y - 2, 4, 4).fill(accent)
  parent.addChild(g)
}

function drawChalkboard(parent, x, y, w, h, title, accent) {
  const g = new Graphics()
  g.rect(x + 4, y + 5, w, h).fill(0x160f12)
  g.rect(x, y, w, h).fill(0x2e4d43)
  g.rect(x, y, w, h).stroke({ width: 2, color: accent })
  g.rect(x + 8, y + h - 9, w - 16, 3).fill(0x7d9d7b)
  parent.addChild(g)
  addText(parent, title, x + w / 2, y + 9, 9, 0xfff3c4, true, true)
}

function drawRecommendedBoard(parent, x, y, w, h, accent) {
  const g = new Graphics()
  g.rect(x + 3, y + 4, w, h).fill(0x322019)
  g.rect(x, y, w, h).fill(0x253c35)
  g.rect(x, y, w, h).stroke({ width: 2, color: accent })
  g.rect(x + 7, y + 7, 46, 4).fill(accent)
  g.rect(x + 7, y + 16, w - 18, 3).fill(0xc5d3aa)
  g.rect(x + 7, y + 24, w - 34, 3).fill(0xc5d3aa)
  parent.addChild(g)
  addText(parent, '今日推荐', x + 82, y + 7, 8, 0xfff3c4, true, true)
}

function drawDisplayCabinet(parent, x, y, w, accent) {
  const px = Math.round(x)
  const py = Math.round(y)
  const g = new Graphics({ roundPixels: true })
  g.rect(px + 4, py + 6, w, 52).fill(0x120d0b)

  // A compact wall lamp visually anchors the display without entering the copy area.
  g.rect(px + Math.round(w * 0.5) - 1, py - 18, 3, 11).fill(0x8b6845)
  g.rect(px + Math.round(w * 0.5) - 12, py - 7, 24, 4).fill(0x4c3428)
  g.rect(px + Math.round(w * 0.5) - 8, py - 3, 16, 5).fill(accent)
  g.rect(px + Math.round(w * 0.5) - 4, py + 2, 8, 3).fill(0xffdfa0)

  // Opaque blue-grey glass keeps the hard pixel edge while separating the food silhouettes.
  g.rect(px, py + 5, w, 24).fill(0x243538)
  g.rect(px, py + 5, w, 24).stroke({ width: 2, color: 0x9e7950 })
  g.rect(px + 5, py + 9, w - 10, 3).fill(0x78918b)
  g.rect(px + 5, py + 24, w - 10, 3).fill(0xc8b27e)
  g.rect(px + Math.round(w * 0.34), py + 7, 2, 20).fill(0x6a817c)
  g.rect(px + Math.round(w * 0.67), py + 7, 2, 20).fill(0x6a817c)

  g.ellipse(px + 27, py + 21, 27, 6).fill(0xe8d8b7)
  g.rect(px + 18, py + 15, 7, 6).fill(0xd65343)
  g.rect(px + 26, py + 13, 8, 8).fill(0xe5b54e)
  g.rect(px + 35, py + 16, 6, 5).fill(0x78a675)
  g.ellipse(px + Math.round(w * 0.5), py + 20, 29, 6).fill(0xd7c7a7)
  g.rect(px + Math.round(w * 0.5) - 10, py + 13, 7, 7).fill(0x78a675)
  g.rect(px + Math.round(w * 0.5), py + 15, 9, 5).fill(0xd08058)
  g.ellipse(px + w - 27, py + 21, 27, 6).fill(0xe8d8b7)
  g.rect(px + w - 38, py + 14, 8, 7).fill(0xb8794b)
  g.rect(px + w - 28, py + 12, 7, 9).fill(0xd65343)

  g.rect(px, py + 29, w, 24).fill(0x99633b)
  g.rect(px + 4, py + 33, w - 8, 16).fill(0xc98f50)
  g.rect(px + Math.round(w * 0.5) - 1, py + 33, 2, 16).fill(0x6b432b)
  g.rect(px + Math.round(w * 0.25) - 2, py + 39, 4, 5).fill(0xe0b35d)
  g.rect(px + Math.round(w * 0.75) - 2, py + 39, 4, 5).fill(0xe0b35d)
  g.rect(px + 9, py + 53, 7, 7).fill(0x513625)
  g.rect(px + w - 16, py + 53, 7, 7).fill(0x513625)
  g.rect(px, py + 29, w, 3).fill(accent)
  parent.addChild(g)
}

function drawSpiceJars(parent, x, y, count, accent) {
  for (let index = 0; index < count; index += 1) {
    const bodyColor = index % 2 ? 0xd68f47 : 0xa7b85f
    const context = getSharedGraphicsContext(`spice-${accent}-${bodyColor}`, (shape) => {
      shape.rect(2, 5, 13, 14).fill(0x17100d)
      shape.rect(0, 4, 13, 14).fill(bodyColor)
      shape.rect(2, 0, 9, 5).fill(accent)
      shape.rect(3, 7, 7, 3).fill(0xf5dd9d)
      shape.rect(4, 8, 5, 1).fill(0x9a744a)
    })
    addSharedGraphic(parent, context, x + index * 19, y)
  }
}

function drawRack(parent, x, y, w, h, accent) {
  const g = new Graphics({ roundPixels: true })
  g.rect(x + 4, y + 5, w, h).fill(0x241812)
  g.rect(x, y, w, h).fill(0x714b35)
  g.rect(x, y, w, h).stroke({ width: 2, color: accent })
  g.rect(x + 4, y + 4, 4, h - 8).fill(0x966344)
  g.rect(x + w - 8, y + 4, 4, h - 8).fill(0x4b3025)
  g.rect(x + 5, y + h * 0.5, w - 10, 3).fill(0x4b3025)
  ;[0xd85a4f, 0x7fb36d, 0xe6b64c, 0xc68bc0, 0x6e9fc0].forEach((color, index) => {
    const shelfY = index < 3 ? y + 8 : y + h * 0.5 + 8
    const shelfX = x + 9 + (index % 3) * 23
    g.rect(shelfX, shelfY, 15, 10).fill(color)
    g.rect(shelfX + 3, shelfY - 3, 9, 3).fill(0x33221d)
    g.rect(shelfX + 3, shelfY + 3, 9, 2).fill(0xe7c383)
  })
  parent.addChild(g)
}

function drawStickyNotes(parent, x, y, count, accent) {
  const g = new Graphics()
  const colors = [accent, 0xc4e39e, 0xe7a9a0]
  for (let index = 0; index < count; index += 1) {
    g.rect(x + (index % 2) * 17, y + Math.floor(index / 2) * 17, 13, 12).fill(colors[index % colors.length])
    g.rect(x + 3 + (index % 2) * 17, y + 3 + Math.floor(index / 2) * 17, 7, 2).fill(0x6b4a35)
  }
  parent.addChild(g)
}

function drawDoubleFridge(parent, x, y, w, h) {
  const g = new Graphics({ roundPixels: true })
  g.roundRect(x + 4, y + 5, w, h, 4).fill(0x18201f)
  g.roundRect(x, y, w, h, 4).fill(0xb9c5c2)
  g.roundRect(x, y, w, h, 4).stroke({ width: 2, color: 0x2b3735 })
  g.rect(x + w / 2 - 1, y + 2, 2, h - 4).fill(0x687773)
  g.rect(x + 4, y + h * 0.5 - 1, w - 8, 2).fill(0x687773)
  g.rect(x + w * 0.35, y + 10, 3, 14).fill(0x3e4b47)
  g.rect(x + w * 0.78, y + 10, 3, 14).fill(0x3e4b47)
  g.rect(x + w * 0.35, y + h * 0.62, 3, 14).fill(0x3e4b47)
  g.rect(x + w * 0.78, y + h * 0.62, 3, 14).fill(0x3e4b47)
  g.rect(x + 7, y + 7, 6, 5).fill(0xe3c15f)
  g.rect(x + 15, y + 8, 5, 4).fill(0x7da77e)
  g.rect(x + 8, y + h - 10, w - 16, 4).fill(0x87a69c)
  g.rect(x + 6, y + h, 7, 4).fill(0x3e4b47)
  g.rect(x + w - 13, y + h, 7, 4).fill(0x3e4b47)
  parent.addChild(g)
}

function drawCookingCounter(parent, x, y, w, accent) {
  const g = new Graphics()
  g.rect(x, y, w, 27).fill(0x865a36)
  g.rect(x + 3, y + 3, w - 6, 17).fill(0xd29b59)
  g.rect(x + 12, y + 8, 28, 6).fill(0xebe3c3)
  g.circle(x + w * 0.45, y + 11, 6).fill(0x7da77e)
  g.circle(x + w * 0.64, y + 11, 5).fill(0xd65343)
  g.rect(x + 11, y + 26, 6, 12).fill(0x513625)
  g.rect(x + w - 17, y + 26, 6, 12).fill(0x513625)
  g.rect(x, y, w, 2).fill(accent)
  parent.addChild(g)
}

function drawCuttingBoard(parent, x, y, w, h) {
  const g = new Graphics()
  g.roundRect(x, y, w, h, 3).fill(0xc78f50)
  g.roundRect(x, y, w, h, 3).stroke({ width: 2, color: 0x6d472f })
  g.circle(x + w - 6, y + 7, 2).fill(0x5f4130)
  parent.addChild(g)
}

function drawHangingUtensils(parent, x, y, count, accent) {
  const g = new Graphics()
  g.rect(x, y, count * 16 + 5, 3).fill(accent)
  for (let index = 0; index < count; index += 1) {
    const utensilX = x + 5 + index * 16
    g.rect(utensilX, y + 3, 3, 18 + (index % 2) * 8).fill(0xd6c8a9)
    g.circle(utensilX + 1.5, y + 24 + (index % 2) * 8, 4).fill(0xc0b294)
  }
  parent.addChild(g)
}

function drawHangingPots(parent, x, y, count, accent) {
  const g = new Graphics({ roundPixels: true })
  g.rect(x, y, count * 24 + 4, 3).fill(accent)
  for (let index = 0; index < count; index += 1) {
    const potX = x + 9 + index * 24
    g.rect(potX, y + 3, 2, 9).fill(0xd7c7a7)
    g.rect(potX - 6, y + 9, 13, 3).fill(0x77716a)
    g.ellipse(potX - 7, y + 11, 15, 8).fill(0x34302e)
    g.rect(potX - 4, y + 4, 8, 3).fill(0x4b4742)
    g.rect(potX + 7, y + 13, 7, 3).fill(0x5a5049)
    g.rect(potX - 4, y + 15, 9, 2).fill(0x171412)
  }
  parent.addChild(g)
}

function drawPictureFrame(parent, x, y, w, h, accent) {
  const g = new Graphics()
  g.rect(x, y, w, h).fill(0xeee0bb)
  g.rect(x, y, w, h).stroke({ width: 2, color: accent })
  g.rect(x + 5, y + 5, w - 10, h - 10).fill({ color: accent, alpha: 0.36 })
  g.rect(x + 9, y + 10, 7, 7).fill(0xf4d578)
  g.rect(x + 20, y + 8, 12, 11).fill(0x77a978)
  parent.addChild(g)
}

function drawPantryShelf(parent, x, y, w, h) {
  const g = new Graphics()
  g.rect(x, y, w, h).fill(0x9a673d)
  g.rect(x, y + h / 2, w, 2).fill(0x513423)
  ;[0xd3544b, 0x6ea466, 0xd5a33e, 0xb57b9e].forEach((color, index) => {
    g.rect(x + 8 + index * 17, y + 5, 10, 10).fill(color)
  })
  parent.addChild(g)
}

function drawPrepTable(parent, x, y, w, accent) {
  const g = new Graphics()
  g.rect(x - w / 2, y, w, 24).fill(0x9c673d)
  g.rect(x - w / 2 + 4, y + 3, w - 8, 15).fill(0xd6a361)
  g.rect(x - w / 2, y, w, 24).stroke({ width: 1, color: 0x65402b })
  g.rect(x - w / 2 + 12, y + 22, 7, 16).fill(0x68452f)
  g.rect(x + w / 2 - 19, y + 22, 7, 16).fill(0x68452f)
  g.rect(x - 26, y + 7, 52, 3).fill(accent)
  g.circle(x + 31, y + 8, 4).fill(0x77a978)
  parent.addChild(g)
}

function drawWorktopFood(parent, x, y, w, accent) {
  const px = Math.round(x)
  const py = Math.round(y)
  const left = Math.round(px - w / 2 + 8)
  const panX = Math.round(px + w / 2 - 24)
  const g = new Graphics({ roundPixels: true })

  // Keep the middle of the worktop clear so the character reads as standing in front of it.
  g.rect(left + 2, py - 7, 27, 8).fill(0x3f2b20)
  g.rect(left, py - 10, 27, 8).fill(0xb97c47)
  g.rect(left, py - 10, 27, 8).stroke({ width: 1, color: 0x65402b })
  g.rect(left + 4, py - 13, 6, 5).fill(0x78a675)
  g.rect(left + 11, py - 15, 6, 7).fill(0xe2ad49)
  g.rect(left + 19, py - 13, 6, 5).fill(0xd65b4f)
  g.rect(left + 6, py - 16, 2, 4).fill(0x4f7f54)

  g.ellipse(panX + 2, py - 3, 15, 5).fill(0x171310)
  g.ellipse(panX, py - 6, 15, 5).fill(0x34302e)
  g.ellipse(panX, py - 7, 11, 3).fill(0x72503b)
  g.rect(panX + 13, py - 8, 16, 4).fill(0x3b2b25)
  g.rect(panX + 25, py - 9, 8, 6).fill(0x241b18)
  g.rect(panX - 7, py - 9, 5, 4).fill(0xd65343)
  g.rect(panX, py - 10, 5, 4).fill(0xe5b54e)
  g.rect(panX + 6, py - 9, 4, 3).fill(0x78a675)
  g.rect(left, py - 2, 27, 2).fill(accent)
  parent.addChild(g)
}

function drawRiceBag(parent, x, y, accent) {
  const g = new Graphics()
  g.roundRect(x, y, 36, 34, 4).fill(0xe7d8ae)
  g.roundRect(x, y, 36, 34, 4).stroke({ width: 2, color: accent })
  g.rect(x + 7, y + 8, 22, 3).fill(0xc29153)
  g.rect(x + 9, y + 17, 18, 4).fill(0x9dbb7a)
  g.rect(x + 13, y + 25, 10, 2).fill(0xc29153)
  parent.addChild(g)
}

function drawStool(parent, x, y, accent) {
  const context = getSharedGraphicsContext(`stool-${accent}`, (shape) => {
    shape.rect(-10, 3, 24, 7).fill(0x1d1410)
    shape.rect(-12, 0, 24, 7).fill(accent)
    shape.rect(-9, 2, 18, 2).fill(0xe2bd70)
    shape.rect(-8, 7, 4, 16).fill(0x6b4630)
    shape.rect(4, 7, 4, 16).fill(0x6b4630)
    shape.rect(-5, 15, 10, 3).fill(0x4e3327)
  })
  addSharedGraphic(parent, context, x, y)
}

function drawBookPile(parent, x, y, accent) {
  const g = new Graphics()
  g.rect(x, y + 13, 42, 7).fill(0x6c8f9b)
  g.rect(x + 5, y + 7, 34, 7).fill(0xe2a55d)
  g.rect(x + 1, y, 38, 8).fill(0xb47db1)
  g.rect(x + 7, y + 2, 24, 2).fill(accent)
  parent.addChild(g)
}

function drawRecipeDesk(parent, x, y, w, accent, foreground = false) {
  drawPrepTable(parent, x, y, w, accent)
  const g = new Graphics()
  if (foreground) {
    g.rect(x + 23, y + 2, 29, 12).fill(0xf0e6c8)
    g.rect(x + 26, y + 5, 23, 2).fill(accent)
    g.rect(x + 31, y + 9, 10, 2).fill(0xb9946a)
    g.rect(x - 50, y + 3, 23, 8).fill(0xfff3d2)
    g.rect(x - 46, y + 6, 15, 2).fill(0xb9946a)
  } else {
    g.rect(x - 31, y - 12, 62, 10).fill(0xf0e6c8)
    g.rect(x - 27, y - 10, 54, 2).fill(accent)
    g.rect(x - 11, y - 17, 22, 9).fill(0xfff3d2)
    g.rect(x - 8, y - 15, 16, 2).fill(0xb9946a)
  }
  parent.addChild(g)
  drawWorktopFood(parent, x, y, w, accent)
}

function drawOvenStove(parent, x, y, accent) {
  const g = new Graphics({ roundPixels: true })
  g.rect(x + 4, y + 5, 61, 47).fill(0x211816)
  g.rect(x, y, 61, 47).fill(0x6d5a52)
  g.rect(x, y, 61, 47).stroke({ width: 2, color: accent })
  g.rect(x + 5, y + 4, 51, 4).fill(0xb88a62)
  g.ellipse(x + 16, y + 4, 14, 4).fill(0x292321)
  g.ellipse(x + 43, y + 4, 14, 4).fill(0x292321)
  g.rect(x + 7, y + 8, 47, 19).fill(0x24282a)
  g.rect(x + 12, y + 12, 37, 11).fill(0x9a5a42)
  g.rect(x + 14, y + 14, 33, 3).fill(0xc67a55)
  g.rect(x + 11, y + 27, 39, 4).fill(0x332a27)
  g.circle(x + 16, y + 35, 5).fill(0x25201e)
  g.circle(x + 31, y + 35, 5).fill(0x25201e)
  g.circle(x + 46, y + 35, 5).fill(0x25201e)
  g.rect(x + 14, y + 33, 4, 2).fill(accent)
  g.rect(x + 29, y + 33, 4, 2).fill(accent)
  g.rect(x + 44, y + 33, 4, 2).fill(accent)
  g.rect(x + 5, y + 47, 7, 4).fill(0x3b2e2a)
  g.rect(x + 49, y + 47, 7, 4).fill(0x3b2e2a)
  parent.addChild(g)
}

function drawNutritionPoster(parent, x, y, w, h, accent) {
  const g = new Graphics()
  g.rect(x, y, w, h).fill(0xf1e6be)
  g.rect(x, y, w, h).stroke({ width: 2, color: accent })
  g.circle(x + w * 0.5, y + 22, 11).fill(0x78aa76)
  g.rect(x + 10, y + 38, w - 20, 3).fill(accent)
  g.rect(x + 16, y + 45, w - 32, 3).fill(0xa78768)
  parent.addChild(g)
  addText(parent, '营养', x + w / 2, y + 6, 7, 0x5a4332, true, true)
}

function drawExperimentTable(parent, x, y, w, accent, foreground = false) {
  drawPrepTable(parent, x, y, w, accent)
  const g = new Graphics()
  if (foreground) {
    g.rect(x - 51, y - 12, 29, 9).fill(0xc8e0d5)
    g.rect(x - 47, y - 20, 9, 8).fill(0xe9b84f)
    g.rect(x + 22, y - 12, 29, 9).fill(0xc8e0d5)
    g.rect(x + 27, y - 20, 9, 8).fill(0xd35b56)
    g.rect(x + 39, y - 19, 8, 9).fill(0x71a6c3)
  } else {
    g.rect(x - 34, y - 12, 68, 9).fill(0xc8e0d5)
    g.rect(x - 22, y - 20, 12, 9).fill(0xe9b84f)
    g.rect(x - 2, y - 19, 12, 8).fill(0xd35b56)
    g.rect(x + 17, y - 21, 10, 10).fill(0x71a6c3)
  }
  parent.addChild(g)
  drawWorktopFood(parent, x, y, w, accent)
}

function drawShelfWithBins(parent, x, y, w, h, accent) {
  const g = new Graphics({ roundPixels: true })
  g.rect(x + 4, y + 5, w, h).fill(0x221711)
  g.rect(x, y, w, h).fill(0x80563b)
  g.rect(x, y, w, h).stroke({ width: 2, color: accent })
  g.rect(x + 4, y + 4, 4, h - 8).fill(0xa46d48)
  g.rect(x + w - 8, y + 4, 4, h - 8).fill(0x4e3327)
  g.rect(x + 4, y + h * 0.5, w - 8, 3).fill(0x4e3327)
  ;[0xd66555, 0x78a675, 0xe1ad53, 0x7197b8].forEach((color, index) => {
    const binX = x + 8 + (index % 4) * ((w - 24) / 4)
    g.rect(binX, y + 8, Math.max(12, (w - 32) / 4), 14).fill(color)
    g.rect(binX + 3, y + 10, Math.max(6, (w - 50) / 4), 3).fill(0xe4c482)
    g.rect(binX, y + h * 0.5 + 8, Math.max(12, (w - 32) / 4), 14).fill(index % 2 ? 0xd8bd6c : 0xb8c9a2)
  })
  parent.addChild(g)
}

function drawWallUtensilRack(parent, x, y, accent) {
  const g = new Graphics()
  g.rect(x, y, 104, 4).fill(accent)
  ;[0xe8d9b8, 0xc78e54, 0x90b2ac, 0xe1b44e].forEach((color, index) => {
    g.rect(x + 10 + index * 24, y + 4, 4, 19).fill(color)
    g.circle(x + 12 + index * 24, y + 24, 4).fill(color)
  })
  parent.addChild(g)
}

function drawSinkCabinet(parent, x, y, w, h, accent) {
  const g = new Graphics({ roundPixels: true })
  g.rect(x + 4, y + 5, w, h).fill(0x201511)
  g.rect(x, y, w, h).fill(0x9b6845)
  g.rect(x, y, w, h).stroke({ width: 2, color: accent })
  g.rect(x - 3, y + 3, w + 6, 4).fill(0xd3a168)
  g.rect(x + 8, y + 7, w - 16, 16).fill(0xb7c8c3)
  g.rect(x + 13, y + 10, w - 26, 9).fill(0x657b78)
  g.rect(x + 17, y + 11, w - 34, 3).fill(0xd9e1d5)
  g.rect(x + w * 0.5 - 1, y + 26, 2, h - 28).fill(0x65432f)
  g.rect(x + 5, y + 26, w - 10, 2).fill(0x6c472f)
  g.rect(x + 18, y + 29, 5, 10).fill(0x6c4a35)
  g.rect(x + w - 23, y + 29, 5, 10).fill(0x6c4a35)
  // Use stepped pixel geometry for the faucet instead of an arc path; this
  // keeps the hard-edged style and avoids path joins bleeding into the room.
  g.rect(x + w * 0.44, y + 1, 3, 8).fill(0x719287)
  g.rect(x + w * 0.44, y - 1, 16, 3).fill(0x719287)
  g.rect(x + w * 0.44 + 13, y, 3, 8).fill(0x719287)
  g.rect(x + 8, y + 35, 22, 3).fill(0xd8bd6c)
  g.rect(x + 9, y + 38, 20, 4).fill(0x6d9cc3)
  g.rect(x + 7, y + h, 7, 4).fill(0x513625)
  g.rect(x + w - 14, y + h, 7, 4).fill(0x513625)
  parent.addChild(g)
}

function drawDishStack(parent, x, y, accent) {
  const g = new Graphics()
  g.ellipse(x, y + 15, 28, 7).fill(0xe8dfc5)
  g.ellipse(x + 2, y + 11, 24, 6).fill(0xc5d8cf)
  g.ellipse(x + 4, y + 7, 20, 5).fill(0xe8dfc5)
  g.rect(x + 11, y + 2, 6, 4).fill(accent)
  parent.addChild(g)
}

function drawTrashBin(parent, x, y, accent) {
  const g = new Graphics()
  g.rect(x - 12, y, 24, 23).fill(0x5b6970)
  g.rect(x - 14, y - 4, 28, 4).fill(accent)
  g.rect(x - 6, y + 5, 3, 13).fill(0x839397)
  g.rect(x + 3, y + 5, 3, 13).fill(0x839397)
  parent.addChild(g)
}

function drawIngredientBasket(parent, x, y, accent) {
  const g = new Graphics()
  g.rect(x, y + 8, 38, 19).fill(0xb97842)
  g.rect(x, y + 8, 38, 19).stroke({ width: 2, color: accent })
  g.rect(x + 5, y + 4, 3, 7).fill(0xd4a361)
  g.rect(x + 30, y + 4, 3, 7).fill(0xd4a361)
  g.rect(x + 7, y + 2, 24, 3).fill(0xd4a361)
  g.circle(x + 9, y + 8, 5).fill(0x78a675)
  g.circle(x + 19, y + 6, 5).fill(0xe0af45)
  g.circle(x + 29, y + 8, 5).fill(0xd65343)
  parent.addChild(g)
}

function drawSmallAppliance(parent, x, y, accent) {
  const g = new Graphics()
  g.roundRect(x, y, 42, 28, 3).fill(0xa7b7b3)
  g.roundRect(x, y, 42, 28, 3).stroke({ width: 2, color: accent })
  g.rect(x + 6, y + 6, 23, 13).fill(0x33403e)
  g.rect(x + 10, y + 9, 15, 7).fill(0xd08058)
  g.circle(x + 35, y + 10, 3).fill(accent)
  g.circle(x + 35, y + 19, 3).fill(0x687c78)
  parent.addChild(g)
}

function drawDeskComputer(parent, x, y, w, accent, foreground = false) {
  drawPrepTable(parent, x, y, w, accent)
  const g = new Graphics()
  if (foreground) {
    g.rect(x + 22, y + 2, 29, 13).fill(0x252c2b)
    g.rect(x + 25, y + 5, 23, 8).fill(accent)
    g.rect(x + 35, y + 15, 6, 4).fill(0x6b4a35)
    g.rect(x - 50, y + 3, 25, 3).fill(0xf1e3b9)
  } else {
    g.rect(x - 24, y - 22, 48, 19).fill(0x252c2b)
    g.rect(x - 20, y - 18, 40, 12).fill(accent)
    g.rect(x - 3, y - 3, 6, 5).fill(0x6b4a35)
    g.rect(x - 18, y + 3, 36, 3).fill(0xf1e3b9)
  }
  parent.addChild(g)
  drawWorktopFood(parent, x, y, w, accent)
}

function drawStorageCabinet(parent, x, y, w, h, accent) {
  const g = new Graphics()
  g.rect(x, y, w, h).fill(0x7d553e)
  g.rect(x, y, w, h).stroke({ width: 2, color: accent })
  g.rect(x + 4, y + 5, w - 8, h * 0.38).fill(0xa4754c)
  g.rect(x + 4, y + h * 0.5, w - 8, h * 0.42).fill(0x9a6b47)
  g.rect(x + w * 0.5 - 2, y + 18, 4, 6).fill(accent)
  g.rect(x + w * 0.5 - 2, y + h * 0.72, 4, 6).fill(accent)
  parent.addChild(g)
}

function drawWorkstation(parent, x, y, accent) {
  const desk = new Container()
  desk.position.set(x, y)

  const body = new Graphics()
  body.roundRect(-30, 0, 60, 20, 4).fill(0xb77d45)
  body.roundRect(-27, 3, 54, 13, 3).fill(0xd5a363)
  body.roundRect(-27, 3, 54, 13, 3).stroke({ width: 1, color: accent, alpha: 0.85 })
  body.rect(-22, 18, 5, 9).fill(0x805738)
  body.rect(17, 18, 5, 9).fill(0x805738)
  desk.addChild(body)

  const ingredients = new Graphics({ roundPixels: true })
  ingredients.ellipse(-20, -2, 9, 3).fill(0x4a342a)
  ingredients.rect(-25, -7, 6, 6).fill(0x78a675)
  ingredients.rect(-19, -9, 6, 8).fill(0xe2ad49)
  ingredients.rect(14, -7, 6, 6).fill(0xd65343)
  ingredients.rect(21, -9, 6, 8).fill(0x78a675)
  desk.addChild(ingredients)

  const screen = new Graphics()
  screen.roundRect(-10, 2, 20, 13, 2).fill(0x1e2a2a)
  screen.roundRect(-8, 4, 16, 8, 1).fill({ color: accent, alpha: 0.88 })
  screen.rect(-4, 15, 8, 2).fill(0x77553a)
  desk.addChild(screen)

  const light = new Graphics()
  light.circle(-20, 7, 2.5).fill(accent)
  light.circle(20, 7, 2.5).fill(0xd65343)
  desk.addChild(light)
  parent.addChild(desk)
  return { screen, light }
}

function drawCookingPan(parent, x, y) {
  const pan = new Container()
  pan.position.set(x, y)

  const body = new Graphics()
  body.ellipse(0, 0, 31, 9).fill(0x2a2420)
  body.ellipse(0, -2, 25, 6).fill(0x5e4533)
  body.ellipse(-2, -3, 18, 4).fill(0xd65343)
  body.circle(-8, -3, 3).fill(0xe7b24b)
  body.circle(4, -2, 3).fill(0x78a96f)
  body.rect(26, -3, 22, 5).fill(0x7e5a3d)
  body.rect(43, -4, 12, 7).fill(0x342923)
  pan.addChild(body)
  parent.addChild(pan)

  const steam = new Graphics()
  steam.moveTo(x - 10, y - 10).quadraticCurveTo(x - 15, y - 20, x - 9, y - 28).stroke({ width: 2, color: 0xf0dbb2, alpha: 0.64 })
  steam.moveTo(x + 3, y - 10).quadraticCurveTo(x - 2, y - 19, x + 5, y - 27).stroke({ width: 2, color: 0xf0dbb2, alpha: 0.52 })
  parent.addChild(steam)
  return { pan, steam, baseX: x }
}

function drawFridge(parent, x, y) {
  const g = new Graphics()
  g.roundRect(x, y, 45, 74, 4).fill(0xbcc6c2)
  g.roundRect(x, y, 45, 74, 4).stroke({ width: 2, color: 0x2a302f })
  g.rect(x + 2, y + 34, 41, 2).fill(0x65716f)
  g.rect(x + 35, y + 9, 3, 13).fill(0x384441)
  g.rect(x + 35, y + 43, 3, 13).fill(0x384441)
  parent.addChild(g)
}

function drawCrate(parent, x, y, accent) {
  const g = new Graphics()
  g.rect(x, y, 52, 30).fill(0xa97943)
  g.rect(x, y, 52, 30).stroke({ width: 2, color: accent })
  g.moveTo(x + 8, y + 6).lineTo(x + 44, y + 24).stroke({ width: 1, color: 0x724726 })
  g.moveTo(x + 44, y + 6).lineTo(x + 8, y + 24).stroke({ width: 1, color: 0x724726 })
  parent.addChild(g)
}

function drawBookcase(parent, x, y, w = 44, h = 75, accent = 0xb083c7) {
  const g = new Graphics({ roundPixels: true })
  g.rect(x + 4, y + 5, w, h).fill(0x241713)
  g.rect(x, y, w, h).fill(0x8e5d43)
  g.rect(x, y, w, h).stroke({ width: 2, color: accent })
  g.rect(x + 4, y + 4, 4, h - 8).fill(0xaf7652)
  g.rect(x + w - 8, y + 4, 4, h - 8).fill(0x5d392c)
  g.rect(x + 4, y + h * 0.27, w - 8, 3).fill(0xe0b35d)
  g.rect(x + 4, y + h * 0.6, w - 8, 3).fill(0xe0b35d)
  ;[0xe67b63, 0x70a6c6, 0xd4a14a, 0xb180bc, 0x78a878].forEach((color, index) => {
    const bookX = x + 9 + (index % 4) * Math.max(7, (w - 20) / 4)
    const bookY = y + 7 + Math.floor(index / 4) * Math.max(18, h * 0.32)
    g.rect(bookX, bookY, 6, Math.min(14, h * 0.18)).fill(color)
    g.rect(bookX + 1, bookY + 2, 2, Math.min(9, h * 0.12)).fill(0xf0d793)
  })
  g.rect(x + 7, y + h * 0.65, w - 14, h * 0.28).fill(0x744934)
  g.rect(x + w * 0.5 - 1, y + h * 0.66, 2, h * 0.26).fill(0x4e3026)
  g.rect(x + w * 0.5 - 6, y + h * 0.78, 3, 4).fill(accent)
  g.rect(x + w * 0.5 + 3, y + h * 0.78, 3, 4).fill(accent)
  parent.addChild(g)
}

function drawScale(parent, x, y, accent) {
  const g = new Graphics()
  g.rect(x, y + 18, 46, 8).fill(0x8f9ea5)
  g.roundRect(x + 10, y, 26, 23, 4).fill(0xe9e2cb)
  g.roundRect(x + 10, y, 26, 23, 4).stroke({ width: 2, color: accent })
  g.circle(x + 23, y + 11, 6).fill(0x80b08d)
  parent.addChild(g)
}

function drawPlant(parent, x, y) {
  const g = new Graphics()
  g.rect(x - 8, y + 20, 16, 11).fill(0xd08058)
  g.rect(x - 6, y + 18, 12, 4).fill(0xe6b16d)
  g.rect(x - 2, y + 4, 4, 17).fill(0x659b68)
  g.circle(x - 7, y + 7, 6).fill(0x79ad73)
  g.circle(x + 6, y + 3, 6).fill(0x5f9563)
  parent.addChild(g)
}

function drawTable(parent, x, y, w, accent) {
  const g = new Graphics({ roundPixels: true })
  g.roundRect(x + 4, y + 5, w, 24, 6).fill(0x211713)
  g.roundRect(x, y, w, 24, 6).fill(0xb77d45)
  g.roundRect(x + 3, y + 3, w - 6, 15, 4).fill(0xd5a363)
  g.rect(x + 8, y + 5, w - 16, 3).fill(0xe6b874)
  g.rect(x + 14, y + 22, 7, 12).fill(0x805738)
  g.rect(x + w - 21, y + 22, 7, 12).fill(0x805738)
  g.rect(x + 20, y + 29, w - 40, 3).fill(0x593925)
  g.rect(x + w / 2 - 16, y + 8, 32, 3).fill(accent)
  g.rect(x + w / 2 - 5, y + 12, 10, 4).fill(0xeee1c1)
  parent.addChild(g)
}

function drawSmallDiningSet(parent, x, y, w, accent) {
  drawTable(parent, x + w * 0.5, y + 18, w, accent)
  drawStool(parent, x + 18, y + 40, accent)
  drawStool(parent, x + w - 18, y + 40, accent)
  drawDishStack(parent, x + w * 0.5 - 7, y + 3, accent)
}

function drawRestCat(parent, x, y, accent) {
  const g = new Graphics({ roundPixels: true })
  g.ellipse(x - 13, y - 2, 34, 13).fill(0x2a1b15)
  g.ellipse(x - 16, y - 5, 31, 12).fill(0xd69a63)
  g.rect(x - 12, y - 18, 24, 16).fill(0xe3a66d)
  g.moveTo(x - 12, y - 17).lineTo(x - 7, y - 27).lineTo(x - 2, y - 17).fill(0xe3a66d)
  g.moveTo(x + 12, y - 17).lineTo(x + 7, y - 27).lineTo(x + 2, y - 17).fill(0xe3a66d)
  g.rect(x - 7, y - 12, 3, 3).fill(0x513625)
  g.rect(x + 4, y - 12, 3, 3).fill(0x513625)
  g.rect(x - 3, y - 6, 6, 2).fill(accent)
  g.rect(x + 13, y - 6, 13, 4).fill(0xd69a63)
  g.rect(x + 23, y - 14, 4, 10).fill(0xd69a63)
  g.rect(x + 19, y - 17, 7, 4).fill(0xd69a63)
  parent.addChild(g)
}

function drawRestDog(parent, x, y, accent) {
  const g = new Graphics({ roundPixels: true })
  g.ellipse(x, y + 2, 37, 14).fill(0x291c17)
  g.ellipse(x, y - 2, 34, 13).fill(0xb8784d)
  g.rect(x - 14, y - 17, 27, 15).fill(0xc98959)
  g.rect(x - 16, y - 18, 7, 12).fill(0x6f4534)
  g.rect(x + 9, y - 18, 7, 12).fill(0x6f4534)
  g.rect(x - 7, y - 12, 3, 3).fill(0x38251f)
  g.rect(x + 4, y - 12, 3, 3).fill(0x38251f)
  g.rect(x - 3, y - 6, 7, 3).fill(0x4e3026)
  g.rect(x - 5, y - 1, 12, 3).fill(accent)
  parent.addChild(g)
}

function drawPetRestCorner(parent, x, y, w, accent) {
  const px = Math.round(x)
  const py = Math.round(y)
  const g = new Graphics({ roundPixels: true })
  g.rect(px + 4, py + 6, w, 37).fill(0x17110f)
  g.rect(px, py + 2, w, 37).fill(0x5f5648)
  g.rect(px + 5, py + 7, w - 10, 27).fill(0xc59a68)
  g.rect(px + 8, py + 10, w - 16, 3).fill(0xe4bd82)
  g.rect(px, py + 2, w, 37).stroke({ width: 2, color: accent })
  parent.addChild(g)

  drawRestCat(parent, px + 30, py + 31, accent)
  drawRestDog(parent, px + 79, py + 31, accent)

  const accessories = new Graphics({ roundPixels: true })
  accessories.ellipse(px + 14, py + 45, 18, 6).fill(0x31423e)
  accessories.ellipse(px + 14, py + 43, 14, 4).fill(0xe8d29b)
  accessories.circle(px + w - 9, py + 45, 5).fill(0xd65343)
  accessories.rect(px + w - 11, py + 43, 4, 4).fill(0xf0c75f)
  parent.addChild(accessories)
}

function drawLongTable(parent, x, y, w, accent) {
  const g = new Graphics({ roundPixels: true })
  g.rect(x - w / 2 + 5, y + 6, w, 25).fill(0x211713)
  g.rect(x - w / 2, y, w, 25).fill(0xa66d3f)
  g.rect(x - w / 2 + 4, y + 3, w - 8, 16).fill(0xd5a363)
  g.rect(x - w / 2 + 10, y + 5, w - 20, 3).fill(0xe4b474)
  g.rect(x - w / 2 + 16, y + 23, 7, 23).fill(0x6d4831)
  g.rect(x + w / 2 - 23, y + 23, 7, 23).fill(0x6d4831)
  g.rect(x - w / 2 + 22, y + 37, w - 44, 4).fill(0x4b3025)
  g.rect(x - 42, y + 7, 84, 3).fill(accent)
  g.rect(x - 78, y + 9, 18, 4).fill(0xeedfb8)
  g.rect(x + 60, y + 9, 18, 4).fill(0xeedfb8)
  parent.addChild(g)
  ;[-w * 0.36, -w * 0.12, w * 0.12, w * 0.36].forEach((offset) => drawStool(parent, x + offset, y + 36, accent))
}

function drawTeaCart(parent, x, y, w, accent) {
  const px = Math.round(x)
  const py = Math.round(y)
  const g = new Graphics({ roundPixels: true })

  // Narrow wall shelf, kept well below the room label band.
  g.rect(px + 13, py, w - 26, 5).fill(0x2a1b16)
  g.rect(px + 10, py - 3, w - 20, 5).fill(0xa66d3f)
  g.rect(px + 18, py + 2, 4, 8).fill(0x70462e)
  g.rect(px + w - 22, py + 2, 4, 8).fill(0x70462e)
  g.rect(px + 25, py - 14, 19, 11).fill(0x58736d)
  g.rect(px + 29, py - 17, 11, 4).fill(0xc6b88f)
  g.rect(px + 56, py - 12, 12, 9).fill(0xe7d8b5)
  g.rect(px + 74, py - 10, 12, 7).fill(0xd3a168)

  // Cart body with a separate top, doors, handles, legs and caster wheels.
  g.rect(px + 5, py + 20, w, 45).fill(0x1b1310)
  g.rect(px, py + 15, w, 43).fill(0x97613d)
  g.rect(px - 3, py + 12, w + 6, 7).fill(0xd4a064)
  g.rect(px + 5, py + 22, w - 10, 29).fill(0xb97b4c)
  g.rect(px + Math.round(w * 0.5) - 1, py + 22, 2, 29).fill(0x68432f)
  g.rect(px + Math.round(w * 0.25) - 2, py + 34, 4, 5).fill(accent)
  g.rect(px + Math.round(w * 0.75) - 2, py + 34, 4, 5).fill(accent)
  g.rect(px + 8, py + 58, 6, 8).fill(0x68432f)
  g.rect(px + w - 14, py + 58, 6, 8).fill(0x68432f)
  g.circle(px + 11, py + 67, 4).fill(0x25211f)
  g.circle(px + w - 11, py + 67, 4).fill(0x25211f)

  // Kettle, cups and an ingredient basket form one readable horizontal group.
  g.ellipse(px + 24, py + 10, 30, 8).fill(0x3b4140)
  g.rect(px + 12, py - 2, 24, 12).fill(0x626d69)
  g.rect(px + 18, py - 6, 12, 4).fill(0xc6b88f)
  g.rect(px + 35, py + 1, 9, 4).fill(0x626d69)
  g.rect(px + 50, py + 2, 10, 9).fill(0xe5d3aa)
  g.rect(px + 63, py + 3, 10, 8).fill(0xc6e0d1)
  g.rect(px + 82, py + 1, 37, 12).fill(0x9f693f)
  g.rect(px + 87, py - 3, 27, 5).fill(0xd4a361)
  g.rect(px + 89, py - 2, 7, 8).fill(0x78a675)
  g.rect(px + 99, py - 4, 7, 10).fill(0xe0af45)
  g.rect(px + 108, py - 1, 7, 7).fill(0xd65343)
  g.rect(px, py + 15, w, 3).fill(accent)
  parent.addChild(g)
}

function drawTeaSet(parent, x, y, accent) {
  const g = new Graphics()
  g.ellipse(x - 12, y + 11, 24, 6).fill(0xf1e5c6)
  g.rect(x - 8, y + 4, 16, 8).fill(0xe8d4ad)
  g.rect(x - 5, y + 6, 10, 2).fill(accent)
  g.circle(x + 24, y + 8, 6).fill(0xd8b384)
  g.rect(x + 21, y - 2, 6, 10).fill(0x7ca078)
  g.circle(x + 18, y - 3, 5).fill(0x7ca078)
  g.circle(x + 28, y - 5, 5).fill(0x6f986f)
  parent.addChild(g)
}

function drawBulletin(parent, x, y, accent, title = '') {
  const g = new Graphics({ roundPixels: true })
  g.rect(x + 4, y + 5, 118, 70).fill(0x211713)
  g.rect(x, y, 118, 70).fill(0xeadfbd)
  g.rect(x, y, 118, 70).stroke({ width: 2, color: accent })
  g.rect(x + 4, y + 4, 110, 3).fill(0xf7edcf)
  g.rect(x + 8, y + 10, 48, 6).fill(accent)
  g.rect(x + 8, y + 24, 94, 4).fill(0x9b8063)
  g.rect(x + 8, y + 36, 75, 4).fill(0x9b8063)
  g.rect(x + 8, y + 48, 88, 4).fill(0x9b8063)
  g.circle(x + 96, y + 18, 7).fill(0xd65343)
  g.rect(x + 94, y + 16, 4, 4).fill(0xf1c36a)
  g.rect(x + 83, y + 31, 23, 18).fill(0xf1d688)
  g.rect(x + 87, y + 34, 15, 2).fill(0x9b8063)
  g.rect(x + 87, y + 40, 11, 2).fill(0x9b8063)
  g.circle(x + 94, y + 31, 2).fill(0xd65343)
  parent.addChild(g)
  if (title) addText(parent, title, x + 59, y + 5, 7, 0x5a4332, true, true)
}

function drawChiliCrate(parent, x, y, accent) {
  const g = new Graphics()
  g.rect(x, y, 54, 30).fill(0x9d673d)
  g.rect(x, y, 54, 30).stroke({ width: 2, color: accent })
  ;[0xd65343, 0xe59e43, 0x76a56d].forEach((color, index) => {
    g.circle(x + 15 + index * 12, y + 15, 6).fill(color)
  })
  parent.addChild(g)
}

function drawSteam(parent, x, y) {
  const g = new Graphics()
  g.moveTo(x, y).quadraticCurveTo(x - 5, y - 8, x, y - 16).stroke({ width: 2, color: 0xf0dbb2, alpha: 0.7 })
  g.moveTo(x + 10, y + 2).quadraticCurveTo(x + 5, y - 6, x + 10, y - 13).stroke({ width: 2, color: 0xf0dbb2, alpha: 0.55 })
  parent.addChild(g)
}
</script>

<style scoped>
.kitchen-scene {
  position: relative;
  display: grid;
  width: 100%;
  height: 100%;
  min-width: 0;
  min-height: 0;
  place-items: center;
  overflow: hidden;
  border: 1px solid #8b6e4e;
  background: #e6d2a8;
  box-shadow: 0 16px 30px rgba(53, 35, 23, 0.2);
}

.kitchen-scene :deep(.kitchen-scene-canvas) {
  display: block;
  max-width: 100%;
  max-height: 100%;
  image-rendering: pixelated;
}

.scene-loading {
  position: absolute;
  inset: 0;
  z-index: 1;
  display: grid;
  place-items: center;
  color: #59432f;
  background: #e6d2a8;
  font-weight: 800;
}
</style>

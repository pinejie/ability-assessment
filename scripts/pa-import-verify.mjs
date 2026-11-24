// 验证导入的两套岗位模型在页面上可读：/pa-posts 列表 + /pa-models 逐个查看
import { chromium } from 'playwright'
import fs from 'fs'

const TOK = fs.readFileSync('/tmp/pa-admin-token.txt', 'utf8').trim()
const SHOTS = '/tmp/pa-import-shots'
fs.mkdirSync(SHOTS, { recursive: true })
const BASE = 'http://localhost:3000'

const browser = await chromium.launch()
const errs = []
const step = (s) => console.log(`· ${s}`)

const ctx = await browser.newContext({ viewport: { width: 1680, height: 1050 } })
await ctx.addInitScript(([jwt]) => {
  localStorage.setItem('ability-login-v1', jwt)
  localStorage.setItem('ability-profile-v1', JSON.stringify({
    loginid: '2024188', resourceId: 1, name: 'admin',
    departmentId: 1, subcompanyId: 1, admin: true, superAdmin: true,
  }))
  localStorage.setItem('ability-dimension-id', '3')
}, [TOK])
const page = await ctx.newPage()
page.on('pageerror', (e) => errs.push(`pageerror: ${e.message}`))
page.on('console', (m) => { if (m.type() === 'error') errs.push(`console: ${m.text()}`) })
const apiFails = []
page.on('response', (r) => {
  if (r.url().includes('/api/') && r.status() >= 400) apiFails.push(`${r.status()} ${r.url()}`)
})

// ---------------------------------------------------------------- 岗位管理
await page.goto(`${BASE}/pa-posts`, { waitUntil: 'networkidle' })
await page.waitForTimeout(1000)
const rows = await page.locator('.el-table__body tbody tr').count()
step(`/pa-posts 表格行数 = ${rows}`)
const rowTexts = await page.locator('.el-table__body tbody tr').allInnerTexts()
rowTexts.forEach((t, i) => console.log(`   row${i + 1}: ${t.replace(/\s+/g, ' ').trim()}`))
await page.screenshot({ path: `${SHOTS}/01-posts.png`, fullPage: true })

// ---------------------------------------------------------------- 逐个岗位看模型
for (const [idx, name] of [[0, '技术人员'], [1, '信息化管理人员']]) {
  await page.goto(`${BASE}/pa-models`, { waitUntil: 'networkidle' })
  await page.waitForTimeout(1200)

  const postSelect = page.locator('.post-picker .el-select__wrapper').first()
  await postSelect.click()
  await page.waitForTimeout(600)
  await page.locator('.el-select-dropdown__item', { hasText: name }).first().click()
  await page.waitForTimeout(1500)

  const tabs = await page.locator('.el-tabs__item').allInnerTexts()
  step(`/pa-models「${name}」tabs = ${tabs.join(' / ')}`)
  await page.screenshot({ path: `${SHOTS}/02-${idx}-${name}-model.png`, fullPage: true })

  const bodyText = (await page.locator('.el-table').first().innerText().catch(() => '')).replace(/\s+/g, ' ')
  console.log(`   模型概要: ${bodyText.slice(0, 400)}`)

  await page.locator('.el-tabs__item', { hasText: '分值区间' }).first().click()
  await page.waitForTimeout(900)
  const rangeCells = await page.locator('.band-range').allInnerTexts()
  step(`「${name}」区间文本 = ${JSON.stringify(rangeCells)}`)
  await page.screenshot({ path: `${SHOTS}/03-${idx}-${name}-bands.png`, fullPage: true })

  const levelTab = page.locator('.el-tabs__item', { hasText: '等级' }).first()
  if (await levelTab.count()) {
    await levelTab.click()
    await page.waitForTimeout(800)
    const lvText = (await page.locator('.el-main').innerText().catch(() => '')).replace(/\s+/g, ' ')
    step(`「${name}」等级区含「入门/新手」: ${lvText.includes('入门/新手')}`)
    await page.screenshot({ path: `${SHOTS}/04-${idx}-${name}-levels.png`, fullPage: true })
  }
}

console.log('\nAPI 失败:', apiFails.length ? apiFails : '无')
console.log('前端错误:', errs.length ? errs : '无')
await browser.close()

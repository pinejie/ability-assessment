#!/usr/bin/env node
/**
 * cp 工作空间 MCP server：测评数据导入助手的全部能力边界。
 * 只有 9 个具名工具：6 个只读查询（固定 SQL + 参数绑定 + 返回列白名单）、
 * parse_import_file（评分导入，限定目录 + xlsx/csv 单表）、parse_structure_file（能力结构导入，多 sheet 机械归一）、
 * validate_draft/deliver（转调测评后端，deliver 不写业务表）。
 * DB 凭据运行时从 /home/wgd/ws/.mcp.json 解析（本文件不落任何秘密）。
 */
import fs from 'node:fs'
import path from 'node:path'
import sql from 'mssql'
import * as XLSX from 'xlsx'
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js'
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js'
import { z } from 'zod'

const BACKEND = process.env.CP_BACKEND_URL || 'http://127.0.0.1:8080'
const AGENT_TOKEN = process.env.CP_AGENT_TOKEN || ''
const COMPANY_ID = 1
// 上传目录 = 测评后端会话服务的本地目录（不再依赖 ai-chat-app）
const UPLOAD_DIR = '/home/wgd/ws/cp/uploads'

// ---------- DB ----------
// 账号优先级：CP_DB_USER/CP_DB_PASSWORD 环境变量（应为真·只读账号）> 回落 .mcp.json 连接参数。
// 注意 .mcp.json 回落的是 sa——READONLY 旗子只对 database-server 有效，对本连接无约束，
// 边界靠"8 段固定 SQL + 不引入动态拼接"纪律维持；生产强烈建议配置只读账号（review #3）。
let pool = null
async function db() {
  if (pool) return pool
  const mcp = JSON.parse(fs.readFileSync('/home/wgd/ws/.mcp.json', 'utf8'))
  const args = mcp.mcpServers['mssql-ecology'].args
  const g = (f) => args[args.indexOf(f) + 1]
  pool = await sql.connect({
    server: process.env.CP_DB_SERVER || g('--server'),
    database: process.env.CP_DB_DATABASE || g('--database'),
    user: process.env.CP_DB_USER || g('--user'),
    password: process.env.CP_DB_PASSWORD || g('--password'),
    port: parseInt(process.env.CP_DB_PORT || g('--port'), 10),
    options: { encrypt: false, trustServerCertificate: true },
    pool: { max: 3, min: 0 }, connectionTimeout: 10000, requestTimeout: 15000,
  })
  return pool
}
async function q(text, inputs = {}) {
  const p = await db()
  const req = p.request()
  for (const [k, v] of Object.entries(inputs)) req.input(k, v)
  return (await req.query(text)).recordset
}

// ---------- 结果封装 ----------
const ok = (data) => ({ content: [{ type: 'text', text: JSON.stringify({ ok: true, ...data }) }] })
const fail = (message) => ({ content: [{ type: 'text', text: JSON.stringify({ ok: false, message }) }], isError: true })

// ---------- 工具实现 ----------
async function lookupPerson(a) {
  if (!a.jobnumber && !a.name) return fail('至少提供 jobnumber 或 name')
  let rows
  // 工号列实测为 HrmResource.workcode（ecology 标准字段，非 jobnumber）
  if (a.jobnumber) {
    rows = await q(
      `SELECT TOP 10 id AS resourceId, lastname AS name, workcode AS jobnumber, departmentid AS departmentId
       FROM HrmResource WHERE workcode = @v AND status = 1`, { v: a.jobnumber })
  } else {
    rows = await q(
      `SELECT TOP 10 id AS resourceId, lastname AS name, workcode AS jobnumber, departmentid AS departmentId
       FROM HrmResource WHERE lastname = @v AND status = 1`, { v: a.name })
  }
  return ok({ persons: rows, matched: rows.length === 1 })
}

async function getPeriods(a) {
  const rows = await q(
    `SELECT p.year, s.id AS sheetId, s.dimension_id AS dimensionId, p.status AS periodStatus
     FROM uf_assess_sheet s JOIN uf_assess_period p ON p.id = s.period_id
     WHERE s.company_id = @c AND s.resource_id = @r ORDER BY p.year DESC`,
    { c: COMPANY_ID, r: Number(a.resourceId) })
  return ok({ periods: rows })
}

async function getNonFlowElements(a) {
  const rows = await q(
    `SELECT r.element_id AS elementId, r.element_name AS elementName, r.category_name AS categoryName,
            r.required_level_no AS requiredLevelNo
     FROM uf_assess_sheet_row r
     JOIN uf_assess_sheet s ON s.id = r.sheet_id
     JOIN uf_assess_period p ON p.id = s.period_id
     JOIN uf_ability_element e ON e.id = r.element_id AND e.deleted = 0
     WHERE s.company_id = @c AND p.year = @y AND s.resource_id = @r
       AND s.dimension_id = @d AND ISNULL(e.assess_in_flow, 1) = 0
     ORDER BY r.sort_no, r.id`,
    { c: COMPANY_ID, y: Number(a.year), r: Number(a.resourceId), d: Number(a.dimensionId) })
  return ok({ elements: rows, count: rows.length })
}

async function getExistingRaters(a) {
  const sheet = await q(
    `SELECT s.id AS sheetId, s.leader_resource_id AS sheetLeaderId
     FROM uf_assess_sheet s JOIN uf_assess_period p ON p.id = s.period_id
     WHERE s.company_id = @c AND p.year = @y AND s.resource_id = @r AND s.dimension_id = @d`,
    { c: COMPANY_ID, y: Number(a.year), r: Number(a.resourceId), d: Number(a.dimensionId) })
  if (sheet.length === 0) return ok({ found: false })
  const raters = await q(
    `SELECT DISTINCT rater_type AS raterType, rater_resource_id AS resourceId
     FROM uf_assess_sheet_rating WHERE sheet_id = @s`, { s: sheet[0].sheetId })
  return ok({
    found: true,
    sheetLeaderId: sheet[0].sheetLeaderId,
    leaders: raters.filter(r => r.raterType === 'LEADER').map(r => r.resourceId),
    clients: raters.filter(r => r.raterType === 'CLIENT').map(r => r.resourceId),
  })
}

async function checkPeriodOpen(a) {
  // 维度必填（review P1#7）：同人多维度页面随机取行会把开放/关闭判反
  const rows = await q(
    `SELECT p.year, p.status AS periodStatus, s.dimension_id AS dimensionId
     FROM uf_assess_sheet s JOIN uf_assess_period p ON p.id = s.period_id
     WHERE s.company_id = @c AND p.year = @y AND s.resource_id = @r AND s.dimension_id = @d`,
    { c: COMPANY_ID, y: Number(a.year), r: Number(a.resourceId), d: Number(a.dimensionId) })
  if (rows.length === 0) return ok({ found: false })
  const r = rows[0]
  return ok({ found: true, open: r.periodStatus !== 1, reason: r.periodStatus === 1 ? '年度已关闭，拒绝写入' : null, page: r })
}

async function parseImportFile(a) {
  // 归属校验（review #8）：文件必须位于 uploads/{当前会话}/ 之下，杜绝跨会话解析他人原件
  const sid = String(a.sessionId || '')
  if (!/^[0-9a-zA-Z_-]{1,64}$/.test(sid)) return fail('必须携带当前会话 sessionId')
  const abs = path.resolve(String(a.filePath || ''))
  if (!abs.startsWith(UPLOAD_DIR + path.sep + sid + path.sep)) {
    return fail('只允许解析当前会话上传的文件（路径与 sessionId 归属不符）')
  }
  if (!fs.existsSync(abs)) return fail('文件不存在')
  const ext = path.extname(abs).toLowerCase()
  if (!['.csv', '.xlsx', '.xls'].includes(ext)) return fail('仅支持 .xlsx / .xls / .csv')
  // CSV 必须以 utf8 字符串读：Buffer+type:'string' 抛异常，Buffer+type:'buffer' 中文表头乱码（review P0#4 实测）
  const wb = ext === '.csv'
    ? XLSX.read(fs.readFileSync(abs, 'utf8'), { type: 'string', cellDates: true })
    : XLSX.read(fs.readFileSync(abs), { type: 'buffer', cellDates: true })
  const first = wb.SheetNames[0]
  if (!first) return fail('文件中没有任何工作表')
  // Sheets 是以表名为键的对象，必须经 SheetNames 取第一张表
  const sheetRows = XLSX.utils.sheet_to_json(wb.Sheets[first], { header: 1, defval: null })
  const ALIAS = {
    year: ['年度', '年份', '测评年度', 'year'],
    name: ['姓名', '被测人', '人员', 'name'],
    jobnumber: ['工号', '人员工号', 'jobnumber', '编号'],
    element: ['要素', '能力要素', '要素名称', 'element'],
    level: ['评级', '等级', '评价等级', 'level'],
    score: ['分数', '具体分数', '原始分', '原始分数', 'score'],
    annual: ['年度分', 'annualscore', 'annual'],
  }
  const head = (sheetRows[0] || []).map(c => String(c ?? '').trim().toLowerCase())
  const colIdx = {}
  for (const [key, names] of Object.entries(ALIAS)) {
    colIdx[key] = head.findIndex(h => names.includes(h))
  }
  const cell = (row, key) => { const i = colIdx[key]; return i >= 0 ? row[i] : null }
  const out = []
  const errors = []
  sheetRows.slice(1).forEach((row, i) => {
    if (!row || row.every(c => c === null || String(c).trim() === '')) return
    const rec = {
      lineNo: i + 2,
      year: cell(row, 'year'), name: cell(row, 'name'), jobnumber: cell(row, 'jobnumber'),
      element: cell(row, 'element'), level: cell(row, 'level'),
      score: cell(row, 'score'), annualScore: cell(row, 'annual'),
    }
    if (colIdx.name < 0 && colIdx.jobnumber < 0) errors.push({ lineNo: rec.lineNo, message: '表头无法识别姓名/工号列' })
    out.push(rec)
  })
  // 上报数据行数供后端 deliver 行对账（fire-and-forget）
  fetch(`${BACKEND}/api/v1/agent/parse-stats`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Agent-Token': AGENT_TOKEN },
    body: JSON.stringify({ sessionId: sid, fileRows: out.length }),
  }).catch(() => {})
  return ok({ columns: head, rows: out.slice(0, 5000), rowLimit: out.length > 5000 ? 5000 : null, parseErrors: errors })
}

// ---------- 能力结构解析（方案 14） ----------
// 与评分导入不同：结构工作簿是「一本多表」（行为表现库 / 模型表 / 自评表），
// 且表头位置不固定（大标题行 + 分组行 + 子列行）、存在合并单元格与成对的分值列。
// 这里只做「机械归一」——定位表头行、展开合并单元格、逐行编号；语义判定（哪张表算什么、等级描述 vs 分值）
// 全部交给模型。行号统一写成「表名!行号」，因为跨表行号会撞。
const HEADER_HINTS = ['类别', '要素', '指标', '能力', '等级', '级别', '定义', '姓名', '岗位', '职位']
const SKIP_SHEET = /(总结|说明|目录|索引|封面|填写|模板)/

/**
 * 表头行判定：前 8 行里含最多表头关键词的一行。
 * 命中 ≥2 个关键词直接认；只命中 1 个时要求该行"看起来像表头"（≥3 个非空且都是短标签），
 * 因为自评表表头常是「要素 | 张三 | 李四」这种只有一个关键词的全人名行。
 */
function findHeaderRow(rows) {
  let best = -1
  let bestScore = 0
  for (let i = 0; i < Math.min(rows.length, 8); i++) {
    const cells = (rows[i] || []).map(c => String(c ?? '').trim()).filter(c => c !== '')
    const score = cells.filter(c => HEADER_HINTS.some(h => c.includes(h))).length
    const labelLike = cells.length >= 3 && cells.every(c => c.length <= 20)
    const usable = score >= 2 || (score === 1 && labelLike)
    if (usable && score > bestScore) { bestScore = score; best = i }
  }
  return best
}

/** 合并单元格展开：{s:{r,c},e:{r,c}}（0 基）→ 每个覆盖位置取左上角值 */
function expandMerges(sheet, rows, headerRow) {
  const grid = rows.map(r => (r || []).map(c => (c === undefined ? null : c)))
  for (const m of sheet['!merges'] || []) {
    const v = grid[m.s.r]?.[m.s.c] ?? null
    if (v === null) continue
    for (let r = m.s.r; r <= m.e.r; r++) {
      for (let c = m.s.c; c <= m.e.c; c++) {
        if (!grid[r]) grid[r] = []
        if (grid[r][c] === null || grid[r][c] === undefined) grid[r][c] = v
      }
    }
  }
  // 表头行若有纵向合并，向下顺延（分组表头常见：第 1 列跨两行）
  if (headerRow >= 0 && grid[headerRow]) {
    const width = Math.max(...grid.map(r => (r ? r.length : 0)))
    for (let c = 0; c < width; c++) {
      if (grid[headerRow][c] === null || grid[headerRow][c] === undefined) {
        grid[headerRow][c] = grid[headerRow - 1]?.[c] ?? null
      }
    }
  }
  return grid
}

async function parseStructureFile(a) {
  const sid = String(a.sessionId || '')
  if (!/^[0-9a-zA-Z_-]{1,64}$/.test(sid)) return fail('必须携带当前会话 sessionId')
  const abs = path.resolve(String(a.filePath || ''))
  if (!abs.startsWith(UPLOAD_DIR + path.sep + sid + path.sep)) {
    return fail('只允许解析当前会话上传的文件（路径与 sessionId 归属不符）')
  }
  if (!fs.existsSync(abs)) return fail('文件不存在')
  const ext = path.extname(abs).toLowerCase()
  if (!['.csv', '.xlsx', '.xls'].includes(ext)) return fail('仅支持 .xlsx / .xls / .csv')
  const wb = ext === '.csv'
    ? XLSX.read(fs.readFileSync(abs, 'utf8'), { type: 'string', cellDates: true })
    : XLSX.read(fs.readFileSync(abs), { type: 'buffer', cellDates: true })
  if (!wb.SheetNames || wb.SheetNames.length === 0) return fail('文件中没有任何工作表')

  const only = a.sheet ? wb.SheetNames.filter(n => n === String(a.sheet)) : wb.SheetNames
  if (only.length === 0) return fail(`工作表不存在：${a.sheet}`)
  const maxSheets = 20
  if (only.length > maxSheets) return fail(`工作表过多（${only.length} > ${maxSheets}），请拆分后上传`)

  const sheets = []
  const stats = []
  const errors = []
  for (const name of only) {
    const sheet = wb.Sheets[name]
    const rows = XLSX.utils.sheet_to_json(sheet, { header: 1, defval: null, blankrows: true })
    if (rows.length === 0) { sheets.push({ name, headerRowNo: null, columns: [], rows: [] }); continue }
    if (SKIP_SHEET.test(name)) { sheets.push({ name, headerRowNo: null, columns: [], rows: [], note: '疑似说明/目录表，如与结构无关可整表 skipped' }); continue }
    const hIdx = findHeaderRow(rows)
    if (hIdx < 0) {
      sheets.push({ name, headerRowNo: null, columns: [], rows: [], note: '未识别到表头（无类别/要素/等级/姓名等列名）' })
      errors.push({ sheet: name, message: '表头无法识别，请人工确认或 skipped' })
      continue
    }
    const grid = expandMerges(sheet, rows, hIdx)
    const columns = (grid[hIdx] || []).map((c, i) => String(c ?? '').trim() || `列${i + 1}`)
    const dataRows = []
    for (let r = hIdx + 1; r < grid.length; r++) {
      const row = grid[r] || []
      if (row.every(c => c === null || c === undefined || String(c).trim() === '')) continue
      dataRows.push({ lineNo: r + 1, cells: row.map(c => (c === null || c === undefined ? null : c)) })
    }
    if (dataRows.length === 0) {
      sheets.push({ name, headerRowNo: hIdx + 1, columns, rows: [] })
      continue
    }
    // 单表行数上限：结构表通常几十行，超过说明解析错了
    const cap = 2000
    const kept = dataRows.slice(0, cap)
    if (dataRows.length > cap) errors.push({ sheet: name, message: `行数 ${dataRows.length} 超出上限 ${cap}，仅返回前 ${cap} 行` })
    sheets.push({ name, headerRowNo: hIdx + 1, columns, rows: kept })
    stats.push({ name, rows: kept.length, firstLine: kept[0].lineNo, lastLine: kept[kept.length - 1].lineNo })
  }

  // 行块上报（fire-and-forget）：供 deliver 时后端校验「每一行都交代了去向」
  fetch(`${BACKEND}/api/v1/agent/parse-structure-stats`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Agent-Token': AGENT_TOKEN },
    body: JSON.stringify({
      sessionId: sid,
      sheets: stats.map(s => ({ name: s.name, firstLine: s.firstLine, lastLine: s.lastLine, rows: s.rows })),
    }),
  }).catch(() => {})

  return ok({
    workbook: path.basename(abs),
    sheetNames: wb.SheetNames,
    sheets,
    // 行号引用格式：deliver 时 coveredRows / skipped[].rowNo 一律写「表名!行号」
    rowRefFormat: '表名!行号',
    parseErrors: errors,
  })
}

async function postDraft(payload, validate) {
  const resp = await fetch(`${BACKEND}/api/v1/agent/draft`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Agent-Token': AGENT_TOKEN },
    body: JSON.stringify({ payload: JSON.stringify(payload), validate: String(validate) }),
  })
  const body = await resp.json().catch(() => null)
  if (!body || body.code !== 200) return fail(`测评后端响应异常（HTTP ${resp.status}）：${body?.message || '无消息'}`)
  const d = body.data
  if (validate) {
    return d.ok
      ? ok({ valid: true, lines: d.lines, coverage: d.coverage })
      : ok({ valid: false, lines: d.lines, coverage: d.coverage })
  }
  return d.ok
    ? ok({ draftId: d.draftId, ready: d.ready, expiresAt: d.expiresAt })
    : ok({ draftId: null, ready: false, lines: d.lines, reconciliation: d.reconciliation || undefined })
}

const validateDraft = (a) => postDraft(a.payload, true)
const deliver = (a) => postDraft(a.payload, false)

// ---------- MCP server（官方 SDK stdio，兼容 Claude Code 握手） ----------
const server = new McpServer({ name: 'cp-import', version: '1.0.0' })

const rowSchema = z.object({
  kind: z.enum(['RATING', 'ANNUAL']),
  resourceId: z.number().int(),
  elementId: z.number().int().optional().nullable(),
  levelNo: z.number().int().optional().nullable(),
  originalScore: z.number().optional().nullable(),
  annualScore: z.number().optional().nullable(),
})
// 模型常把数字写成字符串（"5级" 已由提示词要求取数字，但 "5" 这种要能收），统一转成数字再交后端
const intish = z.union([z.number(), z.string().regex(/^-?\d+$/)]).transform(v => Number(v))
const numish = z.union([z.number(), z.string().regex(/^-?\d+(\.\d+)?$/)]).transform(v => Number(v))
const payloadSchema = z.object({
  sessionId: z.string().min(1),
  // 交付稿类型：评分导入（EXTERNAL_SCORE，缺省）| 能力结构导入（ABILITY_STRUCTURE，方案 14）
  kind: z.enum(['EXTERNAL_SCORE', 'ABILITY_STRUCTURE']).optional(),
  // 评分导入必填；结构导入不写年度（后端按开放期间取），故设为可选，由后端按 kind 校验
  year: intish.optional().nullable(),
  dimensionId: intish.optional().nullable(),
  // 行对账（后端 submitDraft 校验）：coveredRows=稿行覆盖的行号（结构稿写「表名!行号」）；skipped=未导入行交代清单
  coveredRows: z.array(z.union([z.number().int(), z.string()])).optional(),
  skipped: z.array(z.object({
    rowNo: z.union([z.number().int(), z.string()]),
    raw: z.string().max(120).optional(),
    reason: z.string(),
  })).optional(),
  unreconciled: z.boolean().optional(),
  // ---- 评分导入 ----
  rows: z.array(rowSchema).optional(),
  // ---- 能力结构导入 ----
  categories: z.array(z.object({
    name: z.string().min(1),
    sort: intish.optional().nullable(),
    description: z.string().optional().nullable(),
  })).optional(),
  elements: z.array(z.object({
    categoryName: z.string().min(1),
    name: z.string().min(1),
    assessInFlow: intish.optional().nullable(),
    allowClientAssess: intish.optional().nullable(),
    description: z.string().optional().nullable(),
    levels: z.array(z.object({
      levelNo: intish,
      desc: z.string().min(1),
      levelName: z.string().optional().nullable(),
    })).min(1),
  })).optional(),
  positionReqs: z.array(z.object({
    departmentName: z.string().min(1),
    jobTitleName: z.string().min(1),
    description: z.string().optional().nullable(),
    overwrite: z.boolean().optional().nullable(),
    items: z.array(z.object({
      categoryName: z.string().min(1),
      elementName: z.string().min(1),
      weight: numish.optional().nullable(),
    })).min(1),
  })).optional(),
  userReqs: z.array(z.object({
    resourceNo: z.string().optional().nullable(),
    resourceName: z.string().optional().nullable(),
    departmentName: z.string().optional().nullable(),
    description: z.string().optional().nullable(),
    items: z.array(z.object({
      elementName: z.string().min(1),
      levelNo: intish,
      score: numish.optional().nullable(),
    })).min(1),
  })).optional(),
})

server.registerTool('lookup_person',
  { description: '按工号/姓名查员工（仅返回人员ID、姓名、工号、部门ID；status=1 在职）', inputSchema: { jobnumber: z.string().optional(), name: z.string().optional() } },
  lookupPerson)
server.registerTool('get_periods',
  { description: '查该员工有测评页面的年度清单（含维度与年度开关状态），用于缺年份补齐', inputSchema: { resourceId: z.number() } },
  getPeriods)
server.registerTool('get_non_flow_elements',
  { description: '查该人该年度（指定维度）页面的非流程要素清单（外部导入的唯一合法要素集合）', inputSchema: { year: z.number(), resourceId: z.number(), dimensionId: z.number() } },
  getNonFlowElements)
server.registerTool('get_existing_raters',
  { description: '查页面现有领导/客户评价人集合（外部评级将展开到这些人）', inputSchema: { year: z.number(), resourceId: z.number(), dimensionId: z.number() } },
  getExistingRaters)
server.registerTool('check_period_open',
  { description: '查年度是否关闭（关闭即拒绝写入；按维度定位页面）', inputSchema: { year: z.number(), resourceId: z.number(), dimensionId: z.number() } },
  checkPeriodOpen)
server.registerTool('parse_import_file',
  { description: '解析用户上传的 Excel/CSV（仅限当前会话上传目录；须带上下文里的 sessionId），返回结构化行', inputSchema: { filePath: z.string(), sessionId: z.string() } },
  parseImportFile)
server.registerTool('parse_structure_file',
  {
    description: '解析能力结构工作簿（多 sheet：行为表现库/模型表/自评表）。返回每张表的表头行、列名与逐行单元格（行号=表名!行号）；'
      + '只做机械归一（定位表头、展开合并单元格），语义判定交给模型。仅限当前会话上传目录。',
    inputSchema: {
      filePath: z.string(),
      sessionId: z.string(),
      sheet: z.string().optional(),   // 只解析指定表（缺省=全部）
    },
  },
  parseStructureFile)
server.registerTool('validate_draft',
  { description: '整稿送测评后端校验（不落库），返回逐行 PASS/BLOCK 与覆盖摘要', inputSchema: { payload: payloadSchema } },
  (a) => validateDraft(a))

// 一键导入流水线的三出口收场（回合由测评后端注入的《导入引擎》指令约束使用）
server.registerTool('submit_outcome',
  {
    description: '导入流水线唯一合法收场：kind=deliver（payload 已 validate 通过）| ask（questions 清单）| reject（reason+evidence）。每轮恰好一次。',
    inputSchema: {
      sessionId: z.string(),
      kind: z.enum(['deliver', 'ask', 'reject']),
      outcome: z.record(z.string(), z.any()),
    },
  },
  async (a) => {
    try {
      const resp = await fetch(`${BACKEND}/api/v1/agent/outcome`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Agent-Token': AGENT_TOKEN },
        body: JSON.stringify({ sessionId: a.sessionId, kind: a.kind, outcome: JSON.stringify(a.outcome ?? {}) }),
      })
      const body = await resp.json().catch(() => null)
      if (!body || body.code !== 200) {
        return fail(`收场被后端拒绝：${body?.message || 'HTTP ' + resp.status}`)
      }
      return ok({ accepted: true, kind: a.kind })
    } catch (e) {
      return fail(`收场提交失败：${e.message}`)
    }
  })
server.registerTool('deliver',
  { description: '交付整理稿：生成 draft + 就绪标识点亮写入按钮；不写业务表，写入由用户点按钮触发', inputSchema: { payload: payloadSchema } },
  (a) => deliver(a))

await server.connect(new StdioServerTransport())

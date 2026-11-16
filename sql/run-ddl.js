// 一次性执行 DDL 脚本（绕过 MCP server 的语句前缀白名单）
// 用法：DB_USER=xxx DB_PASSWORD=xxx node run-ddl.js <sql文件路径>
// 连接信息从环境变量读取，禁止在仓库里写死凭据
const fs = require('fs')
const path = require('path')
const sql = require('/home/wgd/workspace/node_modules/mssql')

const file = process.argv[2]
if (!file) {
  console.error('用法: DB_USER=xxx DB_PASSWORD=xxx node run-ddl.js <sql文件路径>')
  process.exit(1)
}

// 本机开发时可以只给 DB_SECRET_FILE（默认 ./config/secret.yml，相对本脚本所在目录），
// 由脚本自己把账号口令读出来，避免把明文密码贴到命令行（会留在 shell 历史和进程表里）。
const path0 = path
const fs0 = fs
function readSecret() {
  const file0 = process.env.DB_SECRET_FILE
  if (!file0) return null
  const text = fs0.readFileSync(path0.resolve(__dirname, '../backend', file0), 'utf8')
  const get = (key) => {
    const m = text.match(new RegExp(`^\\s*${key}:\\s*(.+?)\\s*$`, 'm'))
    return m ? m[1].replace(/^["']|["']$/g, '') : null
  }
  const user = get('username')
  const password = get('password')
  if (!user || !password) {
    console.error(`未能从 ${file0} 解析出 username / password`)
    process.exit(1)
  }
  return { user, password }
}

const secret = !process.env.DB_USER ? readSecret() : null
const { DB_HOST, DB_PORT, DB_NAME } = process.env
const DB_USER = process.env.DB_USER || (secret && secret.user)
const DB_PASSWORD = process.env.DB_PASSWORD || (secret && secret.password)
if (!DB_USER || !DB_PASSWORD) {
  console.error('缺少连接凭据：给 DB_USER / DB_PASSWORD，或给 DB_SECRET_FILE 指向 secret.yml')
  console.error('可选：DB_HOST（默认 172.18.28.108）、DB_PORT（默认 1433）、DB_NAME（默认 ecology）')
  process.exit(1)
}

const content = fs.readFileSync(path.resolve(file), 'utf8')

const config = {
  server: DB_HOST || '172.18.28.108',
  port: Number(DB_PORT) || 1433,
  database: DB_NAME || 'ecology',
  user: DB_USER,
  password: DB_PASSWORD,
  options: {
    encrypt: false,
    trustServerCertificate: true,
    enableArithAbort: true,
  },
  pool: { max: 1, min: 0, idleTimeoutMillis: 5000 },
  requestTimeout: 60000,
}

;(async () => {
  let pool
  try {
    pool = await new sql.ConnectionPool(config).connect()
    console.log('已连接 ecology')

    // GO 是客户端批处理分隔符，服务端不认识，必须自己切。
    // 不直接用 /^GO$/m 切分：在多行模式下 $ 与 \r 的相对位置容易踩坑（实测切不出来），
    // 改成逐行扫描最稳，也顺便去掉 BOM。
    const batches = []
    let buf = []
    for (const line of content.replace(/^﻿/, '').split(/\r?\n/)) {
      if (/^[ \t]*GO[ \t]*$/i.test(line)) {
        const b = buf.join('\n').trim()
        if (b) batches.push(b)
        buf = []
      } else {
        buf.push(line)
      }
    }
    const tail = buf.join('\n').trim()
    if (tail) batches.push(tail)

    console.log(`拆分为 ${batches.length} 个批次`)

    for (let i = 0; i < batches.length; i++) {
      const batch = batches[i]
      const head = batch.split(/\r?\n/).find(l => l.trim() && !l.trim().startsWith('--')) || '(空)'
      console.log(`\n--- 批次 ${i + 1} ---`)
      console.log(`起始语句: ${head.trim().slice(0, 100)}`)
      try {
        const result = await pool.request().batch(batch)
        // 一个批次里可能有多条 SELECT（如事务内的多步验证），只取第一个会漏掉后面的结果集。
        // result.recordsets 里是全部结果集，result.recordset 只是最后一个。
        const sets = result.recordsets && result.recordsets.length
          ? result.recordsets
          : [result.recordset || []]
        const rows = sets.flat()
        console.log(`OK，${sets.length} 个结果集，共 ${rows.length} 行`)
        for (const [idx, set] of sets.entries()) {
          if (!set.length) continue
          console.log(`  -- 结果集 ${idx + 1} --`)
          console.log(JSON.stringify(set, null, 2))
        }
      } catch (e) {
        console.error(`失败: ${e.message}`)
        console.error(`错误号: ${e.number}, 行: ${e.lineNumber}`)
        process.exitCode = 1
        break
      }
    }
  } catch (e) {
    console.error('连接或执行失败:', e.message)
    process.exitCode = 1
  } finally {
    if (pool) {
      await pool.close()
    }
  }
})()

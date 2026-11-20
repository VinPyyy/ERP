/**
 * 库存/期初冒烟套件（M3c 任务 3，可复跑、断言式）。
 * 方案与矩阵：docs/testing/smoke-test-plan.md
 *
 * 前置：后端 dev 已起（默认 http://localhost:8080，ERP_BASE_URL 覆盖）；MySQL 容器 healthy；
 *       admin/admin123 种子存在。
 * 运行：node scripts/smoke/inventory-smoke.cjs
 * 退出码：0 全过 / 1 有 FAIL
 *
 * 副作用：每次运行创建唯一后缀（runid）的仓库/商品/期初单，期初最终作废归零，
 *         库存净变化为 0，可反复执行互不冲突。
 */
const forge = require('../../frontend/node_modules/node-forge')

const BASE = (process.env.ERP_BASE_URL || 'http://localhost:8080/api')
const RUN = Date.now().toString(36)

let adminToken = ''
const results = []

async function api(method, path, body, token = adminToken) {
  const headers = { 'Content-Type': 'application/json' }
  if (token) headers.Authorization = token
  const res = await fetch(BASE + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
  return res.json()
}

async function issue() {
  return (await api('GET', '/auth/crypto', undefined, '')).data
}

function credOf(ch, password) {
  const pub = forge.pki.publicKeyFromPem(ch.publicKey)
  const enc = pub.encrypt(`${ch.nonce}.${password}`, 'RSA-OAEP', {
    md: forge.md.sha256.create(),
    mgf1: { md: forge.md.sha1.create() },
  })
  return { encryptedPassword: forge.util.encode64(enc), nonce: ch.nonce, ts: Date.now() }
}

function check(name, cond, detail) {
  results.push({ name, pass: !!cond, detail: detail || '' })
}

;(async () => {
  // 01 登录
  const login = await api('POST', '/auth/login', { username: 'admin', ...credOf(await issue(), 'admin123') }, '')
  adminToken = login.data && login.data.token
  check('01 admin 加密登录', login.code === 0 && adminToken, `code=${login.code}`)

  // 02 建 runid 仓库
  const wh = await api('POST', '/basedata/warehouses', { code: `SMKW${RUN}`, name: `冒烟仓库${RUN}`, sortNo: 99 })
  check('02 建仓库', wh.code === 0, JSON.stringify(wh).slice(0, 120))
  const whId = wh.data

  // 03 建 runid 商品（NONE 模式）
  const prod = await api('POST', '/basedata/products', {
    skuCode: `SMKPSM${RUN}`, name: `冒烟商品${RUN}`, unit: '个', mgmtMode: 'NONE', status: 1,
  })
  check('03 建商品', prod.code === 0, JSON.stringify(prod).slice(0, 120))
  const skuId = prod.data

  // 04 期初建单（含明细行）
  const opening = await api('POST', '/inventory/openings', {
    warehouseId: whId,
    items: [{ skuId, qty: 10, costPrice: 5 }],
  })
  check('04 期初建单', opening.code === 0, JSON.stringify(opening).slice(0, 120))
  const docId = opening.data

  // 05 审核
  const audit = await api('POST', `/inventory/openings/${docId}/audit`)
  check('05 期初审核', audit.code === 0, JSON.stringify(audit).slice(0, 160))

  // 06 库存查询命中且金额正确
  const stocks = await api('GET', `/inventory/stocks?keyword=SMKPSM${RUN}`)
  const row = stocks.data && stocks.data.records.find((r) => r.skuId === skuId)
  check('06 库存查询 qty=10 金额=50', row && row.qty === 10 && row.costAmount === 50 && row.availableQty === 10,
    JSON.stringify(row))

  // 07 流水含 OPENING 行
  const flows = await api('GET', `/inventory/flows?bizType=OPENING&skuId=${skuId}`)
  check('07 流水 OPENING', flows.code === 0 && flows.data.records.length === 1
    && flows.data.records[0].balanceQty === 10, JSON.stringify(flows.data && flows.data.records && flows.data.records.length))

  // 08 重复期初审核被拒
  const dup = await api('POST', '/inventory/openings', { warehouseId: whId, items: [{ skuId, qty: 3, costPrice: 7 }] })
  const dupAudit = await api('POST', `/inventory/openings/${dup.data}/audit`)
  check('08 重复期初审核拒绝', dupAudit.code !== 0 && /期初/.test(dupAudit.msg || ''), JSON.stringify(dupAudit).slice(0, 160))

  // 09 replay-check 零差异
  const replay = await api('GET', '/inventory/flows/replay-check')
  check('09 replay-check consistent', replay.code === 0 && replay.data.consistent === true,
    JSON.stringify(replay.data && replay.data.diffs && replay.data.diffs.slice(0, 2)))

  // 10 作废期初（原因必填）
  const voidRes = await api('POST', `/inventory/openings/${docId}/void`, { reason: `冒烟收尾${RUN}` })
  check('10 期初作废', voidRes.code === 0, JSON.stringify(voidRes).slice(0, 160))

  // 11 库存归零 + 冲正流水入链 + replay 仍零差异
  const stocks2 = await api('GET', `/inventory/stocks?keyword=SMKPSM${RUN}`)
  const row2 = stocks2.data && stocks2.data.records.find((r) => r.skuId === skuId)
  const replay2 = await api('GET', '/inventory/flows/replay-check')
  check('11 作废后归零且对账干净', row2 && row2.qty === 0 && replay2.data.consistent === true,
    `qty=${row2 && row2.qty} consistent=${replay2.data && replay2.data.consistent}`)

  const failed = results.filter((r) => !r.pass)
  console.log('\n库存冒烟结果：')
  for (const r of results) console.log(`  ${r.pass ? 'PASS' : 'FAIL'}  ${r.name}${r.detail ? '  [' + r.detail + ']' : ''}`)
  console.log(`\n${results.length - failed.length}/${results.length} 通过`)
  process.exit(failed.length ? 1 : 0)
})().catch((e) => { console.error('套件崩溃:', e); process.exit(1) })

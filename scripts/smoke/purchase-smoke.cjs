/**
 * 采购链路冒烟套件（M4 任务 6，可复跑、断言式）。
 * 方案与矩阵：docs/testing/smoke-test-plan.md
 *
 * 前置：后端 dev 已起（ERP_BASE_URL 可覆盖）；admin/admin123。
 * 运行：node scripts/smoke/purchase-smoke.cjs
 * 退出码：0 全过 / 1 有 FAIL
 *
 * 副作用：runid 隔离的仓库/供应商/商品/单据链，最终入库作废归零，净库存 0。
 */
const forge = require('../../frontend/node_modules/node-forge')

const BASE = (process.env.ERP_BASE_URL || 'http://localhost:8080/api')
const RUN = Date.now().toString(36)
let token = ''
const results = []

async function api(method, path, body, useToken = true) {
  const headers = { 'Content-Type': 'application/json' }
  if (useToken && token) headers.Authorization = token
  const res = await fetch(BASE + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
  return res.json()
}

async function issue() {
  return (await api('GET', '/auth/crypto', undefined, false)).data
}
function credOf(ch, pwd) {
  const pub = forge.pki.publicKeyFromPem(ch.publicKey)
  const enc = pub.encrypt(`${ch.nonce}.${pwd}`, 'RSA-OAEP', {
    md: forge.md.sha256.create(), mgf1: { md: forge.md.sha1.create() },
  })
  return { encryptedPassword: forge.util.encode64(enc), nonce: ch.nonce, ts: Date.now() }
}
function check(name, cond, detail) {
  results.push({ name, pass: !!cond, detail: String(detail ?? '').slice(0, 140) })
}

;(async () => {
  // 01 登录
  const login = await api('POST', '/auth/login', { username: 'admin', ...credOf(await issue(), 'admin123') }, false)
  token = login.data && login.data.token
  check('01 登录', login.code === 0 && token, `code=${login.code}`)

  // 02 主数据
  const wh = await api('POST', '/basedata/warehouses', { code: `PW${RUN}`, name: `采购冒烟仓${RUN}`, sortNo: 98 })
  const supA = await api('POST', '/basedata/suppliers', { code: `PSA${RUN}`, name: `供应商A${RUN}` })
  const supB = await api('POST', '/basedata/suppliers', { code: `PSB${RUN}`, name: `供应商B${RUN}` })
  const prod = await api('POST', '/basedata/products', {
    skuCode: `PP${RUN}`, name: `采购冒烟商品${RUN}`, unit: '个', mgmtMode: 'NONE', status: 1,
  })
  check('02 主数据', [wh, supA, supB, prod].every((r) => r.code === 0), JSON.stringify([wh.code, supA.code, prod.code]))

  // 03 询价：同商品两供应商报价 → 审核 → 选中 → 转 2 张订单
  const inq = await api('POST', '/purchase/inquiries', {
    title: `询价${RUN}`,
    items: [
      { skuId: prod.data, supplierId: supA.data, qty: 10, price: 5, leadTimeDays: 7 },
      { skuId: prod.data, supplierId: supB.data, qty: 10, price: 4.8, leadTimeDays: 10 },
    ],
  })
  await api('POST', `/purchase/inquiries/${inq.data}/audit`)
  const inqFull = (await api('GET', `/purchase/inquiries/${inq.data}`)).data
  for (const item of inqFull.items) {
    await api('PUT', `/purchase/inquiries/${inq.data}/items/${item.id}/select`, { selected: true })
  }
  const orders = await api('POST', `/purchase/inquiries/${inq.data}/convert-to-orders`, { warehouseId: wh.data })
  check('03 询价转 2 订单', inq.code === 0 && orders.code === 0 && orders.data.length === 2, `orders=${orders.data && orders.data.length}`)

  // 04 订单A 审核 → 生成入库 → 审核入库（10@5）→ 库存正确
  const orderA = orders.data[0]
  await api('POST', `/purchase/orders/${orderA}/audit`)
  const inbound = await api('POST', `/purchase/orders/${orderA}/create-inbound`)
  await api('POST', `/purchase/inbounds/${inbound.data}/audit`)
  const stocks = await api('GET', `/inventory/stocks?keyword=PP${RUN}`)
  const row = stocks.data && stocks.data.records.find((r) => r.warehouseId === wh.data)
  check('04 入库审核入库存', inbound.code === 0 && row && row.qty === 10 && row.costAmount === 50, JSON.stringify(row))

  // 05 订单自动完成（done=qty）
  const orderFull = (await api('GET', `/purchase/orders/${orderA}`)).data
  check('05 订单行完成头完成', orderFull.doc.status === 'COMPLETED' && orderFull.items[0].doneQty === 10,
    `doc=${orderFull.doc.status} done=${orderFull.items[0].doneQty}`)

  // 06 溢收拒绝（done=10 再入 100——任何正常 ratio 下必超限 → 40001）+ 失败草稿直接作废（审查 I6 行为）
  // 注：不依赖 ratio 种子值——dev 库该参数可能被手工改过（首轮 06 假通过就是这么来的）
  const inbItems = (await api('GET', `/purchase/inbounds/${inbound.data}`)).data.items
  const over = await api('POST', '/purchase/inbounds', {
    supplierId: supA.data, warehouseId: wh.data, sourceOrderId: orderA,
    items: [{ sourceOrderItemId: inbItems[0].sourceOrderItemId, skuId: prod.data, qty: 100, price: 5 }],
  })
  const overAudit = await api('POST', `/purchase/inbounds/${over.data}/audit`)
  const overVoid = await api('POST', `/purchase/inbounds/${over.data}/void`, { reason: '溢收示例草稿清理' })
  check('06 溢收拒40001+草稿作废', over.code === 0 && overAudit.code === 40001 && overVoid.code === 0,
    `audit=${overAudit.code}(${overAudit.msg}) void=${overVoid.code}`)

  // 07 退货（有原单，价强制原单价）4 件 → 库存 6
  const ret = await api('POST', '/purchase/returns', {
    supplierId: supA.data, warehouseId: wh.data, sourceInboundId: inbound.data,
    items: [{ sourceInboundItemId: inbItems[0].id, skuId: prod.data, qty: 4, price: 99, reason: '质量不符' }],
  })
  await api('POST', `/purchase/returns/${ret.data}/audit`)
  const stocks2 = await api('GET', `/inventory/stocks?keyword=PP${RUN}`)
  const row2 = stocks2.data && stocks2.data.records.find((r) => r.warehouseId === wh.data)
  check('07 退货出库至 6', ret.code === 0 && row2 && row2.qty === 6, `qty=${row2 && row2.qty}`)

  // 08 退货作废 → 回 10
  await api('POST', `/purchase/returns/${ret.data}/void`, { reason: '误退' })
  const stocks3 = await api('GET', `/inventory/stocks?keyword=PP${RUN}`)
  const row3 = stocks3.data && stocks3.data.records.find((r) => r.warehouseId === wh.data)
  check('08 退货作废回库 10', row3 && row3.qty === 10, `qty=${row3 && row3.qty}`)

  // 09 入库作废（退货已作废，放行）→ done 回减、库存归零
  const voidInb = await api('POST', `/purchase/inbounds/${inbound.data}/void`, { reason: '整单错误' })
  const stocks4 = await api('GET', `/inventory/stocks?keyword=PP${RUN}`)
  const row4 = stocks4.data && stocks4.data.records.find((r) => r.warehouseId === wh.data)
  check('09 入库作废归零', voidInb.code === 0 && row4 && row4.qty === 0, `code=${voidInb.code} qty=${row4 && row4.qty}`)

  // 10 对账零差异
  const replay = await api('GET', '/inventory/flows/replay-check')
  check('10 replay consistent', replay.code === 0 && replay.data.consistent === true,
    JSON.stringify(replay.data && replay.data.diffs && replay.data.diffs.slice(0, 2)))

  const failed = results.filter((r) => !r.pass)
  console.log('\n采购冒烟结果：')
  for (const r of results) console.log(`  ${r.pass ? 'PASS' : 'FAIL'}  ${r.name}${r.detail ? '  [' + r.detail + ']' : ''}`)
  console.log(`\n${results.length - failed.length}/${results.length} 通过`)
  process.exit(failed.length ? 1 : 0)
})().catch((e) => { console.error('套件崩溃:', e); process.exit(1) })

/**
 * 销售链路冒烟套件（M5 任务 6，可复跑、断言式）。
 * 方案与矩阵：docs/testing/smoke-test-plan.md
 *
 * 前置：后端 dev 已起（ERP_BASE_URL 可覆盖）；admin/admin123。
 * 运行：node scripts/smoke/sale-smoke.cjs
 * 退出码：0 全过 / 1 有 FAIL
 *
 * 副作用：runid 隔离的仓库/客户/商品与单据链；收尾全链逆序作废，净库存 0。
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
  results.push({ name, pass: !!cond, detail: String(detail ?? '').slice(0, 160) })
}

;(async () => {
  // 01 登录
  const login = await api('POST', '/auth/login', { username: 'admin', ...credOf(await issue(), 'admin123') }, false)
  token = login.data && login.data.token
  check('01 登录', login.code === 0 && token, `code=${login.code}`)

  // 02 主数据
  const wh = await api('POST', '/basedata/warehouses', { code: `SW${RUN}`, name: `销售冒烟仓${RUN}`, sortNo: 97 })
  const cu = await api('POST', '/basedata/customers', { code: `SC${RUN}`, name: `客户${RUN}` })
  const sup = await api('POST', '/basedata/suppliers', { code: `SS${RUN}`, name: `供应商${RUN}` })
  const prod = await api('POST', '/basedata/products', {
    skuCode: `SP${RUN}`, name: `销售冒烟商品${RUN}`, unit: '个', mgmtMode: 'NONE', status: 1,
  })
  check('02 主数据', [wh, cu, sup, prod].every((r) => r.code === 0), JSON.stringify([wh.code, cu.code, prod.code]))

  // 03 无源采购入库 20@5（销售库存来源）
  const pi = await api('POST', '/purchase/inbounds', {
    supplierId: sup.data, warehouseId: wh.data, items: [{ skuId: prod.data, qty: 20, price: 5 }],
  })
  await api('POST', `/purchase/inbounds/${pi.data}/audit`)
  const st0 = await api('GET', `/inventory/stocks?keyword=SP${RUN}`)
  const row0 = st0.data && st0.data.records.find((r) => r.warehouseId === wh.data)
  check('03 采购入库 20@5', pi.code === 0 && row0 && row0.qty === 20, `qty=${row0 && row0.qty}`)

  // 04 报价 → 转订单 → 审核锁定 10
  const quote = await api('POST', '/sale/quotes', {
    customerId: cu.data, validUntil: '2026-12-31',
    items: [{ skuId: prod.data, qty: 10, price: 9 }],
  })
  await api('POST', `/sale/quotes/${quote.data}/audit`)
  const so = await api('POST', `/sale/quotes/${quote.data}/convert-to-order`, { warehouseId: wh.data })
  await api('POST', `/sale/orders/${so.data}/audit`)
  const soFull = (await api('GET', `/sale/orders/${so.data}`)).data
  const st1 = await api('GET', `/inventory/stocks?keyword=SP${RUN}`)
  const row1 = st1.data.records.find((r) => r.warehouseId === wh.data)
  check('04 报价转单审核锁定', quote.code === 0 && so.code === 0 && soFull.doc.status === 'AUDITED'
    && row1.lockedQty === 10, `locked=${row1.lockedQty}`)

  // 05 分批出库 6 → 14/4
  const ob1 = await api('POST', '/sale/outbounds', {
    customerId: cu.data, warehouseId: wh.data, sourceOrderId: so.data,
    items: [{ sourceOrderItemId: soFull.items[0].id, skuId: prod.data, qty: 6, price: 9 }],
  })
  await api('POST', `/sale/outbounds/${ob1.data}/audit`)
  const row2 = (await api('GET', `/inventory/stocks?keyword=SP${RUN}`)).data.records.find((r) => r.warehouseId === wh.data)
  check('05 出库6核销实扣', ob1.code === 0 && row2.qty === 14 && row2.lockedQty === 4, `qty=${row2.qty} locked=${row2.lockedQty}`)

  // 06 出库 4 → 完结（done=10 行30 头COMPLETED 锁清零）
  const ob2 = await api('POST', '/sale/outbounds', {
    customerId: cu.data, warehouseId: wh.data, sourceOrderId: so.data,
    items: [{ sourceOrderItemId: soFull.items[0].id, skuId: prod.data, qty: 4, price: 9 }],
  })
  await api('POST', `/sale/outbounds/${ob2.data}/audit`)
  const soAfter = (await api('GET', `/sale/orders/${so.data}`)).data
  check('06 完结联动', ob2.code === 0 && soAfter.doc.status === 'COMPLETED'
    && soAfter.items[0].doneQty === 10 && soAfter.items[0].lineStatus === 30,
    `doc=${soAfter.doc.status} done=${soAfter.items[0].doneQty}`)

  // 07 超发拒绝（剩余 0 再出 1 → 50001）+ 失败草稿作废清理
  const obBad = await api('POST', '/sale/outbounds', {
    customerId: cu.data, warehouseId: wh.data, sourceOrderId: so.data,
    items: [{ sourceOrderItemId: soAfter.items[0].id, skuId: prod.data, qty: 1, price: 9 }],
  })
  const badAudit = await api('POST', `/sale/outbounds/${obBad.data}/audit`)
  const badVoid = await api('POST', `/sale/outbounds/${obBad.data}/void`, { reason: '超发示例草稿清理' })
  check('07 超发拒50001+草稿作废', badAudit.code === 50001 && badVoid.code === 0,
    `audit=${badAudit.code}(${badAudit.msg})`)

  // 08 有源退货 2（价强制出库价 9）→ 库存 12
  const ob2Items = (await api('GET', `/sale/outbounds/${ob2.data}`)).data.items
  const ret = await api('POST', '/sale/returns', {
    customerId: cu.data, warehouseId: wh.data, sourceOutboundId: ob2.data,
    items: [{ sourceOutboundItemId: ob2Items[0].id, skuId: prod.data, qty: 2, price: 99, reason: '质量不符' }],
  })
  await api('POST', `/sale/returns/${ret.data}/audit`)
  const row3 = (await api('GET', `/inventory/stocks?keyword=SP${RUN}`)).data.records.find((r) => r.warehouseId === wh.data)
  check('08 退货回库至 12', ret.code === 0 && row3.qty === 12, `qty=${row3.qty}`)

  // 09 全链逆序作废：退货→出库→订单→采购入库 → 归零
  await api('POST', `/sale/returns/${ret.data}/void`, { reason: '冒烟收尾' })
  await api('POST', `/sale/outbounds/${ob2.data}/void`, { reason: '冒烟收尾' })
  await api('POST', `/sale/outbounds/${ob1.data}/void`, { reason: '冒烟收尾' })
  await api('POST', `/sale/orders/${so.data}/void`, { reason: '冒烟收尾' })
  const voidPi = await api('POST', `/purchase/inbounds/${pi.data}/void`, { reason: '冒烟收尾' })
  const row4 = (await api('GET', `/inventory/stocks?keyword=SP${RUN}`)).data.records.find((r) => r.warehouseId === wh.data)
  check('09 逆序作废归零', voidPi.code === 0 && row4.qty === 0 && row4.lockedQty === 0,
    `qty=${row4.qty} locked=${row4.lockedQty}`)

  // 10 对账零差异
  const replay = await api('GET', '/inventory/flows/replay-check')
  check('10 replay consistent', replay.code === 0 && replay.data.consistent === true,
    JSON.stringify(replay.data && replay.data.diffs && replay.data.diffs.slice(0, 2)))

  const failed = results.filter((r) => !r.pass)
  console.log('\n销售冒烟结果：')
  for (const r of results) console.log(`  ${r.pass ? 'PASS' : 'FAIL'}  ${r.name}${r.detail ? '  [' + r.detail + ']' : ''}`)
  console.log(`\n${results.length - failed.length}/${results.length} 通过`)
  process.exit(failed.length ? 1 : 0)
})().catch((e) => { console.error('套件崩溃:', e); process.exit(1) })

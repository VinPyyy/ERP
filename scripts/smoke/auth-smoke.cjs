/**
 * 认证与密码传输冒烟套件（可复跑、断言式）。
 * 方案与用例矩阵：docs/testing/smoke-test-plan.md
 *
 * 运行前置：
 *   - 后端 dev profile 已启动（默认 http://localhost:8080，可用 ERP_BASE_URL 覆盖）
 *   - MySQL 容器 healthy，种子账号 admin/admin123 存在
 *   - frontend 依赖已安装（复用 node-forge，模拟真实前端加密路径）
 * 运行：node scripts/smoke/auth-smoke.cjs
 * 退出码：0 全过；1 存在 FAIL
 *
 * 副作用：在开发库创建若干 smoke_* 前缀用户并逻辑删除（用户名带时间戳后缀，不与他人冲突）；
 * 其中一个用户会被限流锁定 15 分钟（用例 12），均为一次性测试数据。
 */
const forge = require('../../frontend/node_modules/node-forge')

const BASE = process.env.ERP_BASE_URL || 'http://localhost:8080/api'
const RUN_ID = Date.now().toString(36)

async function issue() {
  const json = await (await fetch(`${BASE}/auth/crypto`)).json()
  if (json.code !== 0) throw new Error('/auth/crypto failed: ' + JSON.stringify(json))
  return json.data
}

/** 与前端 passwordCrypto.ts 完全一致的加密参数 */
function credOf(ch, password) {
  const pub = forge.pki.publicKeyFromPem(ch.publicKey)
  const enc = pub.encrypt(`${ch.nonce}.${password}`, 'RSA-OAEP', {
    md: forge.md.sha256.create(),
    mgf1: { md: forge.md.sha1.create() },
  })
  return { encryptedPassword: forge.util.encode64(enc), nonce: ch.nonce, ts: Date.now() }
}

async function call(method, path, body, token) {
  const headers = { 'Content-Type': 'application/json' }
  if (token) headers.Authorization = token
  const res = await fetch(BASE + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) })
  return res.json()
}

async function loginEnc(username, password) {
  return call('POST', '/auth/login', { username, ...(await issue().then((ch) => credOf(ch, password))) })
}

const results = []
async function test(name, fn) {
  try {
    const detail = await fn()
    results.push({ name, pass: true, detail: detail || '' })
  } catch (e) {
    results.push({ name, pass: false, detail: e.message })
  }
}

function expectCode(json, code, msgPart) {
  if (json.code !== code) throw new Error(`期望 code=${code}，实际 ${json.code}（${json.msg}）`)
  if (msgPart && !(json.msg || '').includes(msgPart)) {
    throw new Error(`期望 msg 含"${msgPart}"，实际"${json.msg}"`)
  }
  return `${json.code} ${json.msg || ''}`.trim()
}

;(async () => {
  let adminToken = ''
  const uname = `smoke_${RUN_ID}`
  const reuseName = `smoke_reuse_${RUN_ID}`
  const lockName = `smoke_lock_${RUN_ID}`
  let resetCred = null

  await test('01 crypto 端点返回挑战', async () => {
    const ch = await issue()
    if (!ch.publicKey.includes('BEGIN PUBLIC KEY') || !ch.nonce || ch.expiresIn !== 120) {
      throw new Error('挑战字段异常: ' + JSON.stringify(ch))
    }
    return 'publicKey/nonce/expiresIn=120'
  })

  await test('02 加密登录成功签发 token', async () => {
    const r = await loginEnc('admin', 'admin123')
    expectCode(r, 0)
    adminToken = r.data.token
    if (!adminToken) throw new Error('token 为空')
    return 'token 已签发'
  })

  await test('03 登录重放同一挑战 → 10006', async () => {
    const ch = await issue()
    const body = { username: 'admin', ...credOf(ch, 'admin123') }
    const first = await call('POST', '/auth/login', body)
    expectCode(first, 0)
    return expectCode(await call('POST', '/auth/login', body), 10006)
  })

  await test('04 错误密码 → 10004', async () => {
    return expectCode(await loginEnc('admin', 'definitely-wrong'), 10004, '用户名或密码错误')
  })

  await test('05 旧版明文登录被拒 → 10001', async () => {
    return expectCode(await call('POST', '/auth/login', { username: 'admin', password: 'admin123' }), 10001)
  })

  let smokeUserId = 0
  await test('06 加密建用户 → 0', async () => {
    const r = await call('POST', '/system/users', {
      username: uname, realName: '冒烟套件', status: 1, roleIds: [], warehouseIds: [0],
      ...credOf(await issue(), 'pwd12345'),
    }, adminToken)
    expectCode(r, 0)
    smokeUserId = r.data
    return 'id=' + smokeUserId
  })

  // 重置流程全部打在套件自建用户上，绝不碰 admin——套件中途崩溃也不会污染种子账号
  await test('07 加密重置密码 → 0', async () => {
    resetCred = credOf(await issue(), 'newpass99')
    return expectCode(await call('PUT', `/system/users/${smokeUserId}/password`, resetCred, adminToken), 0)
  })

  await test('08 重置重放同一挑战 → 10006', async () => {
    return expectCode(await call('PUT', `/system/users/${smokeUserId}/password`, resetCred, adminToken), 10006)
  })

  await test('09 重置短密码 → 10001 至少 6 位', async () => {
    return expectCode(
      await call('PUT', `/system/users/${smokeUserId}/password`, credOf(await issue(), '123'), adminToken),
      10001, '至少 6 位')
  })

  await test('10 自建用户以新密码登录 → 0', async () => {
    return expectCode(await loginEnc(uname, 'newpass99'), 0)
  })

  await test('11 逻辑删除后用户名复用 → 10001 曾被使用', async () => {
    const ch = await issue()
    const created = await call('POST', '/system/users', {
      username: reuseName, realName: '复用测试', status: 1, roleIds: [], warehouseIds: [0],
      ...credOf(ch, 'pwd12345'),
    }, adminToken)
    expectCode(created, 0)
    expectCode(await call('DELETE', `/system/users/${created.data}`, undefined, adminToken), 0)
    const again = await call('POST', '/system/users', {
      username: reuseName, realName: '复用测试', status: 1, roleIds: [], warehouseIds: [0],
      ...credOf(await issue(), 'pwd12345'),
    }, adminToken)
    return expectCode(again, 10001, '曾被使用')
  })

  await test('12 限流：5 次失败后正确密码也被拒 → 10007', async () => {
    const created = await call('POST', '/system/users', {
      username: lockName, realName: '限流测试', status: 1, roleIds: [], warehouseIds: [0],
      ...credOf(await issue(), 'pwd12345'),
    }, adminToken)
    expectCode(created, 0)
    for (let i = 0; i < 5; i++) {
      expectCode(await loginEnc(lockName, 'wrong' + i), 10004)
    }
    return expectCode(await loginEnc(lockName, 'pwd12345'), 10007, '分钟后重试')
  })

  const roName = `smoke_ro_${RUN_ID}`
  await test('13 受限用户：角色列表 403 但 options 引用数据可用', async () => {
    const role = await call('POST', '/system/roles',
      { roleCode: `SMOKE_RO_${RUN_ID}`, roleName: `冒烟仅用户模块${RUN_ID}` }, adminToken)
    expectCode(role, 0)
    const created = await call('POST', '/system/users', {
      username: roName, realName: '受限冒烟', status: 1, roleIds: [role.data], warehouseIds: [0],
      ...credOf(await issue(), 'pwd12345'),
    }, adminToken)
    expectCode(created, 0)
    const logged = await loginEnc(roName, 'pwd12345')
    expectCode(logged, 0)
    const t = logged.data.token
    expectCode(await call('GET', '/system/roles', undefined, t), 10403)
    return expectCode(await call('GET', '/system/roles/options', undefined, t), 0)
  })

  const failed = results.filter((r) => !r.pass)
  console.log('\n冒烟结果：')
  for (const r of results) {
    console.log(`  ${r.pass ? 'PASS' : 'FAIL'}  ${r.name}${r.detail ? '  [' + r.detail + ']' : ''}`)
  }
  console.log(`\n${results.length - failed.length}/${results.length} 通过`)
  process.exit(failed.length ? 1 : 0)
})().catch((e) => { console.error('套件崩溃:', e); process.exit(1) })

import sys, os, json, subprocess
from playwright.sync_api import sync_playwright

# 1) via API: create role with ONLY system:user:list + a user holding it
setup = '''
const forge = require(process.argv[2]);
(async () => {
  const BASE = 'http://localhost:8080/api';
  async function api(m, p, b, t) {
    const h = {'Content-Type':'application/json'}; if (t) h.Authorization = t;
    return (await fetch(BASE+p, {method:m, headers:h, body:b===undefined?undefined:JSON.stringify(b)})).json();
  }
  const ch = (await api('GET','/auth/crypto',undefined,false)).data;
  const pub = forge.pki.publicKeyFromPem(ch.publicKey);
  const cred = async (pwd) => { const c = (await api('GET','/auth/crypto',undefined,false)).data;
    const enc = pub.encrypt(c.nonce+'.'+pwd,'RSA-OAEP',{md:forge.md.sha256.create(),mgf1:{md:forge.md.sha1.create()}});
    return {encryptedPassword:forge.util.encode64(enc), nonce:c.nonce, ts:Date.now()}; };
  const login = await api('POST','/auth/login',{username:'admin',...(await cred('admin123'))},false);
  const t = login.data.token;
  const run = Date.now().toString(36);
  const role = await api('POST','/system/roles',{roleCode:'R_UI_'+run, roleName:'仅用户列表'+run, remark:''}, t);
  const tree = await api('GET','/system/permissions/tree',undefined,t);
  let pid = null;
  (function walk(ns){ for (const n of ns||[]) { if (n.permCode==='system:user:list') pid = n.id; walk(n.children); } })(tree.data);
  if (!pid) throw new Error('system:user:list perm id not found');
  const grant = await api('PUT','/system/roles/'+role.data+'/permissions',[pid],t);
  const user = await api('POST','/system/users',{username:'ui'+run, realName:'受限验证', status:1, roleIds:[role.data], warehouseIds:[0], ...(await cred('pwd123'))}, t);
  console.log(JSON.stringify({ok: role.code===0 && grant.code===0 && user.code===0, username:'ui'+run}));
})().catch(e => { console.error(e); process.exit(1); });
'''
open(os.path.join(os.environ.get('TEMP', '/tmp'), 'setup_user.js'), 'w').write(setup)
out = subprocess.run(['node', os.path.join(os.environ.get('TEMP', '/tmp'), 'setup_user.js'),
                      os.path.join(os.environ.get('ERP_ROOT', r'D:\Program Files\JetBrains\IdeaProjects\ERP'),
                                   'frontend', 'node_modules', 'node-forge')],
                     capture_output=True, text=True)
info = json.loads(out.stdout.strip().splitlines()[-1])
assert info['ok'], 'setup failed: ' + out.stdout + out.stderr
username = info['username']

errors, steps = [], []
def ok(name, cond, extra=''):
    steps.append((name, bool(cond), extra))

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True)
    page = browser.new_page(viewport={'width': 1440, 'height': 900})
    page.on('pageerror', lambda e: errors.append(str(e)))

    page.goto('http://localhost:5173/erp/', timeout=30000)
    page.wait_for_load_state('networkidle')
    page.fill("input[placeholder*='用户名']", username)
    page.fill("input[type='password']", 'pwd123')
    page.click("button:has-text('登')")
    try:
        page.wait_for_selector("text=用户管理", timeout=20000)
    except Exception:
        pass
    ok('01 受限用户登录', 'login' not in page.url)

    page.goto('http://localhost:5173/erp/system/user', timeout=30000)
    page.wait_for_load_state('networkidle')
    page.wait_for_timeout(1500)
    err_toasts = page.locator('.el-message--error').count()
    ok('02 进入用户页无 403 报错 toast', err_toasts == 0, f'errors={err_toasts}')
    ok('03 用户列表渲染', page.locator('.erp-table').count() >= 1)
    # 无 user:create 权限 → 新增按钮不可见（v-perm 生效）
    ok('04 新增按钮按权限隐藏', page.locator("button:has-text('新增用户')").count() == 0)
    page.screenshot(path=os.path.join(os.environ.get('TEMP', '/tmp'), 'restricted-user-page.png'))

    browser.close()

fails = [s for s in steps if not s[1]]
print('受限用户浏览器验证：')
for n, p_, x in steps:
    print(f"  {'PASS' if p_ else 'FAIL'}  {n}" + (f'  [{x}]' if x else ''))
print(f'{len(steps)-len(fails)}/{len(steps)} 通过；JS错误 {len(errors)}')
sys.exit(1 if fails or errors else 0)

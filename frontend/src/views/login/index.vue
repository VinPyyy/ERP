<template>
  <div class="login-page">
    <!-- 左侧品牌区（≥992px 显示） -->
    <aside class="brand">
      <div class="brand-inner">
        <div class="brand-logo">
          <svg width="36" height="36" viewBox="0 0 40 40" fill="none" aria-hidden="true">
            <rect x="2.5" y="2.5" width="35" height="35" rx="10" stroke="#fff" stroke-width="2.4" />
            <path d="M13 27v-9.5l7-4.5 7 4.5V27" stroke="#7CC3EA" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" />
            <path d="M13 22h14" stroke="#7CC3EA" stroke-width="2.4" stroke-linecap="round" />
          </svg>
          <span>ERP 进销存</span>
        </div>

        <h1 class="brand-slogan">采购 · 销售 · 库存 · 财务<br />一个系统管好进销存</h1>
        <p class="brand-desc">单据流转全链路留痕，库存与往来数据实时可见。</p>

        <ul class="trust-list">
          <li>
            <span class="trust-icon">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M12 3l7 3v5c0 4.5-2.8 8.3-7 9.5-4.2-1.2-7-5-7-9.5V6l7-3z" stroke="#7CC3EA" stroke-width="1.8" stroke-linejoin="round" />
                <path d="M9 12l2 2 4-4" stroke="#7CC3EA" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </span>
            <div class="trust-text">
              <strong>分级权限管控</strong>
              <span>操作全程留痕，可审计</span>
            </div>
          </li>
          <li>
            <span class="trust-icon">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <rect x="5" y="10.5" width="14" height="9.5" rx="2" stroke="#7CC3EA" stroke-width="1.8" />
                <path d="M8.5 10.5V8a3.5 3.5 0 017 0v2.5" stroke="#7CC3EA" stroke-width="1.8" stroke-linecap="round" />
              </svg>
            </span>
            <div class="trust-text">
              <strong>会话安全</strong>
              <span>密码加密传输，超时自动失效</span>
            </div>
          </li>
          <li>
            <span class="trust-icon">
              <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true">
                <path d="M4 20h16" stroke="#7CC3EA" stroke-width="1.8" stroke-linecap="round" />
                <path d="M7 20v-6M12 20V8m5 12v-9" stroke="#7CC3EA" stroke-width="1.8" stroke-linecap="round" />
              </svg>
            </span>
            <div class="trust-text">
              <strong>实时经营看板</strong>
              <span>库存、往来、毛利一屏掌握</span>
            </div>
          </li>
        </ul>

        <p class="brand-foot">© {{ year }} ERP 进销存系统</p>
      </div>
    </aside>

    <!-- 右侧表单区 -->
    <main class="panel">
      <div class="panel-inner">
        <div class="panel-brand">
          <svg width="28" height="28" viewBox="0 0 40 40" fill="none" aria-hidden="true">
            <rect x="2.5" y="2.5" width="35" height="35" rx="10" stroke="#0F172A" stroke-width="2.4" />
            <path d="M13 27v-9.5l7-4.5 7 4.5V27" stroke="#0369A1" stroke-width="2.4" stroke-linecap="round" stroke-linejoin="round" />
            <path d="M13 22h14" stroke="#0369A1" stroke-width="2.4" stroke-linecap="round" />
          </svg>
          <span>ERP 进销存</span>
        </div>

        <h2 class="form-title">欢迎登录</h2>
        <p class="form-sub">请使用企业分配的账号</p>

        <el-form :model="form" label-position="top" @submit.prevent @keyup.enter="submit">
          <el-form-item label="用户名" required>
            <el-input
              v-model="form.username"
              size="large"
              placeholder="请输入用户名"
              autocomplete="username"
              :prefix-icon="User"
            />
          </el-form-item>

          <el-form-item label="密码" required>
            <el-input
              v-model="form.password"
              type="password"
              size="large"
              placeholder="请输入密码"
              autocomplete="current-password"
              show-password
              :prefix-icon="Lock"
              @keyup="checkCaps"
              @focus="checkCaps"
            />
            <div v-if="capsOn" class="field-hint" aria-live="polite">
              <el-icon><WarningFilled /></el-icon>
              Caps Lock 已开启，请注意大小写
            </div>
          </el-form-item>

          <div class="form-row">
            <el-checkbox v-model="remember">记住用户名</el-checkbox>
          </div>

          <el-button type="primary" size="large" class="submit" :loading="loading" @click="submit">
            登录
          </el-button>

          <div v-if="loginError" class="form-error" role="alert">
            <el-icon><WarningFilled /></el-icon>
            {{ loginError }}
          </div>
        </el-form>

        <p class="form-help">忘记密码？请联系系统管理员重置</p>
      </div>
    </main>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Lock, User, WarningFilled } from '@element-plus/icons-vue'
import http from '@/api/http'
import { encryptLoginPassword, LOGIN_CHALLENGE_INVALID } from '@/api/loginCrypto'
import { useAuthStore } from '@/stores/auth'

const REMEMBER_KEY = 'erp.login.…name'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const loading = ref(false)
const capsOn = ref(false)
const remember = ref(false)
const loginError = ref('')
const year = new Date().getFullYear()
const form = reactive({ username: '', password: '' })

onMounted(() => {
  try {
    const saved = localStorage.getItem(REMEMBER_KEY)
    if (saved) {
      form.username = saved
      remember.value = true
    }
  } catch {
    /* 存储不可用时静默降级 */
  }
})

function saveRemember() {
  try {
    if (remember.value && form.username) {
      localStorage.setItem(REMEMBER_KEY, form.username)
    } else {
      localStorage.removeItem(REMEMBER_KEY)
    }
  } catch {
    /* ignore */
  }
}

function checkCaps(e: KeyboardEvent | FocusEvent) {
  capsOn.value = (e as KeyboardEvent).getModifierState?.('CapsLock') ?? false
}

async function postLogin() {
  const cred = await encryptLoginPassword(form.password)
  try {
    return (await http.post('/auth/login', { username: form.username, ...cred })) as unknown as { token: string }
  } catch (e) {
    // 挑战过期（页面停留超 TTL）：静默重取挑战重试一次
    if ((e as Error & { code?: number }).code === LOGIN_CHALLENGE_INVALID) {
      const fresh = await encryptLoginPassword(form.password)
      return (await http.post('/auth/login', { username: form.username, ...fresh })) as unknown as {
        token: string
      }
    }
    throw e
  }
}

async function submit() {
  loginError.value = ''
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    // 密码经 /auth/crypto 一次性挑战 RSA-OAEP 加密后传输
    const data = await postLogin()
    saveRemember()
    auth.setToken(data.token)
    await auth.loadMe()
    router.push((route.query.redirect as string) || '/')
  } catch (err) {
    // toast 由拦截器统一处理，这里同步在表单内落一条错误
    loginError.value = err instanceof Error && err.message ? err.message : '登录失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  /* 设计令牌（enterprise navy + blue CTA） */
  --navy-900: #0f172a;
  --navy-700: #334155;
  --accent: #0369a1;
  --bg: #f8fafc;
  --border: #e2e8f0;
  --fg: #020617;
  --muted: #475569;
  --el-color-primary: var(--accent);
  --el-color-primary-light-3: #4e96bd;
  --el-color-primary-light-5: #81b0d0;
  --el-color-primary-light-7: #b3cbdf;
  --el-color-primary-light-8: #ccdbe7;
  --el-color-primary-light-9: #e6edf4;
  --el-color-primary-dark-2: #025581;

  min-height: 100vh;
  min-height: 100dvh;
  display: flex;
  background: var(--bg);
  color: var(--fg);
  font-family: 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', system-ui, -apple-system, sans-serif;
}

/* ---------- 左侧品牌区 ---------- */
.brand {
  flex: 0 0 55%;
  position: relative;
  display: flex;
  align-items: center;
  padding: 48px 56px;
  color: #fff;
  background:
    radial-gradient(900px 480px at 88% -12%, rgba(56, 150, 220, 0.38), transparent 62%),
    linear-gradient(rgba(255, 255, 255, 0.05) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.05) 1px, transparent 1px),
    linear-gradient(160deg, var(--navy-900) 0%, #1e293b 55%, var(--navy-700) 100%);
  background-size: auto, 44px 44px, 44px 44px, auto;
}
.brand-inner {
  max-width: 460px;
}
.brand-logo {
  display: flex;
  align-items: center;
  gap: 12px;
  font-size: 18px;
  font-weight: 600;
  letter-spacing: 0.02em;
}
.brand-slogan {
  margin: 40px 0 12px;
  font-size: 30px;
  font-weight: 600;
  line-height: 1.4;
}
.brand-desc {
  margin: 0 0 40px;
  font-size: 15px;
  line-height: 1.6;
  color: rgba(255, 255, 255, 0.72);
}
.trust-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: 20px;
}
.trust-list li {
  display: flex;
  align-items: flex-start;
  gap: 14px;
}
.trust-icon {
  flex: none;
  width: 40px;
  height: 40px;
  display: grid;
  place-items: center;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.12);
}
.trust-text {
  display: grid;
  gap: 2px;
}
.trust-text strong {
  font-size: 15px;
  font-weight: 600;
}
.trust-text span {
  font-size: 13px;
  color: rgba(255, 255, 255, 0.66);
}
.brand-foot {
  position: absolute;
  left: 56px;
  bottom: 28px;
  margin: 0;
  font-size: 12px;
  color: rgba(255, 255, 255, 0.45);
}

/* ---------- 右侧表单区 ---------- */
.panel {
  flex: 1;
  display: grid;
  place-items: center;
  padding: 24px;
}
.panel-inner {
  width: min(400px, 100%);
  animation: rise 0.4s ease-out both;
}
@keyframes rise {
  from {
    opacity: 0;
    transform: translateY(12px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}
.panel-brand {
  display: none;
  align-items: center;
  gap: 10px;
  margin-bottom: 32px;
  font-size: 17px;
  font-weight: 600;
  color: var(--navy-900);
}
.form-title {
  margin: 0 0 4px;
  font-size: 26px;
  font-weight: 600;
}
.form-sub {
  margin: 0 0 28px;
  font-size: 14px;
  color: var(--muted);
}
.form-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin: -4px 0 16px;
}
.submit {
  width: 100%;
  min-height: 44px;
  font-size: 16px;
  font-weight: 500;
}
.field-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  font-size: 13px;
  color: #9a3412;
}
.form-error {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 14px;
  padding: 10px 12px;
  border-radius: 8px;
  font-size: 14px;
  color: #b91c1c;
  background: #fef2f2;
  border: 1px solid #fecaca;
}
.form-help {
  margin: 24px 0 0;
  text-align: center;
  font-size: 13px;
  color: var(--muted);
}

/* ---------- Element Plus 局部微调 ---------- */
:deep(.el-form-item__label) {
  font-weight: 500;
  color: var(--navy-900);
}
:deep(.el-input__wrapper.is-focus) {
  box-shadow:
    0 0 0 1px var(--accent) inset,
    0 0 0 3px rgba(3, 105, 161, 0.18);
}
:deep(.el-button:focus-visible) {
  outline: 3px solid rgba(3, 105, 161, 0.5);
  outline-offset: 2px;
}

/* ---------- 响应式：窄屏折叠品牌区 ---------- */
@media (max-width: 991px) {
  .brand {
    display: none;
  }
  .panel-brand {
    display: flex;
  }
}

/* ---------- 降低动效偏好 ---------- */
@media (prefers-reduced-motion: reduce) {
  .panel-inner {
    animation: none;
  }
  .login-page :deep(*) {
    transition-duration: 0.01ms !important;
  }
}
</style>

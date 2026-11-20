<template>
  <div class="login-page erp-app-bg">
    <div class="hero">
      <section class="hero-copy">
        <span class="badge-pill"><span class="dot"></span>采购 · 销售 · 库存 · 财务</span>
        <h1 class="hero-title">
          一个系统，管好<span class="erp-grad-text">进销存</span>
        </h1>
        <p class="hero-sub">单据流转全链路留痕，库存往来实时可见，权限分级可审计。</p>

        <div class="hero-cards">
          <div class="mini erp-glass">
            <span class="mini-lab">今日单据</span>
            <div class="bars" aria-hidden="true">
              <i style="height: 34%"></i><i style="height: 52%"></i><i style="height: 42%"></i>
              <i style="height: 66%"></i><i style="height: 58%"></i><i style="height: 82%"></i><i style="height: 74%"></i>
            </div>
          </div>
          <div class="mini erp-glass">
            <span class="mini-lab">模块里程碑</span>
            <div class="track" aria-hidden="true"><span class="fill"></span></div>
            <span class="mini-note">2 / 7 已上线</span>
          </div>
        </div>
      </section>

      <div class="login-card erp-glass">
        <span class="mark">
          <svg width="20" height="20" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M5 19V9m5 10V5m5 14v-7m4 7H3" stroke="#fff" stroke-width="2.2" stroke-linecap="round" />
          </svg>
        </span>
        <h2 class="title">欢迎回来</h2>
        <p class="sub">登录 ERP 进销存系统</p>

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
            <template #icon><Position /></template>
            登录
          </el-button>

          <div v-if="loginError" class="form-error" role="alert">
            <el-icon><WarningFilled /></el-icon>
            {{ loginError }}
          </div>
        </el-form>

        <p class="form-help">忘记密码？请联系系统管理员重置</p>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Lock, Position, User, WarningFilled } from '@element-plus/icons-vue'
import http from '@/api/http'
import { encryptPassword, LOGIN_CHALLENGE_INVALID } from '@/api/passwordCrypto'
import { useAuthStore } from '@/stores/auth'

const REMEMBER_KEY = 'erp.login.…name'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const loading = ref(false)
const capsOn = ref(false)
const remember = ref(false)
const loginError = ref('')
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
  const cred = await encryptPassword(form.password)
  try {
    return (await http.post('/auth/login', { username: form.username, ...cred })) as unknown as { token: string }
  } catch (e) {
    // 挑战过期（页面停留超 TTL）：静默重取挑战重试一次
    if ((e as Error & { code?: number }).code === LOGIN_CHALLENGE_INVALID) {
      const fresh = await encryptPassword(form.password)
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
  height: 100vh;
  height: 100dvh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: var(--erp-space-4) 56px;
  overflow: hidden;
}

.hero {
  display: grid;
  grid-template-columns: minmax(0, 600px) 460px;
  gap: 48px;
  align-items: center;
  justify-content: center;
  width: 100%;
}

/* ---------- 左侧品牌叙事 ---------- */
.badge-pill {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  background: var(--erp-blue-soft);
  color: var(--erp-blue);
  border-radius: 999px;
  padding: 7px 14px;
  font-size: 12.5px;
  font-weight: 600;
}
.badge-pill .dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--erp-blue);
}
.hero-title {
  margin: 18px 0 10px;
  font-size: clamp(28px, 3.4vw, 40px);
  font-weight: 800;
  line-height: 1.25;
  letter-spacing: -0.01em;
  color: var(--erp-ink);
}
.hero-sub {
  margin: 0 0 28px;
  font-size: 14px;
  line-height: 1.7;
  color: var(--erp-muted);
}
.hero-cards {
  display: grid;
  grid-template-columns: 1fr;
  gap: var(--erp-space-3);
  max-width: 420px;
}
.mini {
  border-radius: var(--erp-radius);
  padding: 14px 16px;
}
.mini-lab {
  display: block;
  font-size: 12px;
  font-weight: 600;
  color: var(--erp-muted);
  margin-bottom: 10px;
}
.bars {
  display: flex;
  gap: 6px;
  align-items: flex-end;
  height: 40px;
}
.bars i {
  flex: 1;
  border-radius: 4px;
  background: linear-gradient(180deg, var(--erp-blue-sky), var(--erp-blue));
  opacity: .85;
}
.track {
  height: 8px;
  border-radius: 999px;
  background: var(--erp-blue-soft);
  overflow: hidden;
}
.track .fill {
  display: block;
  width: 28.5%;
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, var(--erp-blue), var(--erp-blue-sky));
}
.mini-note {
  display: block;
  margin-top: 8px;
  font-size: 12px;
  color: var(--erp-muted);
}

/* ---------- 右侧玻璃登录卡（贴满右列） ---------- */
.login-card {
  position: relative;
  border-radius: 24px;
  padding: 34px 32px;
  width: 100%;
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
.mark {
  width: 44px;
  height: 44px;
  border-radius: 14px;
  display: grid;
  place-items: center;
  color: #fff;
  background: linear-gradient(135deg, var(--erp-blue-sky), var(--erp-blue));
  box-shadow: 0 8px 18px -8px rgba(37, 99, 235, 0.6);
  margin-bottom: 18px;
}
.title {
  margin: 0 0 4px;
  font-size: 22px;
  font-weight: 800;
  color: var(--erp-ink);
}
.sub {
  margin: 0 0 22px;
  font-size: 13px;
  color: var(--erp-muted);
}
.form-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--erp-space-2);
  margin: -4px 0 16px;
}
.submit {
  width: 100%;
  min-height: 44px;
  font-size: 15px;
  font-weight: 600;
  border-radius: 12px;
  box-shadow: 0 8px 18px -8px rgba(37, 99, 235, 0.65);
}
.field-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 6px;
  font-size: 13px;
  color: var(--erp-amber);
}
.form-error {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 14px;
  padding: 10px 12px;
  border-radius: 10px;
  font-size: 14px;
  color: var(--erp-red-text);
  background: var(--erp-red-soft);
  border: 1px solid var(--erp-red-border);
}
.form-help {
  margin: 20px 0 0;
  text-align: center;
  font-size: 13px;
  color: var(--erp-muted);
}

:deep(.el-form-item__label) {
  font-weight: 600;
  color: #334155;
}
:deep(.el-input__wrapper) {
  background: rgba(250, 251, 255, 0.7);
  border-radius: 12px;
}

/* 矮窗口兜底：内容放不下时允许滚动，避免登录卡被裁切 */
@media (max-height: 680px) {
  .login-page {
    overflow-y: auto;
    align-items: flex-start;
  }
}

/* ---------- 窄屏：隐藏叙事区，卡片居中 ---------- */
@media (max-width: 999px) {
  .hero {
    grid-template-columns: minmax(0, 460px);
    justify-content: center;
  }
  .hero-copy {
    display: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .login-card {
    animation: none;
  }
}
</style>

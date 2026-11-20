<template>
  <div class="login-page erp-app-bg">
    <span class="chip chip-a erp-glass" aria-hidden="true">
      <span class="ok"><el-icon><Check /></el-icon></span>
      密码加密传输
    </span>
    <span class="chip chip-b erp-glass" aria-hidden="true">
      <span class="ok"><el-icon><Check /></el-icon></span>
      会话安全
    </span>

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
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Check, Lock, Position, User, WarningFilled } from '@element-plus/icons-vue'
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
  min-height: 100vh;
  min-height: 100dvh;
  display: grid;
  place-items: center;
  padding: var(--erp-space-4);
  position: relative;
  overflow: hidden;
}

/* 浮动信任 chip（装饰性，窄屏隐藏） */
.chip {
  position: absolute;
  display: flex;
  align-items: center;
  gap: 8px;
  border-radius: 14px;
  padding: 10px 16px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--erp-ink);
}
.chip .ok {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: var(--erp-green-soft);
  color: var(--erp-green-dark);
  display: grid;
  place-items: center;
  font-size: 12px;
}
.chip-a {
  top: 64px;
  right: 14%;
}
.chip-b {
  bottom: 72px;
  left: 16%;
}
@media (max-width: 720px) {
  .chip {
    display: none;
  }
}

/* 玻璃登录卡 */
.login-card {
  width: min(400px, 100%);
  border-radius: 24px;
  padding: 34px 32px;
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
  background: linear-gradient(135deg, var(--erp-blue), var(--erp-violet));
  box-shadow: 0 8px 18px -8px rgba(79, 70, 229, 0.6);
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
  margin: -4px 0 16px;
}
.submit {
  width: 100%;
  min-height: 44px;
  font-size: 15px;
  font-weight: 600;
  border-radius: 12px;
  box-shadow: 0 8px 18px -8px rgba(34, 197, 94, 0.6);
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
  color: #b91c1c;
  background: var(--erp-red-soft);
  border: 1px solid #fecaca;
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

@media (prefers-reduced-motion: reduce) {
  .login-card {
    animation: none;
  }
}
</style>

import { defineStore } from 'pinia'
import http from '@/api/http'

export interface MeInfo {
  id: number
  username: string
  realName?: string
  perms: string[]
  warehouses: number[] // 空数组=无可见仓库；含 0=全部仓库
}

const TOKEN_KEY = 'erp-token'

/**
 * 会话状态：token + 权限码集合 + 可见仓库（spec §5.3 /api/auth/me）。
 * token 存 localStorage 仅作刷新恢复；会话数据以 me 接口为准。
 */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem(TOKEN_KEY) || '',
    user: null as MeInfo | null,
    loaded: false
  }),
  getters: {
    hasPerm: (state) => (code: string) => state.user?.perms.includes(code) ?? false,
    canViewCost: (state) => state.user?.perms.includes('inventory:cost:view') ?? false,
    allWarehouses: (state) => (state.user?.warehouses ?? []).includes(0)
  },
  actions: {
    setToken(token: string) {
      this.token = token
      localStorage.setItem(TOKEN_KEY, token)
    },
    async loadMe() {
      this.user = (await http.get('/auth/me')) as unknown as MeInfo
      this.loaded = true
    },
    clearSession() {
      this.token = ''
      this.user = null
      this.loaded = false
      localStorage.removeItem(TOKEN_KEY)
    },
    async logout() {
      try {
        await http.post('/auth/logout')
      } finally {
        this.clearSession()
      }
    }
  }
})

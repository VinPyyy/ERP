import axios from 'axios'
import { ElMessage } from 'element-plus'
import { useAuthStore } from '@/stores/auth'
import router from '@/router'

/**
 * axios 封装（spec §5.1）：
 * - 后端业务错误一律 HTTP 200 + code≠0 → 统一 toast msg 并 reject
 * - 401 → 清会话跳登录；403 → toast 无权限
 * - 成功时直接返回 R.data（调用方拿到的就是业务数据）
 */
const http = axios.create({
  baseURL: '/api',
  timeout: 30000
})

http.interceptors.request.use((config) => {
  const auth = useAuthStore()
  if (auth.token) {
    config.headers['Authorization'] = auth.token
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body && typeof body.code === 'number') {
      if (body.code !== 0) {
        ElMessage.error(body.msg || '操作失败')
        const error = new Error(body.msg || `业务错误 ${body.code}`) as Error & { code?: number }
        error.code = body.code
        return Promise.reject(error)
      }
      return body.data
    }
    return body
  },
  (error) => {
    const status = error.response?.status
    if (status === 401) {
      const auth = useAuthStore()
      auth.clearSession()
      ElMessage.error('未登录或登录已过期')
      router.push('/login')
    } else if (status === 403) {
      ElMessage.error('无操作权限')
    } else {
      ElMessage.error(error.response?.data?.msg || error.message || '网络错误')
    }
    return Promise.reject(error)
  }
)

export default http

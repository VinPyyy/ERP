import type { Directive } from 'vue'
import { useAuthStore } from '@/stores/auth'

/**
 * v-perm="'sale:outbound:audit'" 或 v-perm="['a','b']"（任一命中即可）：
 * 无权限时直接移除元素（按钮级权限，spec §2.1）。
 */
export const perm: Directive<HTMLElement, string | string[]> = {
  mounted(el, binding) {
    const auth = useAuthStore()
    const required = Array.isArray(binding.value) ? binding.value : [binding.value]
    const allowed = required.some((code) => auth.hasPerm(code))
    if (!allowed) {
      el.parentNode?.removeChild(el)
    }
  }
}

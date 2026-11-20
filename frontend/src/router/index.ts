import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import BasicLayout from '@/layouts/BasicLayout.vue'

/**
 * 路由静态定义（spec §2.1：不做后端动态路由）；菜单按登录返回的 perm_code 过滤。
 * 各业务页面在 M1–M7 里程碑注册到 Layout children。
 */
const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/login/index.vue'),
      meta: { public: true }
    },
    {
      path: '/',
      component: BasicLayout,
      children: [
        {
          path: '',
          name: 'home',
          component: () => import('@/views/home/index.vue')
        },
        // M1：系统管理
        { path: 'system/user', name: 'system-user', component: () => import('@/views/system/user/index.vue') },
        { path: 'system/role', name: 'system-role', component: () => import('@/views/system/role/index.vue') },
        { path: 'system/config', name: 'system-config', component: () => import('@/views/system/config/index.vue') },
        { path: 'system/doc-no', name: 'system-docno', component: () => import('@/views/system/doc-no/index.vue') },
        // M2：基础资料
        { path: 'basedata/product', name: 'basedata-product', component: () => import('@/views/basedata/product/index.vue') },
        { path: 'basedata/customer', name: 'basedata-customer', component: () => import('@/views/basedata/partner/index.vue'), props: { kind: 'customer' } },
        { path: 'basedata/supplier', name: 'basedata-supplier', component: () => import('@/views/basedata/partner/index.vue'), props: { kind: 'supplier' } },
        { path: 'basedata/warehouse', name: 'basedata-warehouse', component: () => import('@/views/basedata/warehouse/index.vue') }
        // M3: /inventory/*  M4: /purchase/*  M5: /sale/*  M6: 盘点调拨  M7: /print/:docType/:id（Layout 外）
      ]
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/'
    }
  ]
})

router.beforeEach(async (to) => {
  if (to.meta.public) return true
  const auth = useAuthStore()
  if (!auth.token) return { path: '/login', query: { redirect: to.fullPath } }
  if (!auth.loaded) {
    try {
      await auth.loadMe()
    } catch {
      auth.clearSession()
      return { path: '/login' }
    }
  }
  return true
})

export default router

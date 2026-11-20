import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import BasicLayout from '@/layouts/BasicLayout.vue'

/**
 * 路由静态定义（spec §2.1：不做后端动态路由）；菜单按登录返回的 perm_code 过滤。
 * 各业务页面在 M1–M7 里程碑注册到 Layout children。
 */
const router = createRouter({
  // base 跟随 vite.config 的 base（/erp/），路由 path 仍按应用内相对路径书写
  history: createWebHistory(import.meta.env.BASE_URL),
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
          component: () => import('@/views/home/index.vue'),
          meta: { title: '首页' }
        },
        // M1：系统管理（meta.title 与菜单、页头三处必须同名）
        { path: 'system/user', name: 'system-user', component: () => import('@/views/system/user/index.vue'), meta: { title: '用户管理' } },
        { path: 'system/role', name: 'system-role', component: () => import('@/views/system/role/index.vue'), meta: { title: '角色管理' } },
        { path: 'system/config', name: 'system-config', component: () => import('@/views/system/config/index.vue'), meta: { title: '系统参数' } },
        { path: 'system/doc-no', name: 'system-docno', component: () => import('@/views/system/doc-no/index.vue'), meta: { title: '编号规则' } },
        // M2：基础资料
        { path: 'basedata/product', name: 'basedata-product', component: () => import('@/views/basedata/product/index.vue'), meta: { title: '商品档案' } },
        { path: 'basedata/customer', name: 'basedata-customer', component: () => import('@/views/basedata/partner/index.vue'), props: { kind: 'customer' }, meta: { title: '客户管理' } },
        { path: 'basedata/supplier', name: 'basedata-supplier', component: () => import('@/views/basedata/partner/index.vue'), props: { kind: 'supplier' }, meta: { title: '供应商管理' } },
        { path: 'basedata/warehouse', name: 'basedata-warehouse', component: () => import('@/views/basedata/warehouse/index.vue'), meta: { title: '仓库档案' } },
        // M3：库存
        { path: 'inventory/stock', name: 'inventory-stock', component: () => import('@/views/inventory/stock/index.vue'), meta: { title: '库存查询' } },
        { path: 'inventory/flow', name: 'inventory-flow', component: () => import('@/views/inventory/flow/index.vue'), meta: { title: '库存流水' } },
        { path: 'inventory/opening', name: 'inventory-opening', component: () => import('@/views/inventory/opening/index.vue'), meta: { title: '期初库存' } }
        // M4: /purchase/*  M5: /sale/*  M6: 盘点调拨  M7: /print/:docType/:id（Layout 外）
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

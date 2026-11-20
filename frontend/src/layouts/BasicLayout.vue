<template>
  <div class="layout erp-app-bg">
    <aside class="side erp-glass">
      <div class="brand">
        <span class="mark">
          <svg width="18" height="18" viewBox="0 0 24 24" fill="none" aria-hidden="true">
            <path d="M5 19V9m5 10V5m5 14v-7m4 7H3" stroke="#fff" stroke-width="2.2" stroke-linecap="round" />
          </svg>
        </span>
        <b>ERP 进销存</b>
      </div>
      <el-menu class="menu" :default-active="route.path" router unique-opened>
        <template v-for="group in visibleMenus" :key="group.path">
          <el-sub-menu :index="group.path">
            <template #title>
              <el-icon v-if="group.icon"><component :is="group.icon" /></el-icon>
              <span>{{ group.title }}</span>
            </template>
            <el-menu-item v-for="item in group.children" :key="item.path" :index="item.path">
              {{ item.title }}
            </el-menu-item>
          </el-sub-menu>
        </template>
      </el-menu>
    </aside>

    <div class="col">
      <header class="topbar erp-glass">
        <el-breadcrumb separator="/">
          <el-breadcrumb-item v-if="moduleTitle">{{ moduleTitle }}</el-breadcrumb-item>
          <el-breadcrumb-item>{{ route.meta.title || '首页' }}</el-breadcrumb-item>
        </el-breadcrumb>
        <el-dropdown @command="onCommand">
          <span class="user">
            <span class="avatar">{{ userInitial }}</span>
            <span class="user-name">{{ auth.user?.realName || auth.user?.username || '未登录' }}</span>
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </header>
      <main class="main">
        <router-view />
      </main>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

interface MenuItem {
  path: string
  title: string
  icon?: string
  perm?: string
  children?: MenuItem[]
}

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

// 菜单树（perm 对应 sys_permission.perm_code；M1+ 页面逐个接入后生效）
const menus: MenuItem[] = [
  {
    path: '/basedata', title: '基础资料', icon: 'Files', perm: 'basedata:menu', children: [
      { path: '/basedata/product', title: '商品', perm: 'basedata:product:list' },
      { path: '/basedata/customer', title: '客户', perm: 'basedata:customer:list' },
      { path: '/basedata/supplier', title: '供应商', perm: 'basedata:supplier:list' },
      { path: '/basedata/warehouse', title: '仓库', perm: 'basedata:warehouse:list' }
    ]
  },
  {
    path: '/purchase', title: '采购管理', icon: 'ShoppingCart', perm: 'purchase:menu', children: [
      { path: '/purchase/inquiry', title: '询价单', perm: 'purchase:inquiry:list' },
      { path: '/purchase/order', title: '采购订单', perm: 'purchase:order:list' },
      { path: '/purchase/inbound', title: '采购入库', perm: 'purchase:inbound:list' },
      { path: '/purchase/return', title: '采购退货', perm: 'purchase:return:list' }
    ]
  },
  {
    path: '/sale', title: '销售管理', icon: 'Sell', perm: 'sale:menu', children: [
      { path: '/sale/quote', title: '销售报价', perm: 'sale:quote:list' },
      { path: '/sale/order', title: '销售订单', perm: 'sale:order:list' },
      { path: '/sale/outbound', title: '销售出库', perm: 'sale:outbound:list' },
      { path: '/sale/return', title: '销售退货', perm: 'sale:return:list' }
    ]
  },
  {
    path: '/inventory', title: '库存管理', icon: 'Box', perm: 'inventory:menu', children: [
      { path: '/inventory/stock', title: '库存查询', perm: 'inventory:stock:list' },
      { path: '/inventory/flow', title: '库存流水', perm: 'inventory:flow:list' },
      { path: '/inventory/opening', title: '期初库存', perm: 'inventory:opening:list' },
      { path: '/inventory/transfer', title: '调拨单', perm: 'inventory:transfer:list' },
      { path: '/inventory/count', title: '盘点单', perm: 'inventory:count:list' }
    ]
  },
  {
    path: '/system', title: '系统管理', icon: 'Setting', perm: 'system:menu', children: [
      { path: '/system/user', title: '用户', perm: 'system:user:list' },
      { path: '/system/role', title: '角色', perm: 'system:role:list' },
      { path: '/system/config', title: '系统参数', perm: 'system:config:list' },
      { path: '/system/doc-no', title: '编号规则', perm: 'system:docno:list' }
    ]
  }
]

const visibleMenus = computed(() =>
  menus
    .filter((g) => auth.hasPerm(g.perm!))
    .map((g) => ({ ...g, children: (g.children || []).filter((c) => auth.hasPerm(c.perm!)) }))
    .filter((g) => g.children.length > 0)
)

/** 面包屑第一级：当前路径所属菜单组；首页无组则不显示 */
const moduleTitle = computed(() => {
  const group = menus.find((g) => route.path.startsWith(g.path + '/') || route.path === g.path)
  return group?.title ?? ''
})

const userInitial = computed(() => {
  const name = auth.user?.realName || auth.user?.username || '?'
  return name.slice(0, 1)
})

async function onCommand(cmd: string) {
  if (cmd === 'logout') {
    await auth.logout()
    router.push('/login')
  }
}
</script>

<style scoped>
.layout {
  display: flex;
  gap: var(--erp-space-3);
  height: 100vh;
  padding: var(--erp-space-3);
}

/* ---------- 玻璃侧栏 ---------- */
.side {
  width: 232px;
  flex: none;
  border-radius: var(--erp-radius-lg);
  padding: 18px 12px;
  overflow-y: auto;
  scrollbar-width: thin;
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 2px 10px 16px;
  font-size: 15px;
  color: var(--erp-ink);
}
.mark {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  display: grid;
  place-items: center;
  color: #fff;
  background: linear-gradient(135deg, var(--erp-blue), var(--erp-violet));
  box-shadow: 0 6px 14px -6px rgba(79, 70, 229, 0.55);
}
.menu {
  border-right: none;
  background: transparent;
  --el-menu-bg-color: transparent;
  --el-menu-text-color: #475569;
  --el-menu-hover-bg-color: rgba(244, 246, 253, 0.9);
  --el-menu-active-color: var(--erp-blue);
}
.menu :deep(.el-sub-menu__title) {
  border-radius: 10px;
  font-weight: 600;
  color: var(--erp-muted);
}
.menu :deep(.el-sub-menu .el-menu) {
  background: transparent;
}
.menu :deep(.el-menu-item) {
  min-height: 38px;
  height: 38px;
  line-height: 38px;
  border-radius: 10px;
  font-size: 13.5px;
}
.menu :deep(.el-menu-item.is-active) {
  background: var(--erp-blue-soft);
  font-weight: 600;
}
.menu :deep(.el-menu-item:focus-visible),
.menu :deep(.el-sub-menu__title:focus-visible) {
  outline: 2px solid rgba(47, 124, 246, 0.55);
  outline-offset: -2px;
}

/* ---------- 玻璃顶栏 + 内容 ---------- */
.col {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: var(--erp-space-3);
}
.topbar {
  border-radius: var(--erp-radius);
  height: 56px;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 var(--erp-space-3);
}
.topbar :deep(.el-breadcrumb__inner) {
  font-size: 13px;
  color: var(--erp-muted);
}
.topbar :deep(.el-breadcrumb__inner.is-link) {
  color: var(--erp-muted);
  font-weight: 400;
}
.topbar :deep(.el-breadcrumb__item:last-child .el-breadcrumb__inner) {
  color: var(--erp-ink);
  font-weight: 600;
}
.user {
  display: flex;
  align-items: center;
  gap: var(--erp-space-1);
  cursor: pointer;
  color: var(--erp-ink);
  font-size: 13.5px;
}
.avatar {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  color: #fff;
  display: grid;
  place-items: center;
  font-size: 13px;
  font-weight: 700;
  background: linear-gradient(135deg, var(--erp-blue), var(--erp-violet));
}
.user-name {
  font-weight: 500;
}
.main {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
}
</style>

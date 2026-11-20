<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="logo">ERP 进销存</div>
      <el-menu :default-active="route.path" router>
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
    </el-aside>
    <el-container>
      <el-header class="header">
        <div />
        <el-dropdown @command="onCommand">
          <span class="user">
            {{ auth.user?.realName || auth.user?.username || '未登录' }}
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
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

async function onCommand(cmd: string) {
  if (cmd === 'logout') {
    await auth.logout()
    router.push('/login')
  }
}
</script>

<style scoped>
.layout { height: 100vh; }
.aside { border-right: 1px solid var(--el-border-color-light); overflow-y: auto; }
.logo { height: 56px; line-height: 56px; text-align: center; font-weight: 600; font-size: 16px; }
.header { display: flex; align-items: center; justify-content: space-between; border-bottom: 1px solid var(--el-border-color-light); }
.user { cursor: pointer; display: flex; align-items: center; gap: 4px; }
.main { background: var(--el-fill-color-lighter); }
</style>

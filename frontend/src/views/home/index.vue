<template>
  <div class="erp-page">
    <div class="hello">
      <span class="badge-pill"><span class="dot"></span>一期建设中</span>
      <h1 class="hello-title">
        你好，<span class="erp-grad-text">{{ displayName }}</span>
      </h1>
      <p class="hello-sub">基础资料、采购、销售、库存模块将随里程碑逐个上线</p>
    </div>

    <div class="kpis">
      <el-card v-if="auth.user" shadow="never" class="kpi">
        <div class="kpi-lab">
          <span class="kpi-ic blue">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <circle cx="12" cy="8" r="3.4" stroke="currentColor" stroke-width="2" />
              <path d="M5.5 19.5a6.5 6.5 0 0113 0" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
            </svg>
          </span>
          权限点
        </div>
        <div class="kpi-num">{{ auth.user.perms?.length ?? 0 }}</div>
        <p class="kpi-note">当前账号可执行的操作数</p>
      </el-card>

      <el-card v-if="auth.user" shadow="never" class="kpi">
        <div class="kpi-lab">
          <span class="kpi-ic violet">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path d="M12 3l7 3v5c0 4.5-2.8 8.3-7 9.5-4.2-1.2-7-5-7-9.5V6l7-3z" stroke="currentColor" stroke-width="2" stroke-linejoin="round" />
            </svg>
          </span>
          角色
        </div>
        <div class="kpi-num">{{ auth.user.roles?.length ?? 0 }}</div>
        <p class="kpi-note">{{ auth.user.roles?.join('、') || '未分配角色' }}</p>
      </el-card>

      <el-card v-if="auth.user" shadow="never" class="kpi">
        <div class="kpi-lab">
          <span class="kpi-ic green">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <path d="M4 20h16M7 20v-6m5 6V8m5 12v-9" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
            </svg>
          </span>
          可见仓库
        </div>
        <div class="kpi-num">{{ auth.allWarehouses ? '全部' : (auth.user.warehouses?.length ?? 0) }}</div>
        <p class="kpi-note">{{ auth.allWarehouses ? '未做仓库级限制' : (auth.user.warehouses?.join(', ') || '无') }}</p>
      </el-card>

      <el-card shadow="never" class="kpi">
        <div class="kpi-lab">
          <span class="kpi-ic blue">
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" aria-hidden="true">
              <rect x="4" y="5" width="16" height="15" rx="3" stroke="currentColor" stroke-width="2" />
              <path d="M8 3v4m8-4v4M4 10h16" stroke="currentColor" stroke-width="2" stroke-linecap="round" />
            </svg>
          </span>
          里程碑
        </div>
        <div class="kpi-num">{{ doneCount }} / {{ milestones.length }}</div>
        <p class="kpi-note">一期已上线模块数</p>
      </el-card>
    </div>

    <el-card shadow="never">
      <div class="card-title">里程碑进度</div>
      <ul class="milestones">
        <li v-for="m in milestones" :key="m.name">
          <span class="m-name">{{ m.name }}</span>
          <el-tag :type="m.done ? 'success' : 'info'" size="small" effect="light">
            {{ m.done ? '已上线' : '规划中' }}
          </el-tag>
        </li>
      </ul>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const displayName = computed(() => auth.user?.realName || auth.user?.username || '')

const milestones = [
  { name: 'M1 认证与系统管理', done: true },
  { name: 'M2 基础资料与 Excel 导入', done: true },
  { name: 'M3 库存核心与期初', done: false },
  { name: 'M4 采购链路', done: false },
  { name: 'M5 销售链路', done: false },
  { name: 'M6 盘点与调拨', done: false },
  { name: 'M7 打印导出与部署', done: false },
]
const doneCount = computed(() => milestones.filter((m) => m.done).length)
</script>

<style scoped>
.hello {
  padding: 4px 4px 0;
}
.badge-pill {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  background: var(--erp-blue-soft);
  color: var(--erp-blue);
  border-radius: 999px;
  padding: 6px 14px;
  font-size: 12.5px;
  font-weight: 600;
}
.badge-pill .dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--erp-green);
}
.hello-title {
  margin: 12px 0 0;
  font-size: 28px;
  font-weight: 800;
  letter-spacing: -0.01em;
  color: var(--erp-ink);
}
.hello-sub {
  margin: 6px 0 0;
  font-size: 13px;
  color: var(--erp-muted);
}

.kpis {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: var(--erp-space-3);
}
.kpi {
  position: relative;
}
.kpi-lab {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--erp-muted);
}
.kpi-ic {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  display: grid;
  place-items: center;
}
.kpi-ic.blue { background: var(--erp-blue-soft); color: var(--erp-blue); }
.kpi-ic.violet { background: var(--erp-violet-soft); color: var(--erp-violet); }
.kpi-ic.green { background: var(--erp-green-soft); color: var(--erp-green-dark); }
.kpi-num {
  margin-top: 10px;
  font-size: 26px;
  font-weight: 800;
  letter-spacing: -0.01em;
  color: var(--erp-ink);
  font-variant-numeric: tabular-nums;
}
.kpi-note {
  margin: 6px 0 0;
  font-size: 12px;
  color: var(--erp-muted);
}

.card-title {
  font-size: 14px;
  font-weight: 700;
  color: var(--erp-ink);
  margin-bottom: var(--erp-space-2);
}
.milestones {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: var(--erp-space-2);
}
.milestones li {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--erp-space-2);
}
.m-name {
  font-size: 13.5px;
  color: var(--erp-ink);
}
</style>

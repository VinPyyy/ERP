<template>
  <el-card shadow="never">
    <h2>欢迎使用 ERP 进销存系统</h2>
    <p class="hint">
      一期建设中：基础资料、采购、销售、库存模块将随里程碑逐个上线。
      三期看板（KPI 卡片 + 趋势图）落在此页。
    </p>
    <el-descriptions v-if="auth.user" title="当前会话" :column="2" border>
      <el-descriptions-item label="用户">{{ auth.user.realName || auth.user.username }}</el-descriptions-item>
      <el-descriptions-item label="权限点数量">{{ auth.user.perms?.length ?? 0 }}</el-descriptions-item>
      <el-descriptions-item label="可见仓库">
        {{ auth.allWarehouses ? '全部仓库' : (auth.user.warehouses?.join(', ') || '无') }}
      </el-descriptions-item>
    </el-descriptions>
  </el-card>
</template>

<script setup lang="ts">
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
</script>

<style scoped>
.hint { color: var(--el-text-color-secondary); margin-bottom: 16px; }
</style>

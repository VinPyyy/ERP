<template>
  <header class="page-header">
    <div class="page-header-text">
      <h1 class="page-title">
        {{ title }}<span v-if="accent" class="erp-grad-text">{{ accent }}</span>
      </h1>
      <p v-if="subtitle" class="page-subtitle">{{ subtitle }}</p>
    </div>
    <div class="page-header-actions">
      <slot name="extra" />
    </div>
  </header>
</template>

<script setup lang="ts">
/**
 * 统一页面头：title + accent（渐变强调词）+ 一句话说明，右侧放页面级主操作。
 * 过滤条件不放这里，放卡片内 .erp-toolbar。
 */
defineProps<{
  title: string
  /** 标题尾部用蓝紫渐变强调的词，如 title="用户" accent="管理" */
  accent?: string
  subtitle?: string
}>()
</script>

<style scoped>
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: var(--erp-space-3);
  flex-wrap: wrap;
  padding: 4px 4px 0;
}
.page-title {
  margin: 0;
  font-size: 24px;
  font-weight: 800;
  line-height: 1.3;
  letter-spacing: -0.01em;
  color: var(--erp-ink);
}
.page-subtitle {
  margin: 4px 0 0;
  font-size: 13px;
  color: var(--erp-muted);
}
.page-header-actions {
  display: flex;
  align-items: center;
  gap: var(--erp-space-1);
}
</style>

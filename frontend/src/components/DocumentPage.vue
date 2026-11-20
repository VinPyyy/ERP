<template>
  <div class="doc-page">
    <el-card shadow="never">
      <!-- 单据头表单 -->
      <slot name="header" />
    </el-card>
    <el-card shadow="never" class="items">
      <!-- 可编辑明细表格 -->
      <slot name="items" />
    </el-card>
    <div class="actions">
      <slot name="actions-prepend" />
      <el-button v-if="editable" type="primary" plain :loading="saving" @click="emit('save')">
        保存草稿
      </el-button>
      <el-button v-if="editable" type="primary" :loading="auditing" @click="emit('audit')">
        提交审核
      </el-button>
      <el-button v-if="audited" type="warning" plain @click="emit('close-doc')">关闭</el-button>
      <el-button v-if="voidable" type="danger" plain @click="emit('void')">作废</el-button>
      <el-button @click="emit('print')">打印</el-button>
      <slot name="actions-append" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'

/**
 * 单据页公共组件（spec §7）：头表单 + 明细表格 + 底部按钮条。
 * 按钮可见性由 status 驱动（10 草稿可编辑；20 已审核可关闭/作废；终态只可打印）。
 * M4 起各单据编辑页复用本组件。
 */
const props = defineProps<{
  /** DocStatus code: 10/15/20/30/40/50 */
  status: number
  /** 是否显示关闭按钮（订单类才有） */
  closable?: boolean
}>()

const emit = defineEmits<{
  save: []
  audit: []
  'close-doc': []
  void: []
  print: []
}>()

const saving = ref(false)
const auditing = ref(false)

const editable = computed(() => props.status === 10)
const audited = computed(() => props.status === 20)
const voidable = computed(() => props.closable !== undefined && [10, 15, 20].includes(props.status))
</script>

<style scoped>
.doc-page { display: flex; flex-direction: column; gap: 12px; }
.items :deep(.el-card__body) { padding-top: 8px; }
.actions { display: flex; gap: 8px; justify-content: center; padding: 8px 0 24px; }
</style>

<template>
  <div class="erp-page">
    <PageHeader title="编号" accent="规则" subtitle="格式：{前缀}-{日期段}-{流水}，如 XSDD-20261119-0001" />

    <el-card shadow="never">
    <div class="hint">
      <el-icon class="hint-ic"><WarningFilled /></el-icon>
      作废不回收号，跳号属正常现象
    </div>
    <el-table v-loading="loading" :data="rules" class="erp-table">
      <el-table-column prop="docType" label="单据类型" min-width="180" />
      <el-table-column prop="prefix" label="前缀" min-width="110" />
      <el-table-column prop="datePattern" label="日期段" min-width="130" />
      <el-table-column prop="seqLength" label="流水位数" min-width="100" />
      <el-table-column label="重置周期" min-width="120">
        <template #default="{ row }">{{ scopeLabels[row.seqScope as string] ?? row.seqScope }}</template>
      </el-table-column>
      <el-table-column prop="currentSeq" label="当前流水" min-width="110" />
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button v-perm="'system:docno:edit'" link type="primary" @click="openEdit(row)">
            <template #icon><Edit /></template>
            编辑
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    </el-card>
  </div>

  <el-dialog v-model="dialogVisible" title="编辑编号规则" width="420px">
    <el-form label-width="90px">
      <el-form-item label="单据类型"><el-input :model-value="editing?.docType" disabled /></el-form-item>
      <el-form-item label="前缀" required><el-input v-model="form.prefix" /></el-form-item>
      <el-form-item label="日期段">
        <el-select v-model="form.datePattern" style="width: 100%">
          <el-option label="yyyyMMdd" value="yyyyMMdd" />
          <el-option label="yyyyMM" value="yyyyMM" />
          <el-option label="无" :value="''" />
        </el-select>
      </el-form-item>
      <el-form-item label="流水位数"><el-input-number v-model="form.seqLength" :min="3" :max="8" /></el-form-item>
      <el-form-item label="重置周期">
        <el-select v-model="form.seqScope" style="width: 100%">
          <el-option v-for="(label, val) in scopeLabels" :key="val" :label="label" :value="val" />
        </el-select>
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">
        <template #icon><Close /></template>
        取消
      </el-button>
      <el-button type="primary" @click="save">
        <template #icon><Check /></template>
        保存
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { docNoApi, type DocNoRuleVO } from '@/api/system'
import PageHeader from '@/components/PageHeader.vue'

const loading = ref(false)
const rules = ref<DocNoRuleVO[]>([])
// seqScope 枚举 → 中文显示（与编辑弹窗选项一致）
const scopeLabels: Record<string, string> = { DAY: '按日', MONTH: '按月', NONE: '不重置' }
const dialogVisible = ref(false)
const editing = ref<DocNoRuleVO | null>(null)
const form = reactive({ prefix: '', datePattern: 'yyyyMMdd', seqLength: 4, seqScope: 'DAY' })

async function reload() {
  loading.value = true
  try {
    rules.value = await docNoApi.list()
  } finally {
    loading.value = false
  }
}
reload()

function openEdit(row: DocNoRuleVO) {
  editing.value = row
  Object.assign(form, {
    prefix: row.prefix,
    datePattern: row.datePattern ?? 'yyyyMMdd',
    seqLength: row.seqLength,
    seqScope: row.seqScope
  })
  dialogVisible.value = true
}

async function save() {
  if (!editing.value) return
  await docNoApi.update(editing.value.id, { ...form })
  ElMessage.success('已保存')
  dialogVisible.value = false
  await reload()
}
</script>

<style scoped>
/* 提示条：无底色（与卡片白一致）+ 前置警示图标 */
.hint {
  display: flex;
  align-items: center;
  gap: var(--erp-space-1);
  margin-bottom: var(--erp-space-2);
  font-size: 13px;
  color: var(--erp-muted);
}
.hint-ic {
  color: var(--erp-amber);
  font-size: 15px;
}
</style>

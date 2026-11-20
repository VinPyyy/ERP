<template>
  <div class="erp-page">
    <PageHeader title="系统" accent="参数" subtitle="全局业务参数配置，修改后即时生效" />

    <el-card shadow="never">
    <el-table v-loading="loading" :data="configs" class="erp-table">
      <el-table-column prop="paramKey" label="参数键" min-width="220" />
      <el-table-column prop="paramName" label="参数名" min-width="140" />
      <el-table-column prop="paramValue" label="值" min-width="120" />
      <el-table-column prop="valueType" label="类型" min-width="100" />
      <el-table-column prop="remark" label="说明" min-width="240" />
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button v-perm="'system:config:edit'" link type="primary" @click="openEdit(row)">
            <template #icon><EditPen /></template>
            修改
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    </el-card>
  </div>

  <el-dialog v-model="dialogVisible" title="修改参数" width="420px">
    <el-form label-width="90px">
      <el-form-item label="参数键"><el-input :model-value="editing?.paramKey" disabled /></el-form-item>
      <el-form-item label="新值" required>
        <!-- BOOL：只允许选 true/false，杜绝自由输入 -->
        <el-select v-if="editing?.valueType === 'BOOL'" v-model="newValue" style="width: 100%">
          <el-option label="true" value="true" />
          <el-option label="false" value="false" />
        </el-select>
        <template v-else>
          <el-input
            v-model="newValue"
            :placeholder="valuePlaceholder"
            @input="valueError = ''"
            @blur="checkValue"
          />
          <div v-if="valueError" class="value-error" role="alert">{{ valueError }}</div>
        </template>
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
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { configApi, type ConfigVO } from '@/api/system'
import PageHeader from '@/components/PageHeader.vue'

const loading = ref(false)
const configs = ref<ConfigVO[]>([])
const dialogVisible = ref(false)
const editing = ref<ConfigVO | null>(null)
const newValue = ref('')
const valueError = ref('')

const DECIMAL_RE = /^-?\d+(\.\d+)?$/
const INT_RE = /^-?\d+$/

const valuePlaceholder = computed(() => {
  switch (editing.value?.valueType) {
    case 'DECIMAL':
      return '数字，可含小数，如 0.1'
    case 'INT':
      return '整数，如 4'
    default:
      return '请输入参数值'
  }
})

async function reload() {
  loading.value = true
  try {
    configs.value = await configApi.list()
  } finally {
    loading.value = false
  }
}
reload()

function openEdit(row: ConfigVO) {
  editing.value = row
  newValue.value = row.paramValue ?? ''
  valueError.value = ''
  dialogVisible.value = true
}

/** 与后端 ConfigServiceImpl.validateByType 同一套规则，两端都要过 */
function checkValue(): boolean {
  const v = newValue.value.trim()
  const type = editing.value?.valueType
  if (!v) {
    valueError.value = '参数值不能为空'
  } else if (type === 'DECIMAL' && !DECIMAL_RE.test(v)) {
    valueError.value = '请输入合法数字（如 0.1、1、-2.5）'
  } else if (type === 'INT' && !INT_RE.test(v)) {
    valueError.value = '请输入整数（如 4）'
  } else {
    valueError.value = ''
  }
  return !valueError.value
}

async function save() {
  if (!editing.value) return
  if (editing.value.valueType === 'BOOL') {
    // BOOL 走下拉，理论不会为空；防御一下
    if (!newValue.value) {
      ElMessage.warning('请选择 true 或 false')
      return
    }
  } else if (!checkValue()) {
    return
  }
  await configApi.update(editing.value.paramKey, newValue.value.trim())
  ElMessage.success('已保存，参数即时生效')
  dialogVisible.value = false
  await reload()
}
</script>

<style scoped>
.value-error {
  margin-top: 6px;
  font-size: 13px;
  color: var(--erp-red-text);
}
</style>

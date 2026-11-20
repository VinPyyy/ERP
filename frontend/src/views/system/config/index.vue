<template>
  <el-card shadow="never">
    <el-table v-loading="loading" :data="configs" border>
      <el-table-column prop="paramKey" label="参数键" width="260" />
      <el-table-column prop="paramName" label="参数名" width="180" />
      <el-table-column prop="paramValue" label="值" width="140" />
      <el-table-column prop="valueType" label="类型" width="100" />
      <el-table-column prop="remark" label="说明" min-width="240" />
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button v-perm="'system:config:edit'" link type="primary" @click="openEdit(row)">修改</el-button>
        </template>
      </el-table-column>
    </el-table>
  </el-card>

  <el-dialog v-model="dialogVisible" title="修改参数" width="420px">
    <el-form label-width="90px">
      <el-form-item label="参数键"><el-input :model-value="editing?.paramKey" disabled /></el-form-item>
      <el-form-item label="新值" required><el-input v-model="newValue" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">取消</el-button>
      <el-button type="primary" @click="save">保存</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { configApi, type ConfigVO } from '@/api/system'

const loading = ref(false)
const configs = ref<ConfigVO[]>([])
const dialogVisible = ref(false)
const editing = ref<ConfigVO | null>(null)
const newValue = ref('')

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
  dialogVisible.value = true
}

async function save() {
  if (!editing.value) return
  await configApi.update(editing.value.paramKey, newValue.value)
  ElMessage.success('已保存，参数即时生效')
  dialogVisible.value = false
  await reload()
}
</script>

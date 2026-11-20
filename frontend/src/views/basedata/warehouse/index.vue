<template>
  <div class="erp-page">
    <PageHeader title="仓库" accent="档案" subtitle="仓库是数据权限单位，建议控制在 10 个以内">
      <template #extra>
        <el-button v-perm="'basedata:warehouse:create'" type="primary" @click="openEdit(null)">
          <template #icon><Plus /></template>
          新增仓库
        </el-button>
      </template>
    </PageHeader>

    <el-card shadow="never">
    <el-table v-loading="loading" :data="warehouses" class="erp-table">
      <el-table-column prop="code" label="编码" width="120" />
      <el-table-column prop="name" label="名称" min-width="140" />
      <el-table-column prop="address" label="地址" min-width="180" />
      <el-table-column prop="keeper" label="负责人" width="100" />
      <el-table-column prop="sortNo" label="排序" width="70" />
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button v-perm="'basedata:warehouse:edit'" link type="primary" @click="openEdit(row)">
            <template #icon><Edit /></template>
            编辑
          </el-button>
          <el-button v-perm="'basedata:warehouse:delete'" link type="danger" @click="remove(row)">
            <template #icon><Delete /></template>
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    </el-card>
  </div>

  <el-dialog v-model="editVisible" :title="form.id ? '编辑仓库' : '新增仓库'" width="440px">
    <el-form :model="form" label-width="90px">
      <el-form-item label="编码" required><el-input v-model="form.code" /></el-form-item>
      <el-form-item label="名称" required><el-input v-model="form.name" /></el-form-item>
      <el-form-item label="地址"><el-input v-model="form.address" /></el-form-item>
      <el-form-item label="负责人"><el-input v-model="form.keeper" /></el-form-item>
      <el-form-item label="排序"><el-input-number v-model="form.sortNo" :min="0" /></el-form-item>
      <el-form-item label="状态">
        <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" />
      </el-form-item>
      <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" /></el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="editVisible = false">
        <template #icon><Close /></template>
        取消
      </el-button>
      <el-button type="primary" :loading="saving" @click="save">
        <template #icon><Check /></template>
        保存
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { warehouseApi, type WarehouseVO } from '@/api/basedata'
import PageHeader from '@/components/PageHeader.vue'

const loading = ref(false)
const warehouses = ref<WarehouseVO[]>([])
const editVisible = ref(false)
const saving = ref(false)
const form = reactive<Partial<WarehouseVO>>({ code: '', name: '', address: '', keeper: '', sortNo: 0, status: 1, remark: '' })

async function reload() {
  loading.value = true
  try {
    warehouses.value = await warehouseApi.list()
  } finally {
    loading.value = false
  }
}
onMounted(reload)

function openEdit(row: WarehouseVO | null) {
  Object.assign(form, row ? { ...row } : {
    id: undefined, code: '', name: '', address: '', keeper: '', sortNo: 0, status: 1, remark: ''
  })
  editVisible.value = true
}

async function save() {
  saving.value = true
  try {
    if (form.id) {
      await warehouseApi.update(form.id, form)
    } else {
      await warehouseApi.create(form)
    }
    ElMessage.success('已保存')
    editVisible.value = false
    await reload()
  } finally {
    saving.value = false
  }
}

async function remove(row: WarehouseVO) {
  await ElMessageBox.confirm(`删除仓库 ${row.name}？`, '确认', { type: 'warning' })
  await warehouseApi.remove(row.id)
  ElMessage.success('已删除')
  await reload()
}
</script>

<style scoped>
/* 骨架样式统一走 tokens.css 的 .erp-* 类 */
</style>

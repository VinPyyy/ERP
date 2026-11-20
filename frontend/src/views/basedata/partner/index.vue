<template>
  <div class="erp-page">
    <PageHeader :title="label" accent="管理" :subtitle="`${label}档案与往来信息`">
      <template #extra>
        <el-button v-perm="`${permPrefix}:create`" type="primary" @click="openEdit(null)">
          <template #icon><Plus /></template>
          新增{{ label }}
        </el-button>
      </template>
    </PageHeader>

    <el-card shadow="never">
    <div class="erp-toolbar">
      <el-input v-model="keyword" :placeholder="`${label}编码/名称`" clearable style="width: 220px" @keyup.enter="doSearch" />
      <el-button type="primary" class="erp-btn-soft" @click="doSearch">
        <template #icon><Search /></template>
        查询
      </el-button>
      <el-upload v-perm="`${permPrefix}:import`" :show-file-list="false" accept=".xlsx" :http-request="doImport">
        <el-button plain>
          <template #icon><Upload /></template>
          导入 Excel
        </el-button>
      </el-upload>
      <el-button v-perm="`${permPrefix}:export`" plain @click="doExport">
        <template #icon><Download /></template>
        导出
      </el-button>
    </div>

    <el-table v-loading="loading" :data="rows" class="erp-table">
      <el-table-column prop="code" label="编码" width="120" />
      <el-table-column prop="name" :label="`${label}名称`" min-width="160" />
      <el-table-column prop="contact" label="联系人" width="100" />
      <el-table-column prop="phone" label="电话" width="130" />
      <el-table-column prop="taxNo" label="税号" width="150" />
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <el-button v-perm="`${permPrefix}:edit`" link type="primary" @click="openEdit(row)">
            <template #icon><Edit /></template>
            编辑
          </el-button>
          <el-button v-perm="`${permPrefix}:delete`" link type="danger" @click="remove(row)">
            <template #icon><Delete /></template>
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination
      v-model:current-page="query.pageNum"
      v-model:page-size="query.pageSize"
      :total="total"
      layout="total, sizes, prev, pager, next"
      class="erp-pager"
      @change="load"
    />
    </el-card>
  </div>

  <el-dialog v-model="editVisible" :title="form.id ? `编辑${label}` : `新增${label}`" width="480px">
    <el-form :model="form" label-width="90px">
      <el-form-item label="编码" required><el-input v-model="form.code" /></el-form-item>
      <el-form-item label="名称" required><el-input v-model="form.name" /></el-form-item>
      <el-form-item label="联系人"><el-input v-model="form.contact" /></el-form-item>
      <el-form-item label="电话"><el-input v-model="form.phone" /></el-form-item>
      <el-form-item label="地址"><el-input v-model="form.address" /></el-form-item>
      <el-form-item label="税号"><el-input v-model="form.taxNo" /></el-form-item>
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

  <el-dialog v-model="importVisible" title="导入结果" width="500px">
    <p v-if="importResult && importResult.errors.length === 0">成功导入 {{ importResult.successCount }} 行</p>
    <template v-else-if="importResult">
      <el-alert type="error" :closable="false" :title="`发现 ${importResult.errors.length} 个错误，整批未导入`" class="mb8" />
      <el-table :data="importResult.errors" max-height="300" border>
        <el-table-column prop="row" label="行号" width="80" />
        <el-table-column prop="reason" label="原因" />
      </el-table>
    </template>
    <template #footer>
      <el-button type="primary" @click="importVisible = false">
        <template #icon><Close /></template>
        关闭
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  customerApi, supplierApi, downloadBlob,
  type PartnerVO, type PartnerSaveRequest, type ImportErrors
} from '@/api/basedata'
import { useTable } from '@/composables/useTable'
import PageHeader from '@/components/PageHeader.vue'

const props = defineProps<{ kind: 'customer' | 'supplier' }>()

const api = computed(() => (props.kind === 'customer' ? customerApi : supplierApi))
const label = computed(() => (props.kind === 'customer' ? '客户' : '供应商'))
const permPrefix = computed(() => `basedata:${props.kind}`)

const { loading, rows, total, query, load, search } = useTable<PartnerVO>((q) => api.value.page(q))

const keyword = ref('')
function doSearch() {
  query.keyword = keyword.value || undefined
  search()
}

const editVisible = ref(false)
const saving = ref(false)
const form = reactive<PartnerSaveRequest & { id?: number }>({
  code: '', name: '', contact: '', phone: '', address: '', taxNo: '', status: 1, remark: ''
})

function openEdit(row: PartnerVO | null) {
  Object.assign(form, row ? { ...row } : {
    id: undefined, code: '', name: '', contact: '', phone: '', address: '', taxNo: '', status: 1, remark: ''
  })
  editVisible.value = true
}

async function save() {
  saving.value = true
  try {
    if (form.id) {
      await api.value.update(form.id, form)
    } else {
      await api.value.create(form)
    }
    ElMessage.success('已保存')
    editVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function remove(row: PartnerVO) {
  await ElMessageBox.confirm(`删除${label.value} ${row.name}？`, '确认', { type: 'warning' })
  await api.value.remove(row.id)
  ElMessage.success('已删除')
  await load()
}

const importVisible = ref(false)
const importResult = ref<ImportErrors | null>(null)

async function doImport(options: { file: File }) {
  importResult.value = await api.value.importFile(options.file)
  importVisible.value = true
  if (importResult.value.errors.length === 0) await load()
}

async function doExport() {
  const blob = await api.value.exportXlsx({ keyword: keyword.value })
  downloadBlob(blob, `${props.kind}s.xlsx`)
}
</script>

<style scoped>
.mb8 { margin-bottom: 8px; }
</style>

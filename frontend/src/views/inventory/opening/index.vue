<template>
  <div class="erp-page">
    <PageHeader title="期初" accent="库存" subtitle="一仓一期初单；Excel 导入草稿行，审核后入库存">
      <template #extra>
        <el-button v-perm="'inventory:opening:create'" type="primary" @click="openCreate">
          <template #icon><Plus /></template>
          新建期初单
        </el-button>
      </template>
    </PageHeader>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="rows" class="erp-table">
        <el-table-column prop="docNo" label="单号" width="180" />
        <el-table-column label="仓库" width="140">
          <template #default="{ row }">{{ warehouseName(row.warehouseId) }}</template>
        </el-table-column>
        <el-table-column prop="docDate" label="单据日期" width="120" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="auditTime" label="审核时间" width="170" />
        <el-table-column prop="voidReason" label="作废原因" min-width="140" />
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button v-perm="'inventory:opening:import'" link type="primary" :disabled="row.status !== 10" @click="openImport(row)">
              <template #icon><Upload /></template>
              导入
            </el-button>
            <el-button v-perm="'inventory:opening:audit'" link type="primary" :disabled="row.status !== 10" @click="audit(row)">
              <template #icon><Check /></template>
              审核
            </el-button>
            <el-button v-perm="'inventory:opening:void'" link type="danger" :disabled="row.status !== 20" @click="voidDoc(row)">
              <template #icon><Delete /></template>
              作废
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

    <el-dialog v-model="createVisible" title="新建期初单" width="420px">
      <el-form label-width="80px">
        <el-form-item label="仓库" required>
          <el-select v-model="createWarehouseId" style="width: 100%">
            <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="明细">
          <span class="tip">建单后通过「导入」上传 Excel 明细行</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">
          <template #icon><Close /></template>
          取消
        </el-button>
        <el-button type="primary" :loading="creating" @click="create">
          <template #icon><Check /></template>
          创建
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="importVisible" :title="`导入期初明细：${importDocNo}`" width="560px">
      <el-upload
        drag
        accept=".xlsx"
        :show-file-list="false"
        :http-request="doImport"
      >
        <el-icon class="upload-ic"><Upload /></el-icon>
        <div>拖拽或点击选择 xlsx（列：SKU编码/数量/期初均价/批号/生产日期/SN列表/备注）</div>
      </el-upload>
      <template v-if="importResult">
        <el-alert
          v-if="importResult.errors.length === 0"
          type="success"
          :closable="false"
          :title="`成功导入 ${importResult.successCount} 行`"
          class="mt12"
        />
        <template v-else>
          <el-alert type="error" :closable="false" :title="`发现 ${importResult.errors.length} 个错误，整批未导入`" class="mt12" />
          <el-table :data="importResult.errors" max-height="240" class="erp-table mt8">
            <el-table-column prop="row" label="行号" width="70" />
            <el-table-column prop="reason" label="原因" />
          </el-table>
        </template>
      </template>
      <template #footer>
        <el-button @click="importVisible = false">
          <template #icon><Close /></template>
          关闭
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { openingApi, type OpeningVO } from '@/api/inventory'
import { warehouseApi, type WarehouseVO } from '@/api/basedata'
import { useTable } from '@/composables/useTable'
import PageHeader from '@/components/PageHeader.vue'

const warehouses = ref<WarehouseVO[]>([])
const warehouseName = (id: number) => warehouses.value.find((w) => w.id === id)?.name ?? String(id)

const { loading, rows, total, query, load } = useTable<OpeningVO>((q) => openingApi.page(q))

function statusText(s: number) {
  return s === 10 ? '草稿' : s === 20 ? '已审核' : s === 50 ? '已作废' : String(s)
}
function statusTag(s: number) {
  return s === 10 ? 'info' : s === 20 ? 'success' : 'danger'
}

const createVisible = ref(false)
const creating = ref(false)
const createWarehouseId = ref<number | undefined>(undefined)

function openCreate() {
  createWarehouseId.value = undefined
  createVisible.value = true
}

async function create() {
  if (!createWarehouseId.value) {
    ElMessage.warning('请选择仓库')
    return
  }
  creating.value = true
  try {
    await openingApi.create({ warehouseId: createWarehouseId.value, items: [] })
    ElMessage.success('草稿已创建，请导入明细')
    createVisible.value = false
    await load()
  } finally {
    creating.value = false
  }
}

const importVisible = ref(false)
const importDocNo = ref('')
const importResult = ref<{ successCount: number; errors: { row: number; reason: string }[] } | null>(null)
let importingId = 0

function openImport(row: OpeningVO) {
  importingId = row.id
  importDocNo.value = row.docNo
  importResult.value = null
  importVisible.value = true
}

async function doImport(opt: { file: File }) {
  importResult.value = await openingApi.importFile(importingId, opt.file)
  if (importResult.value.errors.length === 0) {
    await load()
  }
}

async function audit(row: OpeningVO) {
  await ElMessageBox.confirm(`审核期初单 ${row.docNo}？审核后立即入库存。`, '审核确认', { type: 'warning' })
  await openingApi.audit(row.id)
  ElMessage.success('已审核')
  await load()
}

async function voidDoc(row: OpeningVO) {
  const { value } = await ElMessageBox.prompt(`作废期初单 ${row.docNo}？原因必填`, '作废确认', {
    inputPlaceholder: '作废原因',
    inputValidator: (v: string) => (v && v.trim() ? true : '原因必填'),
    type: 'warning'
  })
  await openingApi.void(row.id, value.trim())
  ElMessage.success('已作废')
  await load()
}

onMounted(async () => {
  warehouses.value = await warehouseApi.list()
  await load()
})
</script>

<style scoped>
.tip {
  font-size: 12.5px;
  color: var(--erp-muted);
}
.upload-ic {
  font-size: 40px;
  color: var(--erp-blue);
  margin-bottom: 8px;
}
.mt12 {
  margin-top: 12px;
}
.mt8 {
  margin-top: 8px;
}
</style>

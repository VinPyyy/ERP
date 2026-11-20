<template>
  <div class="erp-page">
    <PageHeader title="采购" accent="订单" subtitle="审核不锁库存；行关闭/自动完成驱动头状态">
      <template #extra>
        <el-button v-perm="'purchase:order:create'" type="primary" @click="openCreate">
          <template #icon><Plus /></template>
          新增订单
        </el-button>
      </template>
    </PageHeader>

    <el-card shadow="never">
      <div class="erp-toolbar">
        <el-input v-model="keyword" placeholder="单号" clearable style="width: 180px" @keyup.enter="doSearch" />
        <el-button type="primary" @click="doSearch">
          <template #icon><Search /></template>
          查询
        </el-button>
      </div>
      <el-table v-loading="loading" :data="rows" class="erp-table">
        <el-table-column prop="docNo" label="单号" width="180" />
        <el-table-column label="供应商" min-width="140">
          <template #default="{ row }">{{ nameOf(suppliers, row.supplierId) }}</template>
        </el-table-column>
        <el-table-column label="入库仓" width="120">
          <template #default="{ row }">{{ nameOf(warehouses, row.warehouseId) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }"><el-tag :type="tag(row.status)" size="small">{{ text(row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="docDate" label="日期" width="110" />
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">
              <template #icon><View /></template>明细
            </el-button>
            <el-button v-perm="'purchase:order:audit'" link type="primary" :disabled="row.status !== 'DRAFT'" @click="audit(row)">
              <template #icon><Check /></template>审核
            </el-button>
            <el-button v-perm="'purchase:order:create-inbound'" link type="primary" :disabled="row.status !== 'AUDITED'" @click="createInbound(row)">
              <template #icon><Download /></template>生成入库
            </el-button>
            <el-button v-perm="'purchase:order:void'" link type="danger" :disabled="row.status === 'VOIDED' || row.status === 'COMPLETED'" @click="voidDoc(row)">
              <template #icon><Delete /></template>作废
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-model:current-page="query.pageNum" v-model:page-size="query.pageSize" :total="total"
        layout="total, sizes, prev, pager, next" class="erp-pager" @change="load" />
    </el-card>

    <el-dialog v-model="detailVisible" :title="`订单明细：${detail?.doc.docNo ?? ''}`" width="760px">
      <el-table :data="detail?.items ?? []" class="erp-table" size="small">
        <el-table-column prop="lineNo" label="行" width="50" />
        <el-table-column prop="skuName" label="商品" min-width="140" />
        <el-table-column prop="qty" label="数量" width="90" />
        <el-table-column prop="price" label="单价" width="90" />
        <el-table-column prop="doneQty" label="已入库" width="90" />
        <el-table-column label="行状态" width="90">
          <template #default="{ row }">{{ row.lineStatus === 10 ? '执行中' : row.lineStatus === 30 ? '已完成' : '已关闭' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button v-perm="'purchase:order:close'" link type="warning" :disabled="row.lineStatus !== 10 || detail?.doc.status !== 'AUDITED'" @click="closeLine(row)">
              关闭
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <el-dialog v-model="createVisible" title="新增采购订单" width="760px">
      <el-form label-width="80px">
        <el-form-item label="供应商" required>
          <el-select v-model="form.supplierId" filterable style="width: 240px">
            <el-option v-for="s in suppliers" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="入库仓" required>
          <el-select v-model="form.warehouseId" style="width: 240px">
            <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="明细">
          <div style="width: 100%">
            <div v-for="(line, i) in form.items" :key="i" class="line-row">
              <el-select v-model="line.skuId" filterable placeholder="商品" style="width: 220px">
                <el-option v-for="p in products" :key="p.id" :label="`${p.skuCode} ${p.name}`" :value="p.id" />
              </el-select>
              <el-input-number v-model="line.qty" :min="0.0001" :precision="4" placeholder="数量" style="width: 140px" />
              <el-input-number v-model="line.price" :min="0" :precision="6" placeholder="单价" style="width: 140px" />
              <el-button link type="danger" @click="form.items.splice(i, 1)">
                <template #icon><Delete /></template>删
              </el-button>
            </div>
            <el-button link type="primary" @click="form.items.push({ skuId: undefined, qty: 1, price: undefined })">
              <template #icon><Plus /></template>加行
            </el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false"><template #icon><Close /></template>取消</el-button>
        <el-button type="primary" :loading="saving" @click="save"><template #icon><Check /></template>保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { orderApi, type DocVO } from '@/api/purchase'
import { productApi, supplierApi, warehouseApi, type ProductVO, type WarehouseVO } from '@/api/basedata'
import type { PartnerVO } from '@/api/basedata'
import { useTable } from '@/composables/useTable'
import PageHeader from '@/components/PageHeader.vue'

const keyword = ref('')
const suppliers = ref<PartnerVO[]>([])
const warehouses = ref<WarehouseVO[]>([])
const products = ref<ProductVO[]>([])
const { loading, rows, total, query, load, search } = useTable<DocVO['doc']>((q) => orderApi.page(q) as any)

const nameOf = (list: { id: number; name: string }[], id?: number) => list.find((x) => x.id === id)?.name ?? String(id ?? '-')
const text = (s: string) => ({ DRAFT: '草稿', AUDITED: '已审核', COMPLETED: '已完成', VOIDED: '已作废' }[s] ?? s)
const tag = (s: string) => ({ DRAFT: 'info', AUDITED: 'success', COMPLETED: '', VOIDED: 'danger' }[s] ?? 'info')

const detailVisible = ref(false)
const detail = ref<DocVO | null>(null)
async function openDetail(row: { id: number }) {
  detail.value = await orderApi.get(row.id)
  detailVisible.value = true
}
async function closeLine(line: { id: number }) {
  await ElMessageBox.confirm('关闭该行剩余量？', '确认', { type: 'warning' })
  await orderApi.closeItem(detail.value!.doc.id, line.id)
  ElMessage.success('已关闭')
  await openDetail(detail.value!.doc)
}

const createVisible = ref(false)
const saving = ref(false)
const form = reactive<{ supplierId?: number; warehouseId?: number; items: { skuId?: number; qty: number; price?: number }[] }>({
  supplierId: undefined, warehouseId: undefined, items: []
})
function openCreate() {
  Object.assign(form, { supplierId: undefined, warehouseId: undefined, items: [{ skuId: undefined, qty: 1, price: undefined }] })
  createVisible.value = true
}
async function save() {
  if (!form.supplierId || !form.warehouseId) {
    ElMessage.warning('请选择供应商与入库仓')
    return
  }
  saving.value = true
  try {
    await orderApi.create(form)
    ElMessage.success('已保存')
    createVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

function doSearch() {
  query.keyword = keyword.value || undefined
  search()
}
async function audit(row: { id: number; docNo: string }) {
  await ElMessageBox.confirm(`审核订单 ${row.docNo}？`, '审核确认', { type: 'warning' })
  await orderApi.audit(row.id)
  ElMessage.success('已审核')
  await load()
}
async function createInbound(row: { id: number }) {
  await orderApi.createInbound(row.id)
  ElMessage.success('入库草稿已生成，请到采购入库页审核')
}
async function voidDoc(row: { id: number; docNo: string }) {
  const { value } = await ElMessageBox.prompt(`作废订单 ${row.docNo}？原因必填`, '作废确认', {
    inputValidator: (v: string) => (v && v.trim() ? true : '原因必填'), type: 'warning'
  })
  await orderApi.void(row.id, value.trim())
  ElMessage.success('已作废')
  await load()
}

onMounted(async () => {
  suppliers.value = (await supplierApi.page({ pageSize: 200 })).records
  warehouses.value = await warehouseApi.list()
  products.value = (await productApi.page({ pageSize: 200 })).records
  await load()
})
</script>

<style scoped>
.line-row { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; }
</style>

<template>
  <div class="erp-page">
    <PageHeader title="采购" accent="退货" subtitle="有原单价不可改、累计≤原量；无原单手录价">
      <template #extra>
        <el-button v-perm="'purchase:return:create'" type="primary" @click="openCreate">
          <template #icon><Plus /></template>
          新增退货单
        </el-button>
      </template>
    </PageHeader>

    <el-card shadow="never">
      <div class="erp-toolbar">
        <el-input v-model="keyword" placeholder="单号" clearable style="width: 180px" @keyup.enter="doSearch" />
        <el-button type="primary" @click="doSearch"><template #icon><Search /></template>查询</el-button>
      </div>
      <el-table v-loading="loading" :data="rows" class="erp-table">
        <el-table-column prop="docNo" label="单号" width="180" />
        <el-table-column label="供应商" min-width="130">
          <template #default="{ row }">{{ nameOf(suppliers, row.supplierId) }}</template>
        </el-table-column>
        <el-table-column label="仓库" width="110">
          <template #default="{ row }">{{ nameOf(warehouses, row.warehouseId) }}</template>
        </el-table-column>
        <el-table-column label="原入库单" width="170">
          <template #default="{ row }">{{ inboundNo(row.sourceInboundId) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }"><el-tag :type="tag(row.status)" size="small">{{ text(row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)"><template #icon><View /></template>明细</el-button>
            <el-button v-perm="'purchase:return:audit'" link type="primary" :disabled="row.status !== 'DRAFT'" @click="audit(row)">
              <template #icon><Check /></template>审核
            </el-button>
            <el-button v-perm="'purchase:return:void'" link type="danger" :disabled="row.status !== 'AUDITED' && row.status !== 'DRAFT'" @click="voidDoc(row)">
              <template #icon><Delete /></template>作废
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-model:current-page="query.pageNum" v-model:page-size="query.pageSize" :total="total"
        layout="total, sizes, prev, pager, next" class="erp-pager" @change="load" />
    </el-card>

    <el-dialog v-model="detailVisible" :title="`退货明细：${detail?.doc.docNo ?? ''}`" width="700px">
      <el-table :data="detail?.items ?? []" class="erp-table" size="small">
        <el-table-column prop="lineNo" label="行" width="50" />
        <el-table-column prop="skuName" label="商品" min-width="130" />
        <el-table-column prop="qty" label="数量" width="80" />
        <el-table-column prop="price" label="单价" width="90" />
        <el-table-column prop="lotNo" label="批号" width="100" />
        <el-table-column label="SN" min-width="130">
          <template #default="{ row }">{{ (row.snList || []).join(', ') }}</template>
        </el-table-column>
        <el-table-column prop="reason" label="原因" min-width="140" />
      </el-table>
    </el-dialog>

    <el-dialog v-model="createVisible" title="新增采购退货单" width="760px">
      <el-form label-width="80px">
        <el-form-item label="原入库单">
          <el-select v-model="form.sourceInboundId" clearable filterable placeholder="可空=无原单退货" style="width: 260px" @change="onSourceChange">
            <el-option v-for="o in auditedInbounds" :key="o.id" :label="o.docNo" :value="o.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="供应商" required>
          <el-select v-model="form.supplierId" filterable style="width: 240px">
            <el-option v-for="s in suppliers" :key="s.id" :label="s.name" :value="s.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="仓库" required>
          <el-select v-model="form.warehouseId" style="width: 240px">
            <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="明细">
          <div style="width: 100%">
            <div v-for="(line, i) in form.items" :key="i" class="line-row">
              <el-select v-model="line.skuId" filterable placeholder="商品" style="width: 170px">
                <el-option v-for="p in products" :key="p.id" :label="`${p.skuCode} ${p.name}`" :value="p.id" />
              </el-select>
              <el-input-number v-model="line.qty" :min="0.0001" :precision="4" style="width: 110px" />
              <el-input-number v-model="line.price" :min="0" :precision="6" :disabled="!!form.sourceInboundId" placeholder="单价" style="width: 110px" />
              <el-input v-model="line.lotNo" placeholder="批号" style="width: 100px" :disabled="!!form.sourceInboundId" />
              <el-input v-model="line.snText" placeholder="SN 逗号分隔" style="width: 140px" />
              <el-input v-model="line.reason" placeholder="退货原因（必填）" style="width: 150px" />
              <el-button link type="danger" @click="form.items.splice(i, 1)"><template #icon><Delete /></template>删</el-button>
            </div>
            <el-button link type="primary" @click="addLine()"><template #icon><Plus /></template>加行</el-button>
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
import { inboundApi, returnApi, type DocHead, type DocOption, type DocVO } from '@/api/purchase'
import { productApi, supplierApi, warehouseApi, type ProductOption, type WarehouseOption, type PartnerOption } from '@/api/basedata'
import { useTable } from '@/composables/useTable'
import PageHeader from '@/components/PageHeader.vue'

const keyword = ref('')
const suppliers = ref<PartnerOption[]>([])
const warehouses = ref<WarehouseOption[]>([])
const products = ref<ProductOption[]>([])
const allInbounds = ref<DocOption[]>([])
// 审查 M6：仅已审核入库单可作为退货源
const auditedInbounds = allInbounds
const { loading, rows, total, query, load, search } = useTable<DocHead>((q) => returnApi.page(q) as any)

const nameOf = (list: { id: number; name: string }[], id?: number) => list.find((x) => x.id === id)?.name ?? String(id ?? '-')
const inboundNo = (id?: number | null) => allInbounds.value.find((o) => o.id === id)?.docNo ?? (id ? String(id) : '—')
const text = (s: string) => ({ DRAFT: '草稿', AUDITED: '已审核', COMPLETED: '已完成', VOIDED: '已作废' }[s] ?? s)
const tag = (s: string) => ({ DRAFT: 'info', AUDITED: 'success', COMPLETED: '', VOIDED: 'danger' }[s] ?? 'info')

const detailVisible = ref(false)
const detail = ref<DocVO | null>(null)
async function openDetail(row: { id: number }) {
  detail.value = await returnApi.get(row.id)
  detailVisible.value = true
}

interface Line { skuId?: number; qty: number; price?: number; lotNo?: string; snText?: string; reason: string; sourceInboundItemId?: number }
const createVisible = ref(false)
const saving = ref(false)
const form = reactive<{ supplierId?: number; warehouseId?: number; sourceInboundId?: number; items: Line[] }>({
  supplierId: undefined, warehouseId: undefined, sourceInboundId: undefined, items: []
})
function addLine(preset?: Partial<Line>) {
  form.items.push({ skuId: undefined, qty: 1, price: undefined, lotNo: '', snText: '', reason: '', ...preset })
}
function openCreate() {
  Object.assign(form, { supplierId: undefined, warehouseId: undefined, sourceInboundId: undefined, items: [] })
  addLine()
  createVisible.value = true
}
async function onSourceChange(inboundId?: number) {
  form.items = []
  if (!inboundId) {
    addLine()
    return
  }
  const vo = auditedInbounds.value.find((o) => o.id === inboundId)
  if (vo) {
    form.supplierId = vo.supplierId
    form.warehouseId = vo.warehouseId
  }
  const d = await inboundApi.get(inboundId)
  for (const line of d.items) {
    addLine({ skuId: line.skuId, qty: line.qty, price: line.price ?? undefined, lotNo: line.lotNo ?? '', sourceInboundItemId: line.id })
  }
  if (form.items.length === 0) addLine()
}
async function save() {
  if (!form.supplierId || !form.warehouseId) {
    ElMessage.warning('请选择供应商与仓库')
    return
  }
  if (form.items.some((l) => !l.reason.trim())) {
    ElMessage.warning('每行退货原因必填')
    return
  }
  saving.value = true
  try {
    await returnApi.create({
      supplierId: form.supplierId,
      warehouseId: form.warehouseId,
      sourceInboundId: form.sourceInboundId ?? null,
      items: form.items.map((l) => ({
        sourceInboundItemId: l.sourceInboundItemId ?? null,
        skuId: l.skuId, qty: l.qty, price: l.price ?? null,
        lotNo: l.lotNo || null,
        snList: l.snText ? l.snText.split(/[,，]/).map((s) => s.trim()).filter(Boolean) : null,
        reason: l.reason.trim()
      }))
    })
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
  await ElMessageBox.confirm(`审核退货 ${row.docNo}？审核即出库存（按当前均价结转）。`, '审核确认', { type: 'warning' })
  await returnApi.audit(row.id)
  ElMessage.success('已审核')
  await load()
}
async function voidDoc(row: { id: number; docNo: string }) {
  const { value } = await ElMessageBox.prompt(`作废退货单 ${row.docNo}？原因必填`, '作废确认', {
    inputValidator: (v: string) => (v && v.trim() ? true : '原因必填'), type: 'warning'
  })
  await returnApi.void(row.id, value.trim())
  ElMessage.success('已作废')
  await load()
}

onMounted(async () => {
  suppliers.value = await supplierApi.options()
  warehouses.value = await warehouseApi.options()
  products.value = await productApi.options()
  allInbounds.value = await inboundApi.options()
  await load()
})
</script>

<style scoped>
.line-row { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 8px; align-items: center; }
</style>

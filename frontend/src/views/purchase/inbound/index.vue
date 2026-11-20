<template>
  <div class="erp-page">
    <PageHeader title="采购" accent="入库" subtitle="审核动库存与均价；带源单仓库必须与订单一致">
      <template #extra>
        <el-button v-perm="'purchase:inbound:create'" type="primary" @click="openCreate">
          <template #icon><Plus /></template>
          新增入库单
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
        <el-table-column label="源订单" width="170">
          <template #default="{ row }">{{ orderNo(row.sourceOrderId) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }"><el-tag :type="tag(row.status)" size="small">{{ text(row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column label="操作" width="290" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)"><template #icon><View /></template>明细</el-button>
            <el-button v-perm="'purchase:inbound:audit'" link type="primary" :disabled="row.status !== 'DRAFT'" @click="audit(row)">
              <template #icon><Check /></template>审核
            </el-button>
            <el-button v-perm="'purchase:inbound:create-return'" link type="primary" :disabled="row.status !== 'AUDITED'" @click="startReturn(row)">
              <template #icon><RefreshLeft /></template>退货
            </el-button>
            <el-button v-perm="'purchase:inbound:void'" link type="danger" :disabled="row.status !== 'AUDITED' && row.status !== 'DRAFT'" @click="voidDoc(row)">
              <template #icon><Delete /></template>作废
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-model:current-page="query.pageNum" v-model:page-size="query.pageSize" :total="total"
        layout="total, sizes, prev, pager, next" class="erp-pager" @change="load" />
    </el-card>

    <el-dialog v-model="detailVisible" :title="`入库明细：${detail?.doc.docNo ?? ''}`" width="760px">
      <el-table :data="detail?.items ?? []" class="erp-table" size="small">
        <el-table-column prop="lineNo" label="行" width="50" />
        <el-table-column prop="skuName" label="商品" min-width="130" />
        <el-table-column prop="qty" label="实收" width="80" />
        <el-table-column prop="price" label="结算价" width="90" />
        <el-table-column prop="lotNo" label="批号" width="100" />
        <el-table-column prop="productionDate" label="生产日期" width="110" />
        <el-table-column label="SN" min-width="140">
          <template #default="{ row }">{{ (row.snList || []).join(', ') }}</template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <el-dialog v-model="createVisible" title="新增采购入库单" width="820px">
      <el-form label-width="80px">
        <el-form-item label="源订单">
          <el-select v-model="form.sourceOrderId" clearable filterable placeholder="可空=直接入库" style="width: 260px" @change="onSourceChange">
            <el-option v-for="o in auditedOrders" :key="o.id" :label="o.docNo" :value="o.id" />
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
              <el-select v-model="line.skuId" filterable placeholder="商品" style="width: 180px">
                <el-option v-for="p in products" :key="p.id" :label="`${p.skuCode} ${p.name}`" :value="p.id" />
              </el-select>
              <el-input-number v-model="line.qty" :min="0.0001" :precision="4" style="width: 120px" />
              <el-input-number v-model="line.price" :min="0" :precision="6" placeholder="结算价" style="width: 120px" />
              <el-input v-model="line.lotNo" placeholder="批号" style="width: 100px" />
              <el-date-picker v-model="line.productionDate" type="date" value-format="YYYY-MM-DD" placeholder="生产日期" style="width: 135px" />
              <el-input v-model="line.snText" placeholder="SN 逗号分隔" style="width: 150px" />
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
import { inboundApi, orderApi, type DocHead, type DocOption, type DocVO } from '@/api/purchase'
import { productApi, supplierApi, warehouseApi, type ProductOption, type WarehouseOption, type PartnerOption } from '@/api/basedata'
import { useTable } from '@/composables/useTable'
import PageHeader from '@/components/PageHeader.vue'

const keyword = ref('')
const suppliers = ref<PartnerOption[]>([])
const warehouses = ref<WarehouseOption[]>([])
const products = ref<ProductOption[]>([])
const allOrders = ref<DocOption[]>([])
// 审查 M6：仅已审核订单可作为入库源（草稿/完成/作废不可选）
const auditedOrders = allOrders
const { loading, rows, total, query, load, search } = useTable<DocHead>((q) => inboundApi.page(q) as any)

const nameOf = (list: { id: number; name: string }[], id?: number) => list.find((x) => x.id === id)?.name ?? String(id ?? '-')
const orderNo = (id?: number | null) => allOrders.value.find((o) => o.id === id)?.docNo ?? (id ? String(id) : '—')
const text = (s: string) => ({ DRAFT: '草稿', AUDITED: '已审核', COMPLETED: '已完成', VOIDED: '已作废' }[s] ?? s)
const tag = (s: string) => ({ DRAFT: 'info', AUDITED: 'success', COMPLETED: '', VOIDED: 'danger' }[s] ?? 'info')

const detailVisible = ref(false)
const detail = ref<DocVO | null>(null)
async function openDetail(row: { id: number }) {
  detail.value = await inboundApi.get(row.id)
  detailVisible.value = true
}

interface Line { skuId?: number; qty: number; price?: number; lotNo?: string; productionDate?: string; snText?: string; sourceOrderItemId?: number }
const createVisible = ref(false)
const saving = ref(false)
const form = reactive<{ supplierId?: number; warehouseId?: number; sourceOrderId?: number; items: Line[] }>({
  supplierId: undefined, warehouseId: undefined, sourceOrderId: undefined, items: []
})
function addLine(preset?: Partial<Line>) {
  form.items.push({ skuId: undefined, qty: 1, price: undefined, lotNo: '', snText: '', ...preset })
}
function openCreate() {
  Object.assign(form, { supplierId: undefined, warehouseId: undefined, sourceOrderId: undefined, items: [] })
  addLine()
  createVisible.value = true
}
async function onSourceChange(orderId?: number) {
  form.items = []
  if (!orderId) {
    addLine()
    return
  }
  const order = auditedOrders.value.find((o) => o.id === orderId)
  if (order) {
    form.supplierId = order.supplierId
    form.warehouseId = order.warehouseId
  }
  const detailVo = await orderApi.get(orderId)
  for (const line of detailVo.items.filter((i) => i.lineStatus === 10 && (i.qty - (i.doneQty || 0)) > 0)) {
    addLine({ skuId: line.skuId, qty: line.qty - (line.doneQty || 0), price: line.price ?? undefined, sourceOrderItemId: line.id })
  }
  if (form.items.length === 0) addLine()
}
async function save() {
  if (!form.supplierId || !form.warehouseId) {
    ElMessage.warning('请选择供应商与仓库')
    return
  }
  saving.value = true
  try {
    await inboundApi.create({
      supplierId: form.supplierId,
      warehouseId: form.warehouseId,
      sourceOrderId: form.sourceOrderId ?? null,
      items: form.items.map((l) => ({
        sourceOrderItemId: l.sourceOrderItemId ?? null,
        skuId: l.skuId, qty: l.qty, price: l.price,
        lotNo: l.lotNo || null,
        productionDate: l.productionDate || null,
        snList: l.snText ? l.snText.split(/[,，]/).map((s) => s.trim()).filter(Boolean) : null
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
  await ElMessageBox.confirm(`审核入库 ${row.docNo}？审核即入库存并重算均价。`, '审核确认', { type: 'warning' })
  await inboundApi.audit(row.id)
  ElMessage.success('已审核')
  await load()
}
async function voidDoc(row: { id: number; docNo: string }) {
  const { value } = await ElMessageBox.prompt(`作废入库单 ${row.docNo}？原因必填`, '作废确认', {
    inputValidator: (v: string) => (v && v.trim() ? true : '原因必填'), type: 'warning'
  })
  await inboundApi.void(row.id, value.trim())
  ElMessage.success('已作废')
  await load()
}

onMounted(async () => {
  suppliers.value = await supplierApi.options()
  warehouses.value = await warehouseApi.options()
  products.value = await productApi.options()
  allOrders.value = await orderApi.options()
  await load()
})
async function startReturn(row: { id: number; docNo: string }) {
  await ElMessageBox.confirm(`按入库单 ${row.docNo} 的可退余量生成退货草稿？`, '发起退货', { type: 'warning' })
  const draftId = await inboundApi.createReturn(row.id)
  ElMessage.success(`退货草稿已生成（#${draftId}），请到"采购退货"页补原因后审核`)
  await load()
}
</script>

<style scoped>
.line-row { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 8px; align-items: center; }
</style>

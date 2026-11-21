<template>
  <div class="erp-page">
    <PageHeader title="销售" accent="订单" subtitle="审核即锁定库存；行关闭释放剩余锁定；超发严禁">
      <template #extra>
        <el-button v-perm="'sale:order:create'" type="primary" @click="openCreate">
          <template #icon><Plus /></template>
          新增销售订单
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
        <el-table-column label="客户" min-width="130">
          <template #default="{ row }">{{ nameOf(customers, row.customerId) }}</template>
        </el-table-column>
        <el-table-column label="仓库" width="110">
          <template #default="{ row }">{{ nameOf(warehouses, row.warehouseId) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }"><el-tag :type="tag(row.status)" size="small">{{ text(row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="docDate" label="日期" width="110" />
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)"><template #icon><View /></template>明细</el-button>
            <el-button v-perm="'sale:order:audit'" link type="primary" :disabled="row.status !== 'DRAFT'" @click="audit(row)">
              <template #icon><Check /></template>审核
            </el-button>
            <el-button v-perm="'sale:order:create-outbound'" link type="primary" :disabled="row.status !== 'AUDITED'" @click="createOutbound(row)">
              <template #icon><Upload /></template>生出库单
            </el-button>
            <el-button v-perm="'sale:order:void'" link type="danger" :disabled="row.status !== 'AUDITED' && row.status !== 'DRAFT'" @click="voidDoc(row)">
              <template #icon><Delete /></template>作废
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-model:current-page="query.pageNum" v-model:page-size="query.pageSize" :total="total"
        layout="total, sizes, prev, pager, next" class="erp-pager" @change="load" />
    </el-card>

    <el-dialog v-model="detailVisible" :title="`订单明细：${detail?.doc.docNo ?? ''}`" width="780px">
      <el-table :data="detail?.items ?? []" class="erp-table" size="small">
        <el-table-column prop="lineNo" label="行" width="50" />
        <el-table-column prop="skuName" label="商品" min-width="130" />
        <el-table-column prop="qty" label="订购量" width="90" />
        <el-table-column prop="doneQty" label="已出库" width="90" />
        <el-table-column prop="price" label="单价" width="90" />
        <el-table-column label="行状态" width="90">
          <template #default="{ row }">{{ lineText(row.lineStatus) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <el-button v-perm="'sale:order:close'" link type="danger"
              :disabled="detail?.doc.status !== 'AUDITED' || row.lineStatus !== 10" @click="closeItem(row)">
              <template #icon><CircleClose /></template>关闭行
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <el-dialog v-model="createVisible" title="新增销售订单" width="780px">
      <el-form label-width="80px">
        <el-form-item label="客户" required>
          <el-select v-model="form.customerId" filterable style="width: 240px">
            <el-option v-for="c in customers" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="仓库" required>
          <el-select v-model="form.warehouseId" style="width: 240px" @change="refreshAvailable">
            <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="明细">
          <div style="width: 100%">
            <div v-for="(line, i) in form.items" :key="i" class="line-row">
              <el-select v-model="line.skuId" filterable placeholder="商品" style="width: 190px" @change="refreshAvailable">
                <el-option v-for="p in products" :key="p.id" :label="`${p.skuCode} ${p.name}`" :value="p.id" />
              </el-select>
              <el-input-number v-model="line.qty" :min="0.0001" :precision="4" style="width: 120px" />
              <el-input-number v-model="line.price" :min="0" :precision="6" placeholder="单价" style="width: 120px" />
              <span v-if="line.skuId && availableOf(line.skuId) !== undefined" class="avail">可用 {{ availableOf(line.skuId) }}</span>
              <el-button link type="danger" @click="form.items.splice(i, 1)"><template #icon><Delete /></template>删</el-button>
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
import { saleOrderApi, saleApi, type SaleDocHead, type SaleDocVO } from '@/api/sale'
import { productApi, customerApi, warehouseApi, type ProductOption, type WarehouseOption, type PartnerOption } from '@/api/basedata'
import { useTable } from '@/composables/useTable'
import PageHeader from '@/components/PageHeader.vue'

const keyword = ref('')
const customers = ref<PartnerOption[]>([])
const warehouses = ref<WarehouseOption[]>([])
const products = ref<ProductOption[]>([])
const available = ref<Record<string, number>>({})
const { loading, rows, total, query, load, search } = useTable<SaleDocHead>((q) => saleOrderApi.page(q) as any)

const nameOf = (list: { id: number; name: string }[], id?: number) => list.find((x) => x.id === id)?.name ?? String(id ?? '-')
const text = (s: string) => ({ DRAFT: '草稿', AUDITED: '已审核', COMPLETED: '已完成', VOIDED: '已作废' }[s] ?? s)
const tag = (s: string) => ({ DRAFT: 'info', AUDITED: 'success', COMPLETED: '', VOIDED: 'danger' }[s] ?? 'info')
const lineText = (n?: number) => ({ 10: '执行中', 30: '已完成', 40: '已关闭' }[n ?? 0] ?? n)
const availableOf = (skuId: number) => available.value[String(skuId)]

async function refreshAvailable() {
  if (!form.warehouseId) {
    available.value = {}
    return
  }
  const skus = form.items.map((l) => l.skuId).filter((x): x is number => !!x)
  available.value = skus.length ? await saleApi.availability(form.warehouseId, skus) : {}
}

const detailVisible = ref(false)
const detail = ref<SaleDocVO | null>(null)
async function openDetail(row: { id: number }) {
  detail.value = await saleOrderApi.get(row.id)
  detailVisible.value = true
}
async function closeItem(row: { id: number }) {
  await ElMessageBox.confirm(`关闭订单行？剩余锁定将释放。`, '关闭确认', { type: 'warning' })
  await saleOrderApi.closeItem(detail.value!.doc.id, row.id)
  ElMessage.success('已关闭')
  detail.value = await saleOrderApi.get(detail.value!.doc.id)
  await load()
}

async function createOutbound(row: { id: number; docNo: string }) {
  await ElMessageBox.confirm(`按 ${row.docNo} 未履约行生成出库单草稿？`, '生成出库单', { type: 'warning' })
  await saleOrderApi.createOutbound(row.id)
  ElMessage.success('出库单草稿已生成')
}

const createVisible = ref(false)
const saving = ref(false)
const form = reactive<{ customerId?: number; warehouseId?: number; items: { skuId?: number; qty: number; price?: number }[] }>({
  customerId: undefined, warehouseId: undefined, items: []
})
function openCreate() {
  Object.assign(form, { customerId: undefined, warehouseId: undefined, items: [{ skuId: undefined, qty: 1, price: undefined }] })
  available.value = {}
  createVisible.value = true
}
async function save() {
  if (!form.customerId || !form.warehouseId) {
    ElMessage.warning('请选择客户与仓库')
    return
  }
  saving.value = true
  try {
    await saleOrderApi.create({
      customerId: form.customerId, warehouseId: form.warehouseId,
      items: form.items.map((l) => ({ skuId: l.skuId, qty: l.qty, price: l.price }))
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
  await ElMessageBox.confirm(`审核订单 ${row.docNo}？审核即按仓库×SKU 锁定库存。`, '审核确认', { type: 'warning' })
  await saleOrderApi.audit(row.id)
  ElMessage.success('已审核并锁定')
  await load()
}
async function voidDoc(row: { id: number; docNo: string }) {
  const { value } = await ElMessageBox.prompt(`作废销售订单 ${row.docNo}？原因必填`, '作废确认', {
    inputValidator: (v: string) => (v && v.trim() ? true : '原因必填'), type: 'warning'
  })
  await saleOrderApi.void(row.id, value.trim())
  ElMessage.success('已作废')
  await load()
}

onMounted(async () => {
  customers.value = await customerApi.options()
  warehouses.value = await warehouseApi.options()
  products.value = await productApi.options()
  await load()
})
</script>

<style scoped>
.line-row { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 8px; align-items: center; }
.avail { font-size: 12.5px; color: var(--erp-muted); }
</style>

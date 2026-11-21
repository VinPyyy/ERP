<template>
  <div class="erp-page">
    <PageHeader title="销售" accent="报价" subtitle="对客户的正式报价；整单转销售订单草稿">
      <template #extra>
        <el-button v-perm="'sale:quote:create'" type="primary" @click="openCreate">
          <template #icon><Plus /></template>
          新增报价单
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
        <el-table-column label="客户" min-width="140">
          <template #default="{ row }">{{ nameOf(customers, row.customerId) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }"><el-tag :type="tag(row.status)" size="small">{{ text(row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="docDate" label="日期" width="110" />
        <el-table-column prop="validUntil" label="有效期至" width="120" />
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)"><template #icon><View /></template>明细</el-button>
            <el-button v-perm="'sale:quote:audit'" link type="primary" :disabled="row.status !== 'DRAFT'" @click="audit(row)">
              <template #icon><Check /></template>审核
            </el-button>
            <el-button v-perm="'sale:quote:convert'" link type="primary" :disabled="row.status === 'COMPLETED' || row.status === 'VOIDED'" @click="openConvert(row)">
              <template #icon><Switch /></template>转订单
            </el-button>
            <el-button v-perm="'sale:quote:void'" link type="danger" :disabled="row.status === 'VOIDED' || row.status === 'COMPLETED'" @click="voidDoc(row)">
              <template #icon><Delete /></template>作废
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-model:current-page="query.pageNum" v-model:page-size="query.pageSize" :total="total"
        layout="total, sizes, prev, pager, next" class="erp-pager" @change="load" />
    </el-card>

    <el-dialog v-model="detailVisible" :title="`报价明细：${detail?.doc.docNo ?? ''}`" width="720px">
      <el-table :data="detail?.items ?? []" class="erp-table" size="small">
        <el-table-column prop="lineNo" label="行" width="50" />
        <el-table-column prop="skuName" label="商品" min-width="140" />
        <el-table-column prop="qty" label="数量" width="90" />
        <el-table-column prop="price" label="单价" width="100" />
        <el-table-column prop="amount" label="金额" width="110" />
      </el-table>
    </el-dialog>

    <el-dialog v-model="convertVisible" title="转销售订单" width="420px">
      <el-form label-width="90px">
        <el-form-item label="订单仓库" required>
          <el-select v-model="convertWarehouseId" style="width: 100%">
            <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
        </el-form-item>
        <p class="tip">整单行将生成一张销售订单草稿；报价单转为"已转单"。</p>
      </el-form>
      <template #footer>
        <el-button @click="convertVisible = false"><template #icon><Close /></template>取消</el-button>
        <el-button type="primary" :loading="converting" @click="doConvert"><template #icon><Check /></template>转换</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="createVisible" title="新增销售报价单" width="720px">
      <el-form label-width="80px">
        <el-form-item label="客户" required>
          <el-select v-model="form.customerId" filterable style="width: 240px">
            <el-option v-for="c in customers" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="有效期至">
          <el-date-picker v-model="form.validUntil" type="date" value-format="YYYY-MM-DD" />
        </el-form-item>
        <el-form-item label="明细">
          <div style="width: 100%">
            <div v-for="(line, i) in form.items" :key="i" class="line-row">
              <el-select v-model="line.skuId" filterable placeholder="商品" style="width: 200px">
                <el-option v-for="p in products" :key="p.id" :label="`${p.skuCode} ${p.name}`" :value="p.id" />
              </el-select>
              <el-input-number v-model="line.qty" :min="0.0001" :precision="4" style="width: 120px" />
              <el-input-number v-model="line.price" :min="0" :precision="6" placeholder="报价" style="width: 130px" />
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
import { quoteApi, type SaleDocHead, type SaleDocVO } from '@/api/sale'
import { productApi, customerApi, warehouseApi, type ProductOption, type WarehouseOption, type PartnerOption } from '@/api/basedata'
import { useTable } from '@/composables/useTable'
import PageHeader from '@/components/PageHeader.vue'

const keyword = ref('')
const customers = ref<PartnerOption[]>([])
const warehouses = ref<WarehouseOption[]>([])
const products = ref<ProductOption[]>([])
const { loading, rows, total, query, load, search } = useTable<SaleDocHead>((q) => quoteApi.page(q) as any)

const nameOf = (list: { id: number; name: string }[], id?: number) => list.find((x) => x.id === id)?.name ?? String(id ?? '-')
const text = (s: string) => ({ DRAFT: '草稿', AUDITED: '已审核', COMPLETED: '已转单', VOIDED: '已作废' }[s] ?? s)
const tag = (s: string) => ({ DRAFT: 'info', AUDITED: 'success', COMPLETED: '', VOIDED: 'danger' }[s] ?? 'info')

const detailVisible = ref(false)
const detail = ref<SaleDocVO | null>(null)
async function openDetail(row: { id: number }) {
  detail.value = await quoteApi.get(row.id)
  detailVisible.value = true
}

const convertVisible = ref(false)
const converting = ref(false)
const convertWarehouseId = ref<number | undefined>(undefined)
let convertingId = 0
function openConvert(row: { id: number }) {
  convertingId = row.id
  convertWarehouseId.value = warehouses.value[0]?.id
  convertVisible.value = true
}
async function doConvert() {
  if (!convertWarehouseId.value) {
    ElMessage.warning('请选择订单仓库')
    return
  }
  converting.value = true
  try {
    await quoteApi.convert(convertingId, convertWarehouseId.value)
    ElMessage.success('销售订单草稿已生成')
    convertVisible.value = false
    await load()
  } finally {
    converting.value = false
  }
}

const createVisible = ref(false)
const saving = ref(false)
const form = reactive<{ customerId?: number; validUntil?: string; items: { skuId?: number; qty: number; price?: number }[] }>({
  customerId: undefined, validUntil: undefined, items: []
})
function openCreate() {
  Object.assign(form, { customerId: undefined, validUntil: undefined, items: [{ skuId: undefined, qty: 1, price: undefined }] })
  createVisible.value = true
}
async function save() {
  if (!form.customerId) {
    ElMessage.warning('请选择客户')
    return
  }
  saving.value = true
  try {
    await quoteApi.create({
      customerId: form.customerId, validUntil: form.validUntil || null,
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
  await ElMessageBox.confirm(`审核报价单 ${row.docNo}？`, '审核确认', { type: 'warning' })
  await quoteApi.audit(row.id)
  ElMessage.success('已审核')
  await load()
}
async function voidDoc(row: { id: number; docNo: string }) {
  const { value } = await ElMessageBox.prompt(`作废报价单 ${row.docNo}？原因必填`, '作废确认', {
    inputValidator: (v: string) => (v && v.trim() ? true : '原因必填'), type: 'warning'
  })
  await quoteApi.void(row.id, value.trim())
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
.tip { font-size: 12.5px; color: var(--erp-muted); margin: 4px 0 0; }
</style>

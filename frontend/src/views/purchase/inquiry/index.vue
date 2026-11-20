<template>
  <div class="erp-page">
    <PageHeader title="询价" accent="单" subtitle="多供应商比价；选中行按供应商分组转采购订单">
      <template #extra>
        <el-button v-perm="'purchase:inquiry:create'" type="primary" @click="openCreate">
          <template #icon><Plus /></template>
          新增询价单
        </el-button>
      </template>
    </PageHeader>

    <el-card shadow="never">
      <div class="erp-toolbar">
        <el-input v-model="keyword" placeholder="单号/标题" clearable style="width: 180px" @keyup.enter="doSearch" />
        <el-button type="primary" @click="doSearch"><template #icon><Search /></template>查询</el-button>
      </div>
      <el-table v-loading="loading" :data="rows" class="erp-table">
        <el-table-column prop="docNo" label="单号" width="180" />
        <el-table-column prop="title" label="标题" min-width="160" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }"><el-tag :type="tag(row.status)" size="small">{{ text(row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="docDate" label="日期" width="110" />
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)"><template #icon><View /></template>比价</el-button>
            <el-button v-perm="'purchase:inquiry:audit'" link type="primary" :disabled="row.status !== 'DRAFT'" @click="audit(row)">
              <template #icon><Check /></template>审核
            </el-button>
            <el-button v-perm="'purchase:inquiry:convert'" link type="primary" :disabled="row.status === 'COMPLETED' || row.status === 'VOIDED'" @click="openConvert(row)">
              <template #icon><Switch /></template>转订单
            </el-button>
            <el-button v-perm="'purchase:inquiry:void'" link type="danger" :disabled="row.status === 'VOIDED' || row.status === 'COMPLETED'" @click="voidDoc(row)">
              <template #icon><Delete /></template>作废
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination v-model:current-page="query.pageNum" v-model:page-size="query.pageSize" :total="total"
        layout="total, sizes, prev, pager, next" class="erp-pager" @change="load" />
    </el-card>

    <el-dialog v-model="detailVisible" :title="`比价：${detail?.doc.docNo ?? ''}`" width="860px">
      <el-table :data="detail?.items ?? []" class="erp-table" size="small">
        <el-table-column prop="lineNo" label="行" width="50" />
        <el-table-column prop="skuName" label="商品" min-width="130" />
        <el-table-column label="供应商" min-width="120">
          <template #default="{ row }">{{ nameOf(suppliers, row.supplierId) }}</template>
        </el-table-column>
        <el-table-column prop="qty" label="询价量" width="90" />
        <el-table-column prop="price" label="报价" width="90" />
        <el-table-column prop="leadTimeDays" label="交期(天)" width="80" />
        <el-table-column label="选中" width="70">
          <template #default="{ row }">
            <el-checkbox :model-value="row.selected === 1" :disabled="!canEdit" @change="(v: boolean) => toggle(row, v)" />
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <el-dialog v-model="convertVisible" title="转采购订单" width="420px">
      <el-form label-width="90px">
        <el-form-item label="订单入库仓" required>
          <el-select v-model="convertWarehouseId" style="width: 100%">
            <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
          </el-select>
        </el-form-item>
        <p class="tip">选中行将按供应商分组，每组生成一张订单草稿。</p>
      </el-form>
      <template #footer>
        <el-button @click="convertVisible = false"><template #icon><Close /></template>取消</el-button>
        <el-button type="primary" :loading="converting" @click="doConvert"><template #icon><Check /></template>转换</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="createVisible" title="新增询价单" width="820px">
      <el-form label-width="60px">
        <el-form-item label="标题"><el-input v-model="form.title" style="width: 320px" /></el-form-item>
        <el-form-item label="明细">
          <div style="width: 100%">
            <div v-for="(line, i) in form.items" :key="i" class="line-row">
              <el-select v-model="line.skuId" filterable placeholder="商品" style="width: 180px">
                <el-option v-for="p in products" :key="p.id" :label="`${p.skuCode} ${p.name}`" :value="p.id" />
              </el-select>
              <el-select v-model="line.supplierId" filterable placeholder="供应商" style="width: 150px">
                <el-option v-for="s in suppliers" :key="s.id" :label="s.name" :value="s.id" />
              </el-select>
              <el-input-number v-model="line.qty" :min="0.0001" :precision="4" style="width: 110px" />
              <el-input-number v-model="line.price" :min="0" :precision="6" placeholder="报价" style="width: 110px" />
              <el-input-number v-model="line.leadTimeDays" :min="0" :precision="0" placeholder="交期天" style="width: 100px" />
              <el-button link type="danger" @click="form.items.splice(i, 1)"><template #icon><Delete /></template>删</el-button>
            </div>
            <el-button link type="primary" @click="form.items.push({ skuId: undefined, supplierId: undefined, qty: 1 })">
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
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { inquiryApi, type DocHead, type DocVO } from '@/api/purchase'
import { productApi, supplierApi, warehouseApi, type ProductVO, type WarehouseVO, type PartnerVO } from '@/api/basedata'
import { useTable } from '@/composables/useTable'
import PageHeader from '@/components/PageHeader.vue'

const keyword = ref('')
const suppliers = ref<PartnerVO[]>([])
const warehouses = ref<WarehouseVO[]>([])
const products = ref<ProductVO[]>([])
const { loading, rows, total, query, load, search } = useTable<DocHead>((q) => inquiryApi.page(q) as any)

const nameOf = (list: { id: number; name: string }[], id?: number) => list.find((x) => x.id === id)?.name ?? String(id ?? '-')
const text = (s: string) => ({ DRAFT: '草稿', AUDITED: '已审核', COMPLETED: '已转单', VOIDED: '已作废' }[s] ?? s)
const tag = (s: string) => ({ DRAFT: 'info', AUDITED: 'success', COMPLETED: '', VOIDED: 'danger' }[s] ?? 'info')

const detailVisible = ref(false)
const detail = ref<DocVO | null>(null)
const canEdit = computed(() => detail.value?.doc.status === 'DRAFT' || detail.value?.doc.status === 'AUDITED')
async function openDetail(row: { id: number }) {
  detail.value = await inquiryApi.get(row.id)
  detailVisible.value = true
}
async function toggle(line: { id: number; selected?: number }, selected: boolean) {
  await inquiryApi.select(detail.value!.doc.id, line.id, selected)
  line.selected = selected ? 1 : 0
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
    ElMessage.warning('请选择订单入库仓')
    return
  }
  converting.value = true
  try {
    const ids = await inquiryApi.convert(convertingId, convertWarehouseId.value)
    ElMessage.success(`已生成 ${ids.length} 张订单草稿`)
    convertVisible.value = false
    await load()
  } finally {
    converting.value = false
  }
}

const createVisible = ref(false)
const saving = ref(false)
const form = reactive<{ title: string; items: { skuId?: number; supplierId?: number; qty: number; price?: number; leadTimeDays?: number }[] }>({
  title: '', items: []
})
function openCreate() {
  Object.assign(form, { title: '', items: [{ skuId: undefined, supplierId: undefined, qty: 1 }] })
  createVisible.value = true
}
async function save() {
  saving.value = true
  try {
    await inquiryApi.create(form)
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
  await ElMessageBox.confirm(`审核询价单 ${row.docNo}？`, '审核确认', { type: 'warning' })
  await inquiryApi.audit(row.id)
  ElMessage.success('已审核')
  await load()
}
async function voidDoc(row: { id: number; docNo: string }) {
  const { value } = await ElMessageBox.prompt(`作废询价单 ${row.docNo}？原因必填`, '作废确认', {
    inputValidator: (v: string) => (v && v.trim() ? true : '原因必填'), type: 'warning'
  })
  await inquiryApi.void(row.id, value.trim())
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
.tip { font-size: 12.5px; color: var(--erp-muted); margin: 4px 0 0; }
</style>

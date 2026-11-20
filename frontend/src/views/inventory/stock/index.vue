<template>
  <div class="erp-page">
    <PageHeader title="库存" accent="查询" subtitle="仓库×SKU 汇总，展开看批次，批次行看 SN" />

    <el-card shadow="never">
      <div class="erp-toolbar">
        <el-input v-model="keyword" placeholder="编码/名称" clearable style="width: 200px" @keyup.enter="doSearch" />
        <el-select v-model="warehouseFilter" placeholder="仓库" clearable style="width: 160px" @change="doSearch">
          <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
        </el-select>
        <el-button type="primary" @click="doSearch">
          <template #icon><Search /></template>
          查询
        </el-button>
      </div>

      <el-table v-loading="loading" :data="rows" class="erp-table" row-key="rowKey" @expand-change="onExpand">
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="lot-panel">
              <el-table :data="row.lots || []" class="erp-table" size="small">
                <el-table-column prop="lotNo" label="批号" min-width="120" />
                <el-table-column prop="productionDate" label="生产日期" width="120" />
                <el-table-column prop="expiryDate" label="有效期至" width="120" />
                <el-table-column prop="qty" label="批次数量" width="100" />
                <el-table-column label="SN" width="90">
                  <template #default="{ row: lot }">
                    <el-button link type="primary" @click="openSns(row, lot)">
                      <template #icon><View /></template>
                      查看
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
              <el-empty v-if="row.lots && row.lots.length === 0" description="无批次行" :image-size="48" />
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="warehouseName" label="仓库" width="120" />
        <el-table-column prop="skuCode" label="编码" width="120" />
        <el-table-column prop="skuName" label="名称" min-width="140" />
        <el-table-column prop="qty" label="库存量" width="100" />
        <el-table-column prop="lockedQty" label="锁定" width="90" />
        <el-table-column prop="availableQty" label="可用量" width="100" />
        <el-table-column v-if="canViewCost" prop="costPrice" label="均价" width="110" />
        <el-table-column v-if="canViewCost" prop="costAmount" label="成本金额" width="120" />
        <el-table-column label="SN" width="80">
          <template #default="{ row }">
            <el-button v-if="row.snManaged && !row.lotManaged" link type="primary" @click="openSns(row, null)">
              <template #icon><View /></template>
              查看
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

    <el-dialog v-model="snVisible" :title="`SN 明细：${snContext}`" width="640px">
      <el-table :data="snRows" class="erp-table" size="small">
        <el-table-column prop="snCode" label="SN" min-width="140" />
        <el-table-column prop="status" label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.status === 'IN_STOCK' ? 'success' : 'info'" size="small">
              {{ row.status === 'IN_STOCK' ? '在库' : '已出库' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="inDocNo" label="最近入库单" width="150" />
        <el-table-column prop="outDocNo" label="最近出库单" width="150" />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { inventoryApi, type LotStockVO, type SnVO, type StockVO } from '@/api/inventory'
import { warehouseApi, type WarehouseVO } from '@/api/basedata'
import { useTable } from '@/composables/useTable'
import { useAuthStore } from '@/stores/auth'
import PageHeader from '@/components/PageHeader.vue'

const auth = useAuthStore()
const canViewCost = computed(() => auth.canViewCost)

const warehouses = ref<WarehouseVO[]>([])
const warehouseFilter = ref<number | undefined>(undefined)
const keyword = ref('')

type StockRow = StockVO & { rowKey: string; warehouseName?: string; lots?: LotStockVO[] | null }

const { loading, rows, total, query, load, search } = useTable<StockRow>(async (q) => {
  const page = await inventoryApi.stocks({
    ...q,
    keyword: keyword.value || undefined,
    warehouseId: warehouseFilter.value || undefined
  })
  return {
    total: page.total,
    records: page.records.map((r) => ({
      ...r,
      rowKey: `${r.warehouseId}-${r.skuId}`,
      warehouseName: warehouses.value.find((w) => w.id === r.warehouseId)?.name ?? String(r.warehouseId),
      lots: null
    }))
  }
})

function doSearch() {
  search()
}

// 展开行懒加载批次（首次展开才请求）
async function onExpand(row: StockRow, expandedRows: StockRow[]) {
  if (expandedRows.includes(row) && row.lots === null) {
    row.lots = await inventoryApi.lots(row.warehouseId, row.skuId)
  }
}

const snVisible = ref(false)
const snContext = ref('')
const snRows = ref<SnVO[]>([])

async function openSns(stock: StockRow, lot: LotStockVO | null) {
  snContext.value = lot ? `${stock.skuCode} / ${lot.lotNo ?? '-'}` : `${stock.skuCode} 在库 SN`
  // 服务端过滤：仓库×SKU×批次×在库（审查 I2：跨仓混入与 200 截断修复）
  const page = await inventoryApi.sns({
    skuId: stock.skuId,
    warehouseId: stock.warehouseId,
    lotId: lot?.lotId,
    status: 'IN_STOCK',
    pageSize: 200
  })
  snRows.value = page.records
  snVisible.value = true
}

onMounted(async () => {
  warehouses.value = await warehouseApi.list()
  await load()
})
</script>

<style scoped>
.lot-panel {
  padding: 8px 48px;
}
</style>

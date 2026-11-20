<template>
  <div class="erp-page">
    <PageHeader title="库存" accent="流水" subtitle="insert-only 审计轨迹，含变动后余额快照" />

    <el-card shadow="never">
      <div class="erp-toolbar">
        <el-select v-model="warehouseFilter" placeholder="仓库" clearable style="width: 140px" @change="doSearch">
          <el-option v-for="w in warehouses" :key="w.id" :label="w.name" :value="w.id" />
        </el-select>
        <el-select v-model="bizType" placeholder="业务类型" clearable style="width: 150px" @change="doSearch">
          <el-option v-for="t in bizTypes" :key="t" :label="t" :value="t" />
        </el-select>
        <el-input v-model="docNo" placeholder="单据号" clearable style="width: 180px" @keyup.enter="doSearch" />
        <el-date-picker
          v-model="dateRange"
          type="daterange"
          value-format="YYYY-MM-DD"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          style="width: 260px"
          @change="doSearch"
        />
        <el-button type="primary" @click="doSearch">
          <template #icon><Search /></template>
          查询
        </el-button>
      </div>

      <el-table v-loading="loading" :data="rows" class="erp-table">
        <el-table-column prop="createTime" label="时间" width="170" />
        <el-table-column prop="bizType" label="业务类型" width="150" />
        <el-table-column prop="bizDocNo" label="单据号" width="170" />
        <el-table-column prop="skuCode" label="编码" width="110" />
        <el-table-column prop="skuName" label="名称" min-width="120" />
        <el-table-column prop="qty" label="变动量" width="90" />
        <el-table-column v-if="canViewCost" prop="price" label="业务价" width="90" />
        <el-table-column v-if="canViewCost" prop="costPrice" label="成本单价" width="100" />
        <el-table-column v-if="canViewCost" prop="costAmount" label="成本金额" width="100" />
        <el-table-column prop="balanceQty" label="结余量" width="90" />
        <el-table-column v-if="canViewCost" prop="balanceCostAmount" label="结余金额" width="100" />
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
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { inventoryApi, type FlowVO } from '@/api/inventory'
import { warehouseApi, type WarehouseOption } from '@/api/basedata'
import { useTable } from '@/composables/useTable'
import { useAuthStore } from '@/stores/auth'
import PageHeader from '@/components/PageHeader.vue'

const auth = useAuthStore()
const canViewCost = computed(() => auth.canViewCost)

const bizTypes = ['OPENING', 'PUR_IN', 'PUR_RETURN_OUT', 'SALE_OUT', 'SAL_RETURN_IN',
  'TRANSFER_OUT', 'TRANSFER_IN', 'COUNT_GAIN_IN', 'COUNT_LOSS_OUT']

const warehouses = ref<WarehouseOption[]>([])
const warehouseFilter = ref<number | undefined>(undefined)
const bizType = ref<string | undefined>(undefined)
const docNo = ref('')
const dateRange = ref<[string, string] | null>(null)

const { loading, rows, total, query, load, search } = useTable<FlowVO>((q) =>
  inventoryApi.flows({
    ...q,
    warehouseId: warehouseFilter.value || undefined,
    bizType: bizType.value || undefined,
    bizDocNo: docNo.value || undefined,
    startDate: dateRange.value?.[0] || undefined,
    endDate: dateRange.value?.[1] || undefined
  })
)

onMounted(async () => {
  warehouses.value = await warehouseApi.options()
})

function doSearch() {
  search()
}
</script>

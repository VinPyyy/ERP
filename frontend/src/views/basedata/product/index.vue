<template>
  <div class="erp-page">
    <PageHeader title="商品" accent="档案" subtitle="商品档案、类别树与计量管理方式" />
    <div class="product-layout">
    <el-card shadow="never" class="tree-card">
      <template #header>
        <div class="tree-head">
          <span>类别</span>
          <el-button v-perm="'basedata:product:edit'" type="primary" @click="addCategory(0)">
            <template #icon><Plus /></template>
            新增根类
          </el-button>
        </div>
      </template>
      <el-tree
        :data="categoryTree"
        node-key="id"
        :props="{ label: 'name', children: 'children' }"
        highlight-current
        @node-click="(n: CategoryNode) => { selectedCategoryId = n.id; doSearch() }"
      >
        <template #default="{ data }">
          <span class="tree-node">
            <span>{{ data.name }}</span>
            <span class="tree-ops">
              <el-button v-perm="'basedata:product:edit'" link size="small" @click.stop="renameCategory(data)">改</el-button>
              <el-button v-perm="'basedata:product:edit'" link size="small" @click.stop="addCategory(data.id)">子</el-button>
              <el-button v-perm="'basedata:product:edit'" link size="small" type="danger" @click.stop="removeCategory(data)">删</el-button>
            </span>
          </span>
        </template>
      </el-tree>
      <el-button link type="primary" class="view-all" @click="clearCategory">查看全部商品</el-button>
    </el-card>

    <el-card shadow="never" class="list-card">
      <div class="erp-toolbar">
        <el-input v-model="keyword" placeholder="编码/名称/条码" clearable style="width: 200px" @keyup.enter="doSearch" />
        <el-select v-model="modeFilter" placeholder="管理方式" clearable style="width: 130px" @change="doSearch">
          <el-option v-for="m in mgmtOptions" :key="m.value" :label="m.label" :value="m.value" />
        </el-select>
        <el-button type="primary" @click="doSearch">
          <template #icon><Search /></template>
          查询
        </el-button>
        <el-button v-perm="'basedata:product:create'" type="primary" @click="openEdit(null)">
          <template #icon><Plus /></template>
          新增商品
        </el-button>
        <el-upload
          v-perm="'basedata:product:import'"
          :show-file-list="false"
          accept=".xlsx"
          :http-request="doImport"
        >
          <el-button type="primary">
            <template #icon><Upload /></template>
            导入 Excel
          </el-button>
        </el-upload>
        <el-button v-perm="'basedata:product:export'" type="primary" @click="doExport">
          <template #icon><Download /></template>
          导出
        </el-button>
      </div>

      <el-table v-loading="loading" :data="rows" class="erp-table">
        <el-table-column prop="skuCode" label="编码" width="120" />
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column prop="spec" label="规格" width="100" />
        <el-table-column prop="unit" label="单位" width="70" />
        <el-table-column label="管理方式" width="110">
          <template #default="{ row }">{{ mgmtOptions.find(m => m.value === row.mgmtMode)?.label }}</template>
        </el-table-column>
        <el-table-column label="保质期" width="90">
          <template #default="{ row }">{{ row.shelfLifeDays ? row.shelfLifeDays + '天' : '-' }}</template>
        </el-table-column>
        <el-table-column v-if="auth.canViewCost" prop="refSalePrice" label="参考售价" width="100" />
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.status === 1 ? 'success' : 'info'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button v-perm="'basedata:product:edit'" link type="primary" @click="openEdit(row)">
              <template #icon><Edit /></template>
              编辑
            </el-button>
            <el-button v-perm="'basedata:product:delete'" link type="danger" @click="remove(row)">
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
  </div>

  <el-dialog v-model="editVisible" :title="editForm.id ? '编辑商品' : '新增商品'" width="560px">
    <el-form :model="editForm" label-width="100px">
      <el-form-item label="编码" required><el-input v-model="editForm.skuCode" :disabled="!!editForm.id && referenced" /></el-form-item>
      <el-form-item label="名称" required><el-input v-model="editForm.name" /></el-form-item>
      <el-form-item label="规格"><el-input v-model="editForm.spec" /></el-form-item>
      <el-form-item label="单位" required><el-input v-model="editForm.unit" placeholder="如：个/箱/瓶" /></el-form-item>
      <el-form-item label="条码"><el-input v-model="editForm.barcode" /></el-form-item>
      <el-form-item label="类别">
        <el-select v-model="editForm.categoryId" clearable style="width: 100%">
          <el-option v-for="c in flatCategories" :key="c.id" :label="c.name" :value="c.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="管理方式" required>
        <el-radio-group v-model="editForm.mgmtMode" :disabled="!!editForm.id && referenced">
          <el-radio v-for="m in mgmtOptions" :key="m.value" :value="m.value">{{ m.label }}</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="lotManaged" label="保质期天数" required>
        <el-input-number v-model="editForm.shelfLifeDays" :min="1" />
      </el-form-item>
      <el-form-item v-if="lotManaged" label="临期预警天数">
        <el-input-number v-model="editForm.expireWarnDays" :min="0" />
        <span class="tip">三期预警用，可先不填</span>
      </el-form-item>
      <el-form-item label="参考售价"><el-input-number v-model="editForm.refSalePrice" :min="0" :precision="2" /></el-form-item>
      <el-form-item label="状态">
        <el-switch v-model="editForm.status" :active-value="1" :inactive-value="0" active-text="启用" />
      </el-form-item>
      <el-form-item label="备注"><el-input v-model="editForm.remark" type="textarea" /></el-form-item>
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

  <el-dialog v-model="importVisible" title="导入结果" width="520px">
    <p v-if="importResult && importResult.errors.length === 0">
      成功导入 {{ importResult.successCount }} 行
    </p>
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
import { computed, reactive, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  productApi, categoryApi, mgmtOptions, downloadBlob,
  type ProductVO, type ProductSaveRequest, type CategoryVO, type ImportErrors
} from '@/api/basedata'
import { useTable } from '@/composables/useTable'
import { useAuthStore } from '@/stores/auth'
import PageHeader from '@/components/PageHeader.vue'

interface CategoryNode extends CategoryVO { children: CategoryNode[] }

const auth = useAuthStore()
const { loading, rows, total, query, load, search } = useTable<ProductVO>((q) => productApi.page(q))

const keyword = ref('')
const modeFilter = ref<string | undefined>()
const selectedCategoryId = ref<number | undefined>()

function doSearch() {
  query.keyword = keyword.value || undefined
  query.mgmtMode = modeFilter.value
  query.categoryId = selectedCategoryId.value
  search()
}

function clearCategory() {
  selectedCategoryId.value = undefined
  doSearch()
}

// 类别树
const flatCategories = ref<CategoryVO[]>([])
const categoryTree = computed<CategoryNode[]>(() => {
  const byParent = new Map<number, CategoryNode[]>()
  for (const c of flatCategories.value) {
    const node = { ...c, children: [] } as CategoryNode
    const list = byParent.get(c.parentId) ?? []
    list.push(node)
    byParent.set(c.parentId, list)
  }
  const nodeMap = new Map<number, CategoryNode>()
  for (const list of byParent.values()) for (const n of list) nodeMap.set(n.id, n)
  for (const list of byParent.values()) {
    for (const n of list) n.children = byParent.get(n.id) ?? []
  }
  return byParent.get(0) ?? []
})

async function reloadCategories() {
  flatCategories.value = await categoryApi.list()
}
onMounted(reloadCategories)

async function addCategory(parentId: number) {
  const { value } = await ElMessageBox.prompt('类别名称', '新增类别', { inputPattern: /\S+/, inputErrorMessage: '名称必填' })
  await categoryApi.create({ name: value, parentId })
  await reloadCategories()
}

async function renameCategory(data: CategoryNode) {
  const { value } = await ElMessageBox.prompt('新名称', '重命名', { inputValue: data.name, inputPattern: /\S+/, inputErrorMessage: '名称必填' })
  await categoryApi.update(data.id, { name: value, parentId: data.parentId, sortNo: data.sortNo })
  await reloadCategories()
}

async function removeCategory(data: CategoryNode) {
  await ElMessageBox.confirm(`删除类别 ${data.name}？`, '确认', { type: 'warning' })
  await categoryApi.remove(data.id)
  await reloadCategories()
}

// 编辑
const editVisible = ref(false)
const saving = ref(false)
const referenced = ref(false) // 已有 id 即谨慎放开模式修改，服务端仍是最终防线
const editForm = reactive<ProductSaveRequest & { id?: number }>({
  skuCode: '', name: '', spec: '', unit: '', barcode: '', categoryId: null,
  mgmtMode: 'NONE', shelfLifeDays: null, expireWarnDays: null, refSalePrice: null, status: 1, remark: ''
})
const lotManaged = computed(() => editForm.mgmtMode === 'LOT' || editForm.mgmtMode === 'LOT_SN')

function openEdit(row: ProductVO | null) {
  referenced.value = !!row
  Object.assign(editForm, row ? {
    id: row.id, skuCode: row.skuCode, name: row.name, spec: row.spec ?? '', unit: row.unit,
    barcode: row.barcode ?? '', categoryId: row.categoryId ?? null, mgmtMode: row.mgmtMode,
    shelfLifeDays: row.shelfLifeDays ?? null, expireWarnDays: row.expireWarnDays ?? null,
    refSalePrice: row.refSalePrice ?? null, status: row.status, remark: row.remark ?? ''
  } : {
    id: undefined, skuCode: '', name: '', spec: '', unit: '', barcode: '',
    categoryId: selectedCategoryId.value ?? null, mgmtMode: 'NONE', shelfLifeDays: null,
    expireWarnDays: null, refSalePrice: null, status: 1, remark: ''
  })
  editVisible.value = true
}

async function save() {
  saving.value = true
  try {
    if (editForm.id) {
      await productApi.update(editForm.id, editForm)
    } else {
      await productApi.create(editForm)
    }
    ElMessage.success('已保存')
    editVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

async function remove(row: ProductVO) {
  await ElMessageBox.confirm(`删除商品 ${row.name}？（有库存引用将被拒绝）`, '确认', { type: 'warning' })
  await productApi.remove(row.id)
  ElMessage.success('已删除')
  await load()
}

// 导入导出
const importVisible = ref(false)
const importResult = ref<ImportErrors | null>(null)

async function doImport(options: { file: File }) {
  importResult.value = await productApi.importFile(options.file)
  importVisible.value = true
  if (importResult.value.errors.length === 0) await load()
}

async function doExport() {
  const blob = await productApi.exportXlsx({ keyword: keyword.value, mgmtMode: modeFilter.value, categoryId: selectedCategoryId.value })
  downloadBlob(blob, 'products.xlsx')
}
</script>

<style scoped>
/* stretch：树卡与右侧列表卡等高，空态时底边对齐 */
.product-layout { display: flex; gap: 12px; align-items: stretch; }
.tree-card { width: 240px; flex-shrink: 0; }
.tree-card :deep(.el-card__header) { border-bottom: 0; }
.view-all { margin-top: var(--erp-space-1); align-self: flex-start; }
.list-card { flex: 1; min-width: 0; }
.tree-head { display: flex; justify-content: space-between; align-items: center; }
/* el-tree 默认白底会在玻璃卡上留下白斑；空态高度对齐右侧表格（60px） */
.tree-card :deep(.el-tree) { background: transparent; }
.tree-card :deep(.el-tree__empty-block) { min-height: 60px; height: 60px; }
.tree-node { display: flex; justify-content: space-between; width: 100%; align-items: center; }
.tree-ops { display: none; }
.tree-node:hover .tree-ops { display: inline; }
/* toolbar/pager 统一走 tokens.css 的 .erp-* 类 */
.tip { margin-left: 8px; color: var(--el-text-color-secondary); font-size: 12px; }
.mb8 { margin-bottom: 8px; }
</style>

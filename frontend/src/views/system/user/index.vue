<template>
  <div class="erp-page">
    <PageHeader title="用户" accent="管理" subtitle="管理系统账号、角色分配与可见仓库" />

    <el-card shadow="never">
    <div class="erp-toolbar">
      <el-input v-model="keyword" placeholder="用户名/姓名" clearable style="width: 220px" @keyup.enter="doSearch" />
      <el-select v-model="statusFilter" placeholder="状态" clearable style="width: 120px" @change="doSearch">
        <el-option label="启用" :value="1" />
        <el-option label="停用" :value="0" />
      </el-select>
      <el-button type="primary" @click="doSearch">
        <template #icon><Search /></template>
        查询
      </el-button>
      <el-button v-perm="'system:user:create'" type="primary" @click="openCreate">
        <template #icon><Plus /></template>
        新增用户
      </el-button>
    </div>

    <el-table v-loading="loading" :data="rows" class="erp-table">
      <el-table-column prop="username" label="用户名" width="140" />
      <el-table-column prop="realName" label="姓名" width="120" />
      <el-table-column prop="phone" label="手机" width="130" />
      <el-table-column label="角色" min-width="160">
        <template #default="{ row }">{{ row.roleNames.join('、') }}</template>
      </el-table-column>
      <el-table-column label="状态" width="80">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'danger'">{{ row.status === 1 ? '启用' : '停用' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="lastLoginTime" label="最后登录" width="170" />
      <el-table-column label="操作" width="270" fixed="right">
        <template #default="{ row }">
          <el-button v-perm="'system:user:edit'" link type="primary" @click="openEdit(row)">
            <template #icon><Edit /></template>
            编辑
          </el-button>
          <el-button v-perm="'system:user:reset-password'" link type="warning" @click="openReset(row)">
            <template #icon><Refresh /></template>
            重置密码
          </el-button>
          <el-button v-perm="'system:user:delete'" link type="danger" :disabled="row.username === 'admin'" @click="remove(row)">
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

  <el-dialog v-model="dialogVisible" :title="form.id ? '编辑用户' : '新增用户'" width="520px">
    <el-form :model="form" label-width="90px">
      <el-form-item label="用户名" required>
        <el-input v-model="form.username" :disabled="!!form.id" />
      </el-form-item>
      <el-form-item v-if="!form.id" label="密码" required>
        <el-input v-model="form.password" type="password" show-password />
      </el-form-item>
      <el-form-item label="姓名"><el-input v-model="form.realName" /></el-form-item>
      <el-form-item label="手机"><el-input v-model="form.phone" /></el-form-item>
      <el-form-item label="角色">
        <el-select v-model="form.roleIds" multiple style="width: 100%">
          <el-option v-for="r in roles" :key="r.id" :label="r.roleName" :value="r.id" />
        </el-select>
      </el-form-item>
      <el-form-item label="可见仓库">
        <el-select v-model="form.warehouseIds" multiple style="width: 100%" placeholder="勾选「全部仓库」或选择具体仓库">
          <el-option label="全部仓库" :value="0" />
          <!-- 具体仓库选项 M2 接入 /api/basedata/warehouses -->
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-switch v-model="form.status" :active-value="1" :inactive-value="0" active-text="启用" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="dialogVisible = false">
        <template #icon><Close /></template>
        取消
      </el-button>
      <el-button type="primary" :loading="saving" @click="save">
        <template #icon><Check /></template>
        保存
      </el-button>
    </template>
  </el-dialog>

  <el-dialog v-model="resetVisible" title="重置密码" width="380px">
    <el-input v-model="resetPassword" type="password" show-password placeholder="新密码（至少 6 位）" />
    <template #footer>
      <el-button @click="resetVisible = false">
        <template #icon><Close /></template>
        取消
      </el-button>
      <el-button type="primary" @click="doReset">
        <template #icon><Check /></template>
        确定
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { userApi, roleApi, type UserVO, type UserSaveRequest, type RoleVO } from '@/api/system'
import { encryptPassword } from '@/api/passwordCrypto'
import { useTable } from '@/composables/useTable'
import PageHeader from '@/components/PageHeader.vue'

const { loading, rows, total, query, load, search } = useTable<UserVO>(
  (q) => userApi.page(q)
)

const keyword = ref('')
const statusFilter = ref<number | undefined>(undefined)

function doSearch() {
  query.keyword = keyword.value || undefined
  query.status = statusFilter.value
  search()
}

const roles = ref<RoleVO[]>([])
const dialogVisible = ref(false)
const saving = ref(false)
// password 仅 UI 本地态：提交前经 encryptPassword 转成 encryptedPassword/nonce/ts
const form = reactive<UserSaveRequest & { id?: number; password: string }>({
  username: '', password: '', realName: '', phone: '', status: 1, roleIds: [], warehouseIds: []
})

const resetVisible = ref(false)
const resetPassword = ref('')
let resettingId = 0

onMounted(async () => {
  roles.value = await roleApi.list()
})

function openCreate() {
  Object.assign(form, { id: undefined, username: '', password: '', realName: '', phone: '', status: 1, roleIds: [], warehouseIds: [] })
  dialogVisible.value = true
}

function openEdit(row: UserVO) {
  Object.assign(form, {
    id: row.id, username: row.username, realName: row.realName, phone: row.phone,
    status: row.status, roleIds: [...row.roleIds], warehouseIds: [...row.warehouseIds]
  })
  dialogVisible.value = true
}

async function save() {
  // 密码只在本地 form 里存在，提交前加密；编辑不涉及密码
  if (!form.id && form.password.length < 6) {
    ElMessage.warning('密码至少 6 位')
    return
  }
  saving.value = true
  try {
    const { password, ...base } = form
    if (form.id) {
      await userApi.update(form.id, base)
    } else {
      const cred = await encryptPassword(password)
      await userApi.create({ ...base, ...cred })
    }
    ElMessage.success('已保存')
    dialogVisible.value = false
    await load()
  } finally {
    saving.value = false
  }
}

function openReset(row: UserVO) {
  resettingId = row.id
  resetPassword.value = ''
  resetVisible.value = true
}

async function doReset() {
  if (resetPassword.value.length < 6) {
    ElMessage.warning('新密码至少 6 位')
    return
  }
  const cred = await encryptPassword(resetPassword.value)
  await userApi.resetPassword(resettingId, cred)
  ElMessage.success('密码已重置')
  resetVisible.value = false
}

async function remove(row: UserVO) {
  await ElMessageBox.confirm(`确认删除用户 ${row.username}？`, '删除确认', { type: 'warning' })
  await userApi.remove(row.id)
  ElMessage.success('已删除')
  await load()
}
</script>

<style scoped>
/* 页面骨架样式统一走 tokens.css 的 .erp-* 类 */
</style>

<template>
  <div class="erp-page">
    <PageHeader title="角色" accent="管理" subtitle="定义角色并分配权限点">
      <template #extra>
        <el-button v-perm="'system:role:create'" type="primary" @click="openCreate">
          <template #icon><Plus /></template>
          新增角色
        </el-button>
      </template>
    </PageHeader>

    <el-card shadow="never">
    <el-table v-loading="loading" :data="roles" class="erp-table">
      <el-table-column prop="roleCode" label="角色编码" width="160" />
      <el-table-column prop="roleName" label="角色名称" width="160" />
      <el-table-column label="类型" width="100">
        <template #default="{ row }">
          <el-tag v-if="row.builtIn === 1" type="info">预置</el-tag>
          <el-tag v-else>自定义</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="180" />
      <el-table-column label="操作" width="270" fixed="right">
        <template #default="{ row }">
          <el-button v-perm="'system:role:grant'" link type="primary" @click="openGrant(row)">
            <template #icon><Key /></template>
            授权
          </el-button>
          <el-button v-perm="'system:role:edit'" link type="primary" @click="openEdit(row)">
            <template #icon><Edit /></template>
            编辑
          </el-button>
          <el-button v-perm="'system:role:delete'" link type="danger" :disabled="row.builtIn === 1" @click="remove(row)">
            <template #icon><Delete /></template>
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    </el-card>
  </div>

  <el-dialog v-model="dialogVisible" :title="form.id ? '编辑角色' : '新增角色'" width="420px">
    <el-form :model="form" label-width="90px">
      <el-form-item label="角色编码" required><el-input v-model="form.roleCode" /></el-form-item>
      <el-form-item label="角色名称" required><el-input v-model="form.roleName" /></el-form-item>
      <el-form-item label="备注"><el-input v-model="form.remark" type="textarea" /></el-form-item>
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

  <el-drawer v-model="grantVisible" :title="`授权：${grantingRole?.roleName ?? ''}`" size="420px">
    <el-tree
      ref="treeRef"
      :data="permTree"
      node-key="id"
      show-checkbox
      default-expand-all
      :props="{ label: 'permName', children: 'children' }"
    />
    <template #footer>
      <el-button @click="grantVisible = false">
        <template #icon><Close /></template>
        取消
      </el-button>
      <el-button type="primary" :loading="granting" @click="doGrant">
        <template #icon><Check /></template>
        保存授权
      </el-button>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { reactive, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import type { ElTree } from 'element-plus'
import {
  roleApi, permissionApi,
  type RoleVO, type PermissionNode
} from '@/api/system'
import PageHeader from '@/components/PageHeader.vue'

const loading = ref(false)
const roles = ref<RoleVO[]>([])
const permTree = ref<PermissionNode[]>([])

const dialogVisible = ref(false)
const saving = ref(false)
const form = reactive<{ id?: number; roleCode: string; roleName: string; remark: string }>({
  roleCode: '', roleName: '', remark: ''
})

const grantVisible = ref(false)
const granting = ref(false)
const grantingRole = ref<RoleVO | null>(null)
const treeRef = ref<InstanceType<typeof ElTree>>()

async function reload() {
  loading.value = true
  try {
    roles.value = await roleApi.list()
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  await reload()
  permTree.value = await permissionApi.tree()
})

function openCreate() {
  Object.assign(form, { id: undefined, roleCode: '', roleName: '', remark: '' })
  dialogVisible.value = true
}

function openEdit(row: RoleVO) {
  Object.assign(form, { id: row.id, roleCode: row.roleCode, roleName: row.roleName, remark: row.remark ?? '' })
  dialogVisible.value = true
}

async function save() {
  saving.value = true
  try {
    if (form.id) {
      await roleApi.update(form.id, { roleCode: form.roleCode, roleName: form.roleName, remark: form.remark })
    } else {
      await roleApi.create({ roleCode: form.roleCode, roleName: form.roleName, remark: form.remark })
    }
    ElMessage.success('已保存')
    dialogVisible.value = false
    await reload()
  } finally {
    saving.value = false
  }
}

async function remove(row: RoleVO) {
  await ElMessageBox.confirm(`确认删除角色 ${row.roleName}？`, '删除确认', { type: 'warning' })
  await roleApi.remove(row.id)
  ElMessage.success('已删除')
  await reload()
}

async function openGrant(row: RoleVO) {
  grantingRole.value = row
  grantVisible.value = true
  const checked = await roleApi.permissionIds(row.id)
  // 回显：叶子精确勾选，父节点由 el-tree 半选自动推导
  const leafIds = new Set<number>()
  const collectLeaves = (nodes: PermissionNode[]) => {
    for (const n of nodes) {
      if (n.children.length === 0) leafIds.add(n.id)
      else collectLeaves(n.children)
    }
  }
  collectLeaves(permTree.value)
  treeRef.value?.setCheckedKeys(checked.filter((id) => leafIds.has(id)))
}

async function doGrant() {
  if (!grantingRole.value || !treeRef.value) return
  granting.value = true
  try {
    const keys = [...treeRef.value.getCheckedKeys(), ...treeRef.value.getHalfCheckedKeys()] as number[]
    await roleApi.grant(grantingRole.value.id, keys)
    ElMessage.success('授权已更新')
    grantVisible.value = false
  } finally {
    granting.value = false
  }
}
</script>

<style scoped>
/* 骨架样式统一走 tokens.css 的 .erp-* 类 */
</style>

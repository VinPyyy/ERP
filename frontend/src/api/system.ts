import http from './http'
import type { EncryptedCredential } from './passwordCrypto'

/** 系统管理 API 封装（M1）。http 拦截器已剥 R 外壳，返回 data。 */

export interface UserVO {
  id: number
  username: string
  realName?: string
  phone?: string
  status: number
  lastLoginTime?: string
  createTime?: string
  remark?: string
  roleIds: number[]
  roleNames: string[]
  warehouseIds: number[]
}

export interface UserSaveRequest {
  username: string
  /** create 必填（RSA-OAEP 密文，来自 encryptPassword）；update 忽略 */
  encryptedPassword?: string
  nonce?: string
  ts?: number
  realName?: string
  phone?: string
  status?: number
  remark?: string
  roleIds?: number[]
  warehouseIds?: number[]
}

export const userApi = {
  page: (params: Record<string, unknown>) => http.get('/system/users', { params }) as any as Promise<{ total: number; records: UserVO[] }>,
  get: (id: number) => http.get(`/system/users/${id}`) as any as Promise<UserVO>,
  create: (data: UserSaveRequest) => http.post('/system/users', data) as any as Promise<number>,
  update: (id: number, data: UserSaveRequest) => http.put(`/system/users/${id}`, data),
  /** 新密码经一次性挑战 RSA-OAEP 加密后传输（与登录同一机制） */
  resetPassword: (id: number, cred: EncryptedCredential) => http.put(`/system/users/${id}/password`, cred),
  remove: (id: number) => http.delete(`/system/users/${id}`)
}

export interface RoleVO {
  id: number
  roleCode: string
  roleName: string
  builtIn: number
  remark?: string
}

export const roleApi = {
  list: () => http.get('/system/roles') as any as Promise<RoleVO[]>,
  create: (data: { roleCode: string; roleName: string; remark?: string }) => http.post('/system/roles', data) as any as Promise<number>,
  update: (id: number, data: { roleCode: string; roleName: string; remark?: string }) => http.put(`/system/roles/${id}`, data),
  remove: (id: number) => http.delete(`/system/roles/${id}`),
  permissionIds: (id: number) => http.get(`/system/roles/${id}/permissions`) as any as Promise<number[]>,
  grant: (id: number, permissionIds: number[]) => http.put(`/system/roles/${id}/permissions`, permissionIds)
}

export interface PermissionNode {
  id: number
  permCode: string
  permName: string
  type: string
  parentId: number
  children: PermissionNode[]
}

export const permissionApi = {
  tree: () => http.get('/system/permissions/tree') as any as Promise<PermissionNode[]>
}

export interface ConfigVO {
  id: number
  paramKey: string
  paramName: string
  paramValue?: string
  valueType: string
  remark?: string
}

export const configApi = {
  list: () => http.get('/system/configs') as any as Promise<ConfigVO[]>,
  update: (key: string, value: string) => http.put(`/system/configs/${key}`, { value })
}

export interface DocNoRuleVO {
  id: number
  docType: string
  prefix: string
  datePattern?: string
  seqLength: number
  seqScope: string
  currentSeq: number
  currentPeriod?: string
}

export const docNoApi = {
  list: () => http.get('/system/doc-no-rules') as any as Promise<DocNoRuleVO[]>,
  update: (id: number, data: Partial<DocNoRuleVO>) => http.put(`/system/doc-no-rules/${id}`, data)
}

import http from './http'

export interface CategoryVO {
  id: number
  name: string
  parentId: number
  sortNo: number
}

export interface ProductVO {
  id: number
  skuCode: string
  name: string
  spec?: string
  unit: string
  barcode?: string
  categoryId?: number
  /** Jackson 序列化为枚举名：NONE/LOT/SN/LOT_SN */
  mgmtMode: 'NONE' | 'LOT' | 'SN' | 'LOT_SN'
  shelfLifeDays?: number
  expireWarnDays?: number
  refSalePrice?: number
  status: number
  remark?: string
  createTime?: string
}

export interface ProductSaveRequest {
  skuCode: string
  name: string
  spec?: string
  unit: string
  barcode?: string
  categoryId?: number | null
  mgmtMode: string
  shelfLifeDays?: number | null
  expireWarnDays?: number | null
  refSalePrice?: number | null
  status?: number
  remark?: string
}

export interface ImportErrors {
  total: number
  successCount: number
  errors: { row: number; reason: string }[]
}

const mgmtOptions = [
  { value: 'NONE', label: '不管' },
  { value: 'LOT', label: '管批次' },
  { value: 'SN', label: '管SN' },
  { value: 'LOT_SN', label: '批次+SN' }
]
export { mgmtOptions }

export const productApi = {
  page: (params: Record<string, unknown>) =>
    http.get('/basedata/products', { params }) as any as Promise<{ total: number; records: ProductVO[] }>,
  options: () => http.get('/basedata/products/options') as any as Promise<ProductOption[]>,
  create: (data: ProductSaveRequest) => http.post('/basedata/products', data) as any as Promise<number>,
  update: (id: number, data: ProductSaveRequest) => http.put(`/basedata/products/${id}`, data),
  remove: (id: number) => http.delete(`/basedata/products/${id}`),
  importFile: (file: File) => {
    const fd = new FormData()
    fd.append('file', file)
    return http.post('/basedata/products/import', fd) as any as Promise<ImportErrors>
  },
  exportXlsx: (params: Record<string, unknown>) =>
    http.get('/basedata/products/export', { params, responseType: 'blob' }) as any as Promise<Blob>
}

export const categoryApi = {
  list: () => http.get('/basedata/categories') as any as Promise<CategoryVO[]>,
  create: (data: { name: string; parentId?: number; sortNo?: number }) =>
    http.post('/basedata/categories', data) as any as Promise<number>,
  update: (id: number, data: { name: string; parentId?: number; sortNo?: number }) =>
    http.put(`/basedata/categories/${id}`, data),
  remove: (id: number) => http.delete(`/basedata/categories/${id}`)
}

export interface PartnerVO {
  id: number
  code: string
  name: string
  contact?: string
  phone?: string
  address?: string
  taxNo?: string
  status: number
  remark?: string
}

export interface PartnerSaveRequest {
  code: string
  name: string
  contact?: string
  phone?: string
  address?: string
  taxNo?: string
  status?: number
  remark?: string
}

/** 下拉引用数据（/options，登录即可，docs/system/reference-options.md） */
export interface PartnerOption {
  id: number
  code: string
  name: string
}
export interface WarehouseOption {
  id: number
  code: string
  name: string
}
export interface ProductOption {
  id: number
  skuCode: string
  name: string
  unit: string
}

type PartnerApi = {
  page: (params: Record<string, unknown>) => Promise<{ total: number; records: PartnerVO[] }>
  options: () => Promise<PartnerOption[]>
  create: (data: PartnerSaveRequest) => Promise<number>
  update: (id: number, data: PartnerSaveRequest) => Promise<void>
  remove: (id: number) => Promise<void>
  importFile: (file: File) => Promise<ImportErrors>
  exportXlsx: (params: Record<string, unknown>) => Promise<Blob>
}

function buildPartnerApi(base: string): PartnerApi {
  return {
    page: (params) => http.get(base, { params }) as any,
    options: () => http.get(`${base}/options`) as any,
    create: (data) => http.post(base, data) as any,
    update: (id, data) => http.put(`${base}/${id}`, data),
    remove: (id) => http.delete(`${base}/${id}`),
    importFile: (file) => {
      const fd = new FormData()
      fd.append('file', file)
      return http.post(`${base}/import`, fd) as any
    },
    exportXlsx: (params) => http.get(`${base}/export`, { params, responseType: 'blob' }) as any
  }
}

export const customerApi = buildPartnerApi('/basedata/customers')
export const supplierApi = buildPartnerApi('/basedata/suppliers')

export interface WarehouseVO {
  id: number
  code: string
  name: string
  address?: string
  keeper?: string
  status: number
  sortNo: number
  remark?: string
}

export const warehouseApi = {
  list: () => http.get('/basedata/warehouses') as any as Promise<WarehouseVO[]>,
  options: () => http.get('/basedata/warehouses/options') as any as Promise<WarehouseOption[]>,
  create: (data: Partial<WarehouseVO>) => http.post('/basedata/warehouses', data) as any as Promise<number>,
  update: (id: number, data: Partial<WarehouseVO>) => http.put(`/basedata/warehouses/${id}`, data),
  remove: (id: number) => http.delete(`/basedata/warehouses/${id}`)
}

/** 通用浏览器下载 xlsx Blob */
export function downloadBlob(blob: Blob, filename: string) {
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

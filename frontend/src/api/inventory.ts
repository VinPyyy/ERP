import http from './http'

/** 库存查询与期初 API（M3c）。http 拦截器已剥 R 外壳。 */

export interface StockVO {
  warehouseId: number
  skuId: number
  skuCode?: string
  skuName?: string
  unit?: string
  qty: number
  lockedQty: number
  availableQty: number
  costPrice?: number | null
  costAmount?: number | null
}

export interface LotStockVO {
  lotId: number
  lotNo?: string
  productionDate?: string
  expiryDate?: string
  qty: number
}

export interface SnVO {
  id: number
  skuId: number
  snCode: string
  lotId?: number
  status: string
  warehouseId?: number | null
  inDocNo?: string
  inTime?: string
  outDocNo?: string
  outTime?: string
  remark?: string
}

export interface FlowVO {
  id: number
  warehouseId: number
  skuId: number
  skuCode?: string
  skuName?: string
  lotId?: number
  qty: number
  price?: number | null
  costPrice: number
  costAmount: number
  balanceQty: number
  balanceCostAmount: number
  balanceCostPrice: number
  bizType: string
  bizDocNo: string
  createTime?: string
}

export interface OpeningVO {
  id: number
  docNo: string
  status: number
  warehouseId: number
  docDate: string
  auditTime?: string
  voidReason?: string
  items?: OpeningItemVO[]
}

export interface OpeningItemVO {
  id: number
  lineNo: number
  skuId: number
  skuName?: string
  qty: number
  costPrice: number
  costAmount?: number
  lotNo?: string
  productionDate?: string
  snList?: string[]
}

export interface ImportResultVO {
  total: number
  successCount: number
  errors: { row: number; reason: string }[]
}

interface Page<T> {
  total: number
  records: T[]
}

export const inventoryApi = {
  stocks: (params: Record<string, unknown>) =>
    http.get('/inventory/stocks', { params }) as any as Promise<Page<StockVO>>,
  lots: (warehouseId: number, skuId: number) =>
    http.get('/inventory/stocks/lots', { params: { warehouseId, skuId } }) as any as Promise<LotStockVO[]>,
  sns: (params: Record<string, unknown>) =>
    http.get('/inventory/sns', { params }) as any as Promise<Page<SnVO>>,
  adjustSn: (id: number, body: { status: string; warehouseId?: number; reason: string }) =>
    http.post(`/inventory/sns/${id}/adjust`, body),
  flows: (params: Record<string, unknown>) =>
    http.get('/inventory/flows', { params }) as any as Promise<Page<FlowVO>>,
  replayCheck: () => http.get('/inventory/flows/replay-check') as any as Promise<{ consistent: boolean; diffs: unknown[] }>
}

export const openingApi = {
  page: (params: Record<string, unknown>) =>
    http.get('/inventory/openings', { params }) as any as Promise<Page<OpeningVO>>,
  get: (id: number) => http.get(`/inventory/openings/${id}`) as any as Promise<OpeningVO>,
  create: (body: { warehouseId: number; items: { skuId: number; qty: number; costPrice: number }[] }) =>
    http.post('/inventory/openings', body) as any as Promise<number>,
  audit: (id: number) => http.post(`/inventory/openings/${id}/audit`),
  void: (id: number, reason: string) => http.post(`/inventory/openings/${id}/void`, { reason }),
  importFile: (id: number, file: File) => {
    const form = new FormData()
    form.append('file', file)
    return http.post(`/inventory/openings/${id}/import`, form, {
      headers: { 'Content-Type': 'multipart/form-data' }
    }) as any as Promise<ImportResultVO>
  },
  templateUrl: '/api/inventory/openings/template'
}
